package com.boxpace.data.cloud

import com.boxpace.domain.DeltaPendente
import com.boxpace.domain.EncomendaRepository
import com.boxpace.domain.PreferenciasRepository
import com.boxpace.domain.SincronizacaoRepository
import com.boxpace.domain.SyncState
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordenador de sincronização — **único escritor** do arquivo canônico no
 * Drive (AD-SYNC-1). Executa o ciclo read → merge LWW → write → reconcile
 * (read-back) dentro de um mutex; um sync em voo por vez; 401 aborta o ciclo;
 * ESCRITA_VENCIDA retenta com backoff exponencial (cap [MAX_TENTATIVAS]).
 *
 * Drena os deltas pendentes do Room (Salvar/Excluir/SalvarPreferencia) em um
 * único write e só os remove após sucesso confirmado pelo read-back (AD-SYNC-9).
 */
class CoordenadorDeSync(
    private val encomendaRepository: EncomendaRepository,
    private val preferenciasRepository: PreferenciasRepository,
    private val drive: DriveCliente,
    private val tokenProvider: TokenOAuthProvider,
    private val scope: CoroutineScope,
) : SincronizacaoRepository {

    private val mutex = Mutex()
    private val emVoo = AtomicBoolean(false)

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Desvinculado)
    override val syncState = _syncState.asStateFlow()

    /** verdadeiro quando já houve um vínculo bem-sucedido nesta sessão. */
    private val vinculado = AtomicBoolean(false)

    /**
     * Gatilho da mutação persistida (fire-and-forget da coroutine do
     * repositório) e da reconexão de rede. Sem token não há o que sincronizar
     * (nunca entra em ciclo — evita SincronizacaoPerdida no app-start).
     */
    fun dispararSync() {
        if (tokenProvider.atual() == null) return
        if (!emVoo.compareAndSet(false, true)) return
        scope.launch {
            try {
                // drena: um ciclo por lote; deltas que chegarem durante o ciclo
                // disparam uma rodada nova até a fila esvaziar (jamais descartados)
                var rodadas = 0
                while (rodadas < MAX_RODADAS_DRENAGEM) {
                    rodadas++
                    val ok = mutex.withLock { ciclo(Motivo.DELTAS) }
                    if (!ok) break
                    if (encomendaRepository.listarDeltasPendentes().isEmpty()) break
                }
            } catch (_: Exception) {
                // nunca derruba a UI; falha já é sinalizada pelo estado do ciclo
            } finally {
                emVoo.set(false)
            }
        }
    }

    override suspend fun vincular(): Boolean {
        if (tokenProvider.atual() == null) return false
        return try {
            mutex.withLock {
                // Promover-vs-restaurar vem de UMA leitura canônica (AD-SYNC-5A/6A):
                // canônico ausente ⇒ promoção do cache (grava); presente ⇒ restore
                // pull-only (não grava). Falha/401 de leitura NUNCA promovem às cegas.
                val leitura = lerCanonico()
                if (leitura is LeituraCanonico.Ok && leitura.arquivo == null) {
                    ciclo(Motivo.PRIMEIRO_VINCULO, leitura)
                } else {
                    restaurarInterno(leitura)
                }
            }
        } catch (_: Exception) {
            _syncState.value = if (vinculado.get()) SyncState.Vinculado else SyncState.Desvinculado
            false
        }
    }

    override suspend fun restaurar(): Boolean {
        if (tokenProvider.atual() == null) return false
        return try {
            mutex.withLock { restaurarInterno(lerCanonico()) }
        } catch (_: Exception) {
            // nunca deixa o Restaurando preso (AD-SYNC-6B)
            _syncState.value = if (vinculado.get()) SyncState.Vinculado else SyncState.Desvinculado
            false
        }
    }

    /**
     * Restauração pull-only compartilhada por [vincular] e [restaurar]. Recebe a
     * leitura única do canônico (evita re-listagem), nunca relê. Exceção no corpo
     * (leitura+merge+reconcile) cai em estado terminal: nunca deixa o
     * [SyncState.Restaurando] preso (AD-SYNC-6B).
     */
    private suspend fun restaurarInterno(leitura: LeituraCanonico): Boolean {
        _syncState.value = SyncState.Restaurando
        return try {
            when (leitura) {
                is LeituraCanonico.NaoAutorizado -> {
                    // token expirado/revogado: deltas preservados, sem spinner congelado
                    _syncState.value = SyncState.SincronizacaoPerdida
                    false
                }
                is LeituraCanonico.Falha -> {
                    // sem rede: local intacto, aviso discreto; volta a Vinculado/Desvinculado
                    _syncState.value = if (vinculado.get()) SyncState.Vinculado else SyncState.Desvinculado
                    false
                }
                is LeituraCanonico.SchemaMaior -> {
                    // restore é PULADO: nada é espelhado nem escrito; aviso EmPausa persistido
                    vinculado.set(true)
                    _syncState.value = SyncState.SincronizacaoEmPausa(
                        "Arquivo no Drive mais novo que o app — restore pausado neste arquivo"
                    )
                    true
                }
                is LeituraCanonico.Ok -> {
                    if (leitura.arquivo != null) {
                        // pull-only: canônico → merge LWW com deltas → espelha no Room.
                        // NUNCA escreve no Drive e NÃO limpa deltas (AD-SYNC-9).
                        val deltas = encomendaRepository.listarDeltasPendentes()
                        val candidato = MergeRegistros.aplicarDeltas(leitura.canonico, deltas)
                        reconciliarRoom(candidato)
                        vinculado.set(true)
                        _syncState.value = SyncState.Vinculado
                        // restore concluído com deltas pendentes ⇒ deixa o sync drená-los
                        // (auto-sync pós-restore; quem consome deltas é só o sync — AD-SYNC-9)
                        if (deltas.isNotEmpty()) {
                            dispararSync()
                        }
                    } else {
                        // canônico ausente: nada a restaurar — local intocado (EDGE_CANONICO_AUSENTE)
                        vinculado.set(true)
                        _syncState.value = SyncState.Vinculado
                    }
                    true
                }
            }
        } catch (_: Exception) {
            _syncState.value = if (vinculado.get()) SyncState.Vinculado else SyncState.Desvinculado
            false
        }
    }

    override suspend fun reconectar(): Boolean {
        if (tokenProvider.atual() == null) return false
        return mutex.withLock {
            val ok = ciclo(Motivo.RECONECTAR)
            ok
        }
    }

    override suspend fun desvincular(): Boolean {
        return mutex.withLock {
            val autorizadoEvinculado = tokenProvider.atual() != null && vinculado.get()
            if (autorizadoEvinculado && !despejarDeltas()) {
                // descarga falhou: aborta para não perder deltas (AC Desvincular)
                return@withLock false
            }
            vinculado.set(false)
            tokenProvider.limpar()
            _syncState.value = SyncState.Desvinculado
            true
        }
    }

    // --- ciclo central ---

    private suspend fun ciclo(motivo: Motivo, primeiroLeitura: LeituraCanonico? = null): Boolean {
        _syncState.value = SyncState.Sincronizando
        var tentativas = 0
        while (tentativas < MAX_TENTATIVAS) {
            tentativas++
            // 1ª tentativa reusa a leitura que decidiu o caminho (promoção); retentativas relêem
            val leitura = if (tentativas == 1 && primeiroLeitura != null) primeiroLeitura else lerCanonico()
            when (val resultado = cicloTentativa(motivo, leitura)) {
                CicloResultado.Sucesso -> {
                    vinculado.set(true)
                    _syncState.value = SyncState.Vinculado
                    return true
                }
                CicloResultado.NaoAutorizado -> {
                    _syncState.value = SyncState.SincronizacaoPerdida
                    return false
                }
                CicloResultado.ESCRITA_VENCIDA,
                CicloResultado.Falha,
                -> {
                    // nenhum delta foi removido; backoff exponencial cap ≤ MAX_TENTATIVAS
                    if (tentativas < MAX_TENTATIVAS) delay(BACKOFF_BASE_MS * (1L shl (tentativas - 1)))
                }
                CicloResultado.SucessoComAviso -> {
                    // schema maior: não sobrescreve; aviso discreto persistido; sync pausa nesse arquivo
                    vinculado.set(true)
                    _syncState.value = SyncState.SincronizacaoEmPausa(
                        "Arquivo no Drive mais novo que o app — sync pausado neste arquivo"
                    )
                    return true
                }
            }
        }
        // backoff esgotado: deltas preservados para o próximo gatilho; o estado
        // nunca permanece "Sincronizando" (spinner congelado) se tudo falhou.
        _syncState.value = if (vinculado.get()) SyncState.Vinculado else SyncState.Desvinculado
        return vinculado.get()
    }

    private suspend fun lerCanonico(): LeituraCanonico {
        val lista = when (val r = drive.listarArquivoCanonico()) {
            is DriveResultado.NaoAutorizado -> return LeituraCanonico.NaoAutorizado
            is DriveResultado.Falha -> return LeituraCanonico.Falha
            is DriveResultado.Sucesso -> r.valor
        }

        val arquivoExistente = lista != null
        if (!arquivoExistente) {
            // canônico ausente (AD-SYNC-4: ausente = vazio)
            return LeituraCanonico.Ok(BoxpaceArquivo(), arquivo = null)
        }

        val leitura = when (val r = drive.ler(lista)) {
            is DriveResultado.NaoAutorizado -> return LeituraCanonico.NaoAutorizado
            is DriveResultado.Falha -> return LeituraCanonico.Falha
            is DriveResultado.Sucesso -> r.valor
        }
        val decodificado = try {
            SchemaBoxpace.decodificar(leitura.conteudo)
        } catch (_: Exception) {
            // arquivo ilegível: não sobrescreve às cegas; falha discreta
            return LeituraCanonico.Falha
        }
        if (decodificado.schemaVersion > SchemaBoxpace.SCHEMA_VERSION) {
            // SCHEMA_MAIOR: não sobrescreve; aviso persistido; sync/restore pausa
            return LeituraCanonico.SchemaMaior
        }
        // SCHEMA_MENOR: migra em memória antes de qualquer merge/LWW (a recusa
        // de versão maior acima permanece intacta)
        return LeituraCanonico.Ok(SchemaBoxpace.migrarParaSchemaAtual(decodificado), arquivo = lista)
    }

    private suspend fun cicloTentativa(motivo: Motivo, leitura: LeituraCanonico): CicloResultado {
        val token = tokenProvider.atual() ?: return CicloResultado.NaoAutorizado

        // 1) read: usa a leitura canônica (da decisão da 1ª tentativa ou da retentativa)
        val ok = when (leitura) {
            is LeituraCanonico.NaoAutorizado -> return CicloResultado.NaoAutorizado
            is LeituraCanonico.Falha -> return CicloResultado.Falha
            is LeituraCanonico.SchemaMaior -> return CicloResultado.SucessoComAviso
            is LeituraCanonico.Ok -> leitura
        }

        // 2) merge LWW: base + deltas pendentes (promoção no primeiro vínculo)
        val deltas = encomendaRepository.listarDeltasPendentes()
        val isPromocao = ok.arquivo == null && (motivo == Motivo.PRIMEIRO_VINCULO || vinculado.get())
        val candidato = if (isPromocao) {
            // promoção: canônico ∅ → importa o cache local + drena deltas (LWW; tombstones dos Excluir)
            val local = MergeRegistros.mergeArquivos(
                MergeRegistros.promover(encomendaRepository.listar()),
                MergeRegistros.aplicarDeltas(BoxpaceArquivo(), deltas),
            )
            local
        } else {
            MergeRegistros.aplicarDeltas(ok.canonico, deltas)
        }

        // 3) write: arquivo presente → atualiza com a metadata da leitura; ausente → cria
        val conteudo = SchemaBoxpace.codificar(candidato)
        if (ok.arquivo != null) {
            when (val r = drive.atualizar(ok.arquivo, conteudo)) {
                is DriveResultado.NaoAutorizado -> return CicloResultado.NaoAutorizado
                is DriveResultado.Falha -> return CicloResultado.Falha
                is DriveResultado.Sucesso -> Unit
            }
        } else {
            when (val r = drive.criar(conteudo)) {
                is DriveResultado.NaoAutorizado -> return CicloResultado.NaoAutorizado
                is DriveResultado.Falha -> return CicloResultado.Falha
                is DriveResultado.Sucesso -> Unit
            }
        }

        // 4) reconcile read-back: re-lê e confirma que o conteúdo batE (AD-SYNC-9)
        val relido = when (val r = drive.listarArquivoCanonico()) {
            is DriveResultado.NaoAutorizado -> return CicloResultado.NaoAutorizado
            is DriveResultado.Falha -> return CicloResultado.Falha
            is DriveResultado.Sucesso -> r.valor
        } ?: return CicloResultado.ESCRITA_VENCIDA
        val leituraConfirm = when (val r = drive.ler(relido)) {
            is DriveResultado.NaoAutorizado -> return CicloResultado.NaoAutorizado
            is DriveResultado.Falha -> return CicloResultado.Falha
            is DriveResultado.Sucesso -> r.valor
        }
        if (leituraConfirm.conteudo != conteudo) {
            // versão mudou entre get e update: NENHUM delta do lote é removido
            return CicloResultado.ESCRITA_VENCIDA
        }

        // sucesso confirmado: remove os deltas processados e espelha o canônico no Room
        encomendaRepository.limparDeltasPendentes()
        reconciliarRoom(candidato)
        return CicloResultado.Sucesso
    }

    private suspend fun reconciliarRoom(canonico: BoxpaceArquivo) {
        // ids com delta pendente (chegados durante o ciclo) não sofrem espelhação
        val protegidos = encomendaRepository.listarDeltasPendentes()
            .filterIsInstance<DeltaPendente.Salvar>()
            .map { it.alvoId }
            .toSet()

        val vivos = canonico.encomendas
            .filterNot { it.tombstone }
            .mapNotNull { runCatching { SchemaBoxpace.registroParaEncomenda(it) }.getOrNull() }

        vivos.forEach { encomenda ->
            runCatching { encomendaRepository.salvar(encomenda) }
        }

        // espelha o canônico (AD-SYNC-9): remove fantasmas locais ausentes do
        // canônico (ex.: tombstone vindo da exclusão feita em outro aparelho).
        val idsCanonico = vivos.map { it.id }.toSet()
        encomendaRepository.listar()
            .filter { it.id !in idsCanonico && it.id !in protegidos }
            .forEach { fantasma ->
                runCatching { encomendaRepository.excluir(fantasma.id, Instant.now().toString()) }
            }
    }

    private suspend fun despejarDeltas(): Boolean {
        val resultado = cicloTentativa(Motivo.DESVINCULAR, lerCanonico())
        return resultado == CicloResultado.Sucesso || resultado == CicloResultado.SucessoComAviso
    }

    private enum class Motivo { PRIMEIRO_VINCULO, DELTAS, RECONECTAR, DESVINCULAR }

    /** Resultado da leitura do canônico — compartilhado entre sync e restore. */
    private sealed interface LeituraCanonico {
        /** [arquivo] não-nulo quando o boxpace.json existe (arquivo ⨯ metadata p/ escrita). */
        data class Ok(val canonico: BoxpaceArquivo, val arquivo: ArquivoDriveMetadata?) : LeituraCanonico
        data object NaoAutorizado : LeituraCanonico
        data object Falha : LeituraCanonico
        data object SchemaMaior : LeituraCanonico
    }

    private enum class CicloResultado {
        Sucesso,
        SucessoComAviso,
        NaoAutorizado,
        ESCRITA_VENCIDA,
        Falha,
    }

    private companion object {
        const val MAX_TENTATIVAS = 5
        const val BACKOFF_BASE_MS = 100L
        const val MAX_RODADAS_DRENAGEM = 16
    }
}
