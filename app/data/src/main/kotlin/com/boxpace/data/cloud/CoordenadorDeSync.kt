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
        return mutex.withLock {
            val ok = ciclo(Motivo.PRIMEIRO_VINCULO)
            ok
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

    private suspend fun ciclo(motivo: Motivo): Boolean {
        _syncState.value = SyncState.Sincronizando
        var tentativas = 0
        while (tentativas < MAX_TENTATIVAS) {
            tentativas++
            when (val resultado = cicloTentativa(motivo)) {
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

    private suspend fun cicloTentativa(motivo: Motivo): CicloResultado {
        val token = tokenProvider.atual() ?: return CicloResultado.NaoAutorizado

        // 1) read: localiza e lê o canônico
        val lista = when (val r = drive.listarArquivoCanonico()) {
            is DriveResultado.NaoAutorizado -> return CicloResultado.NaoAutorizado
            is DriveResultado.Falha -> return CicloResultado.Falha
            is DriveResultado.Sucesso -> r.valor
        }

        val arquivoExistente = lista != null
        val canonicoLido: BoxpaceArquivo = if (arquivoExistente) {
            val leitura = when (val r = drive.ler(lista)) {
                is DriveResultado.NaoAutorizado -> return CicloResultado.NaoAutorizado
                is DriveResultado.Falha -> return CicloResultado.Falha
                is DriveResultado.Sucesso -> r.valor
            }
            val decodificado = try {
                SchemaBoxpace.decodificar(leitura.conteudo)
            } catch (_: Exception) {
                // arquivo ilegível: não sobrescreve às cegas; falha discreta
                return CicloResultado.Falha
            }
            if (decodificado.schemaVersion > SchemaBoxpace.SCHEMA_VERSION) {
                // SCHEMA_MAIOR: não sobrescreve; aviso persistido; sync pausa
                return CicloResultado.SucessoComAviso
            }
            // SCHEMA_MENOR: migra em memória antes de qualquer merge/LWW (a
            // recusa de versão maior acima permanece intacta)
            SchemaBoxpace.migrarParaSchemaAtual(decodificado)
        } else {
            // canônico ausente (AD-SYNC-4: ausente = vazio)
            BoxpaceArquivo()
        }

        // 2) merge LWW: base + deltas pendentes (promoção no primeiro vínculo)
        val deltas = encomendaRepository.listarDeltasPendentes()
        val isPromocao = !arquivoExistente && (motivo == Motivo.PRIMEIRO_VINCULO || vinculado.get())
        val candidato = if (isPromocao) {
            // promoção: canônico ∅ → importa o cache local + drena deltas (LWW; tombstones dos Excluir)
            val local = MergeRegistros.mergeArquivos(
                MergeRegistros.promover(encomendaRepository.listar()),
                MergeRegistros.aplicarDeltas(BoxpaceArquivo(), deltas),
            )
            local
        } else {
            MergeRegistros.aplicarDeltas(canonicoLido, deltas)
        }

        // 3) write
        val conteudo = SchemaBoxpace.codificar(candidato)
        if (arquivoExistente) {
            when (val r = drive.atualizar(lista, conteudo)) {
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
        val resultado = cicloTentativa(Motivo.DESVINCULAR)
        return resultado == CicloResultado.Sucesso || resultado == CicloResultado.SucessoComAviso
    }

    private enum class Motivo { PRIMEIRO_VINCULO, DELTAS, RECONECTAR, DESVINCULAR }

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
