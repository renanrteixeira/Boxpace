package com.boxpace.data.cloud

import com.boxpace.domain.DeltaPendente
import com.boxpace.domain.Encomenda
import com.boxpace.domain.EncomendaRepository
import com.boxpace.domain.Evento
import com.boxpace.domain.Preferencias
import com.boxpace.domain.PreferenciasRepository
import com.boxpace.domain.SyncState
import com.boxpace.domain.Tema
import com.boxpace.domain.Transportadora
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Testes do [CoordenadorDeSync] com Ktor MockEngine — cobrem as linhas do
 * matrix I/O: promoção do 1º vínculo, dreno em lote, LWW, 401 (Sincronização
 * perdida), version mismatch sem limpar deltas + backoff, tombstone de exclusão.
 */
class CoordenadorDeSyncTest {

    private class RepositorioFake : EncomendaRepository {
        val room = MutableStateFlow<List<Encomenda>>(emptyList())
        val deltas = mutableListOf<DeltaPendente>()
        var falharEmLimpar = false

        /** Quando setado, lança em `listarDeltasPendentes` — simula falha dentro do restore. */
        var falharDuranteRestore = false

        /** Quando setado, `limparDeltasPendentes` re-insere um delta na fila (uma vez) — simula mutação mid-cycle. */
        var reinjetarDelta: (() -> DeltaPendente)? = null

        override fun observar(): Flow<List<Encomenda>> = room
        override suspend fun salvar(encomenda: Encomenda) {
            room.value = listOf(encomenda) + room.value.filterNot { it.id == encomenda.id }
        }
        override suspend fun salvarComDelta(encomenda: Encomenda): Boolean {
            salvar(encomenda)
            deltas += DeltaPendente.Salvar(encomenda, encomenda.id, encomenda.atualizadaEm)
            return true
        }
        override suspend fun buscarPorId(id: String): Encomenda? = room.value.firstOrNull { it.id == id }
        override suspend fun buscarPorCodigo(codigo: String, transportadora: Transportadora): Encomenda? = null
        override suspend fun listar(): List<Encomenda> = room.value
        override suspend fun listarAtivas(): List<Encomenda> = room.value.filter { it.fechadaEm == null }
        override suspend fun listarFechadas(): List<Encomenda> = room.value.filter { it.fechadaEm != null }
        override suspend fun excluir(id: String, criadoEm: String) {
            room.value = room.value.filterNot { it.id == id }
        }
        override suspend fun registrarDeltaPendente(delta: DeltaPendente) { deltas += delta }
        override suspend fun listarDeltasPendentes(): List<DeltaPendente> {
            if (falharDuranteRestore) throw RuntimeException("falha durante o restore")
            return deltas.toList()
        }
        override suspend fun limparDeltasPendentes() {
            if (falharEmLimpar) throw RuntimeException("limpar falhou")
            deltas.clear()
            val delta = reinjetarDelta
            if (delta != null) {
                reinjetarDelta = null
                deltas += delta()
            }
        }
        override suspend fun purgarFechadasAntigas(dias: Int) {}
    }

    private class PrefsFake : PreferenciasRepository {
        override suspend fun carregar(): Preferencias =
            Preferencias(tema = Tema.SISTEMA, updatedAt = "2026-09-01T00:00:00Z")
        override suspend fun salvar(preferencias: Preferencias) {}
    }

    /** Simula o App Data Folder: guarda conteúdo/revisão e responde REST. */
    private class DriveSimulado(initial: String? = null) {
        var conteudo: String? = initial
        var revisao: Int = 0

        fun engine(): MockEngine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Get -> {
                    when {
                        request.url.parameters.contains("alt") ->
                            conteudo?.let { respondJson(it) }
                                ?: respondJson("", HttpStatusCode.NotFound)
                        request.url.parameters.contains("q") -> {
                            // listagem (spaces + q + fields=files(...))
                            val files = if (conteudo == null) "[]"
                            else """[{"id":"FILE_ID","name":"${SchemaBoxpace.NOME_ARQUIVO}","headRevisionId":"rev-$revisao"}]"""
                            respondJson("""{"files":$files}""")
                        }
                        else -> respondJson("""{"headRevisionId":"rev-$revisao"}""") // metadata (fields=headRevisionId)
                    }
                }
                HttpMethod.Post -> {
                    revisao++
                    conteudo = capturarCorpoMultipart(request)
                    respondJson("""{"id":"FILE_ID","name":"${SchemaBoxpace.NOME_ARQUIVO}"}""", HttpStatusCode.OK)
                }
                HttpMethod.Patch -> {
                    revisao++
                    conteudo = request.body.toByteArray().decodeToString()
                    respondJson("""{"headRevisionId":"rev-$revisao"}""")
                }
                else -> respondJson("{}", HttpStatusCode.MethodNotAllowed)
            }
        }
    }

    private fun encomenda(
        id: String = "correios:AA123456789BR",
        codigo: String = "AA123456789BR",
        transportadora: Transportadora = Transportadora.CORREIOS,
        etiqueta: String = "Fone",
        atualizadaEm: String = "2026-09-01T12:00:00Z",
    ) = Encomenda(
        id = id,
        codigo = codigo,
        transportadora = transportadora,
        etiqueta = etiqueta,
        ultimoStatus = "Objeto postado",
        eventos = listOf(Evento("2026-09-01T10:00:00", "Objeto postado")),
        criadaEm = "2026-09-01T10:00:00Z",
        atualizadaEm = atualizadaEm,
        fechadaEm = null,
    )

    private fun coordenador(
        drive: DriveSimulado,
        repo: RepositorioFake,
        token: TokenOAuthProvider,
    ): CoordenadorDeSync {
        val client = HttpClient(drive.engine())
        val driveCliente = DriveCliente(client, token)
        return CoordenadorDeSync(
            encomendaRepository = repo,
            preferenciasRepository = PrefsFake(),
            drive = driveCliente,
            tokenProvider = token,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    // --- HAPPY_PATH_VINCULAR_NOVO: promoção do cache (canônico ausente) ---

    @Test
    fun `primeiro vinculo promove cache local e fica Vinculado`() = runTest {
        val drive = DriveSimulado(initial = null)
        val repo = RepositorioFake().apply {
            room.value = listOf(encomenda())
        }
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = coordenador(drive, repo, token)

        val ok = coord.vincular()

        assertTrue(ok)
        assertEquals(SyncState.Vinculado, coord.syncState.value)
        assertTrue(drive.conteudo != null)
        // canônico promovido contém a encomenda
        val arquivo = SchemaBoxpace.decodificar(drive.conteudo!!)
        assertTrue(arquivo.encomendas.any { it.codigo == "AA123456789BR" })
    }

    // --- HAPPY_PATH_SYNC_MUTACAO: drena Salvar em lote e limpa a fila ---

    @Test
    fun `sync drena delta e remove da fila apos sucesso confirmado`() = runTest {
        val canonoVazio = SchemaBoxpace.codificar(BoxpaceArquivo())
        val drive = DriveSimulado(initial = canonoVazio)
        val repo = RepositorioFake()
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = CoordenadorDeSync(
            encomendaRepository = repo,
            preferenciasRepository = PrefsFake(),
            drive = DriveCliente(HttpClient(drive.engine()), token),
            tokenProvider = token,
            scope = this,
        )

        // primeiro vínculo com canônico existente vira restore pull-only (Restaurando→Vinculado)
        assertTrue(coord.vincular())
        assertEquals(SyncState.Vinculado, coord.syncState.value)

        // mutação local após o vínculo: quem drena a fila é o sync, não o restore
        repo.deltas += DeltaPendente.Salvar(
            encomenda = encomenda(),
            alvoId = "correios:AA123456789BR",
            criadoEm = "2026-09-01T12:00:00Z",
        )
        coord.dispararSync()
        aguardarDreno { repo.deltas.isEmpty() && drive.conteudo?.contains("AA123456789BR") == true }

        assertTrue(repo.deltas.isEmpty(), "delta deve ser removido após sucesso")
        assertEquals(SyncState.Vinculado, coord.syncState.value)
    }

    // --- tombstone (Excluir) gravado no canônico e identidade não recriada ---

    @Test
    fun `exclusao pendente grava tombstone no canonico`() = runTest {
        val canonoComVivo = SchemaBoxpace.codificar(
            BoxpaceArquivo(encomendas = listOf(SchemaBoxpace.encomendaParaRegistro(encomenda()))),
        )
        val drive = DriveSimulado(initial = canonoComVivo)
        val repo = RepositorioFake()
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = CoordenadorDeSync(
            encomendaRepository = repo,
            preferenciasRepository = PrefsFake(),
            drive = DriveCliente(HttpClient(drive.engine()), token),
            tokenProvider = token,
            scope = this,
        )

        // primeiro vínculo com canônico presente é restore pull-only; só o sync escreve
        assertTrue(coord.vincular())

        repo.deltas += DeltaPendente.Excluir(alvoId = "correios:AA123456789BR", criadoEm = "2026-09-01T13:00:00Z")
        coord.dispararSync()
        aguardarDreno { repo.deltas.isEmpty() }

        val arquivo = SchemaBoxpace.decodificar(drive.conteudo!!)
        assertTrue(arquivo.encomendas.single().tombstone, "espera tombstone em vez de copia viva")
        assertTrue(repo.deltas.isEmpty())
    }

    // --- RECONEXAO_COM_DELTAS: drena após reconectar ---

    @Test
    fun `reconectar drena deltas acumulados`() = runTest {
        val drive = DriveSimulado(initial = SchemaBoxpace.codificar(BoxpaceArquivo()))
        val repo = RepositorioFake().apply {
            deltas += DeltaPendente.SalvarPreferencia(
                preferencias = Preferencias(tema = Tema.ESCURO, updatedAt = "2026-09-01T12:00:00Z"),
                alvoId = "preferencias:tema",
                criadoEm = "2026-09-01T12:00:00Z",
            )
        }
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = coordenador(drive, repo, token)

        assertTrue(coord.reconectar())
        val arquivo = SchemaBoxpace.decodificar(drive.conteudo!!)
        assertTrue(arquivo.preferencias.any { it.chave == "preferencias:tema" && it.valor == "escuro" })
        assertTrue(repo.deltas.isEmpty())
    }

    // --- 401: sina SincronizacaoPerdida; dados/deltas intactos ---

    @Test
    fun `401 leva a SincronizacaoPerdida e preserva deltas`() = runTest {
        val engine = MockEngine { respond(
            content = """{"error":"unauthorized"}""",
            status = HttpStatusCode.Unauthorized,
        ) }
        val client = HttpClient(engine)
        val repo = RepositorioFake().apply {
            deltas += DeltaPendente.Salvar(encomenda(), "correios:AA123456789BR", "2026-09-01T12:00:00Z")
        }
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = CoordenadorDeSync(
            encomendaRepository = repo,
            preferenciasRepository = PrefsFake(),
            drive = DriveCliente(client, token),
            tokenProvider = token,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertFalse(coord.vincular())
        assertEquals(SyncState.SincronizacaoPerdida, coord.syncState.value)
        assertEquals(1, repo.deltas.size)
    }

    // --- ESCRITA_VENCIDA: read-back difere → NENHUM delta removido + backoff ---

    @Test
    fun `version mismatch nao remove deltas`() = runTest {
        // Simula falsificação na promoção: o Drive aceita (cria) mas o read-back
        // nunca confirma o conteúdo gravado (listagem sempre vazia).
        var gravados = 0
        val engine = MockEngine { request ->
            val isMedia = request.url.parameters.contains("alt")
            if (request.method == HttpMethod.Get && isMedia) {
                respondJson("""{"schemaVersion":1,"encomendas":[],"preferencias":[]}""")
            } else if (request.method == HttpMethod.Get) {
                // listagem sempre vazia → a retentativa volta a promover (re-escrita)
                respondJson("""{"files":[]}""")
            } else {
                gravados++
                respondJson("""{"id":"FILE_ID"}""")
            }
        }
        val client = HttpClient(engine)
        val repo = RepositorioFake().apply {
            deltas += DeltaPendente.Salvar(encomenda(), "correios:AA123456789BR", "2026-09-01T12:00:00Z")
        }
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = CoordenadorDeSync(
            encomendaRepository = repo,
            preferenciasRepository = PrefsFake(),
            drive = DriveCliente(client, token),
            tokenProvider = token,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        // backoff com tempo real em runTest pode estourar; usamos retorno sem asserção de sucesso.
        coord.vincular()
        assertTrue(repo.deltas.isNotEmpty(), "deltas preservados enquanto version nao confere")
        assertTrue(gravados >= 2, "backoff deve re-tentar a escrita (gravados=$gravados)")
        assertTrue(
            coord.syncState.value != SyncState.Sincronizando,
            "estado nunca deve ficar travado em Sincronizando após backoff esgotado",
        )
    }

    // --- BH3: mutação chegando durante o ciclo é re-drenada (nunca descartada) ---

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `delta chegando durante o ciclo e re-drenado sem perder dados`() = runTest {
        val drive = DriveSimulado(initial = SchemaBoxpace.codificar(BoxpaceArquivo()))
        val repo = RepositorioFake()
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = CoordenadorDeSync(
            encomendaRepository = repo,
            preferenciasRepository = PrefsFake(),
            drive = DriveCliente(HttpClient(drive.engine()), token),
            tokenProvider = token,
            scope = this,
        )

        assertTrue(coord.vincular())

        // mutação "chega" no instante em que o ciclo limpa a fila
        repo.reinjetarDelta = {
            DeltaPendente.Salvar(
                encomenda = encomenda(atualizadaEm = "2026-09-01T13:00:00Z"),
                alvoId = "correios:AA123456789BR",
                criadoEm = "2026-09-01T13:00:00Z",
            )
        }

        coord.dispararSync()
        // o MockEngine despacha em segundo plano: aguarda o dreno terminar
        var drenou = false
        repeat(100) {
            testScheduler.advanceUntilIdle()
            runCurrent()
            if (repo.deltas.isEmpty() && drive.conteudo?.contains("AA123456789BR") == true) {
                drenou = true
                return@repeat
            }
            delay(10)
        }
        assertTrue(drenou, "dreno deve terminar com a fila vazia")

        // o dreno roda de novo até esvaziar: o delta mid-cycle jamais é perdido
        assertTrue(repo.deltas.isEmpty(), "delta mid-cycle não pode ser descartado")
        val arquivo = SchemaBoxpace.decodificar(drive.conteudo!!)
        assertEquals("2026-09-01T13:00:00Z", arquivo.encomendas.single().updatedAt)
        assertEquals(SyncState.Vinculado, coord.syncState.value)
    }

    // --- BH10: tombstone do canônico é espelhado — fantasma local removido (AD-SYNC-9) ---

    @Test
    fun `tombstone do canonico remove fantasma local do Room`() = runTest {
        val canonicoComTombstone = SchemaBoxpace.codificar(
            BoxpaceArquivo(
                encomendas = listOf(
                    SchemaBoxpace.tombstoneParaRegistro(
                        codigo = "AA123456789BR",
                        transportadora = Transportadora.CORREIOS,
                        updatedAt = "2026-09-01T12:00:00Z",
                    ),
                ),
            ),
        )
        val drive = DriveSimulado(initial = canonicoComTombstone)
        val repo = RepositorioFake().apply {
            // fantasma: registro local que JÁ foi excluído em outro aparelho
            room.value = listOf(encomenda())
        }
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = coordenador(drive, repo, token)

        assertTrue(coord.vincular())

        assertTrue(repo.room.value.isEmpty(), "Room deve espelhar o canônico — sem fantasmas")
    }

    // --- SCHEMA_MENOR: leitura de v1 migra para v2 na gravação (etiqueta + transportadora) ---

    @Test
    fun `leitura de canonico v1 grava v2 migrado`() = runTest {
        val v1 = """
            {
              "schemaVersion": 1,
              "encomendas": [
                {"codigo":"AA123456789BR","transportadora":"correios",
                 "updatedAt":"2026-09-01T12:00:00Z","ultimoStatus":"Objeto postado"}
              ],
              "preferencias": []
            }
        """.trimIndent()
        val drive = DriveSimulado(initial = v1)
        val repo = RepositorioFake()
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = CoordenadorDeSync(
            encomendaRepository = repo,
            preferenciasRepository = PrefsFake(),
            drive = DriveCliente(HttpClient(drive.engine()), token),
            tokenProvider = token,
            scope = this,
        )

        // vínculo com canônico v1 é restore pull-only: migra em memória e NÃO grava
        assertTrue(coord.vincular())
        assertEquals(0, drive.revisao)
        assertEquals(1, repo.room.value.size)

        // o sync posterior lê v1, migra e grava como v2
        coord.dispararSync()
        aguardarDreno { drive.revisao >= 1 }

        val arquivo = SchemaBoxpace.decodificar(drive.conteudo!!)
        assertEquals(2, arquivo.schemaVersion)
        val registro = arquivo.encomendas.single()
        assertFalse(registro.tombstone)
        assertEquals("CORREIOS", registro.transportadora)
        assertEquals("AA123456789BR", registro.etiqueta)
    }

    // --- E2E v1: canônico legado + deltas lowercase → v2 com identidade única, LWW e sem órfãos ---

    @Test
    fun `canonico v1 com deltas lowercase grava v2 com identidade unica e sem deltas orfaos`() = runTest {
        val v1 = """
            {
              "schemaVersion": 1,
              "encomendas": [
                {"codigo":"AA123456789BR","transportadora":"correios",
                 "updatedAt":"2026-09-01T10:00:00Z","ultimoStatus":"postado"},
                {"codigo":"888123456789","transportadora":"jt",
                 "updatedAt":"2026-09-01T10:00:00Z","ultimoStatus":"postado"}
              ],
              "preferencias": []
            }
        """.trimIndent()
        val drive = DriveSimulado(initial = v1)
        val repo = RepositorioFake()
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = CoordenadorDeSync(
            encomendaRepository = repo,
            preferenciasRepository = PrefsFake(),
            drive = DriveCliente(HttpClient(drive.engine()), token),
            tokenProvider = token,
            scope = this,
        )

        // vínculo com canônico presente é restore pull-only (não grava, não limpa deltas)
        assertTrue(coord.vincular())
        assertEquals(0, drive.revisao)

        // Salvar mais recente que o canônico: deve vencer o LWW do correios no sync
        repo.deltas += DeltaPendente.Salvar(
            encomenda = encomenda(
                id = "correios:AA123456789BR",
                codigo = "AA123456789BR",
                transportadora = Transportadora.CORREIOS,
                etiqueta = "Fone novo",
                atualizadaEm = "2026-09-01T12:00:00Z",
            ),
            alvoId = "correios:AA123456789BR",
            criadoEm = "2026-09-01T12:00:00Z",
        )
        // Excluir mais recente que o canônico: deve virar tombstone do jt no sync
        repo.deltas += DeltaPendente.Excluir(
            alvoId = "jt:888123456789",
            criadoEm = "2026-09-01T12:00:00Z",
        )
        coord.dispararSync()
        aguardarDreno { repo.deltas.isEmpty() }

        val arquivo = SchemaBoxpace.decodificar(drive.conteudo!!)
        assertEquals(2, arquivo.schemaVersion)

        // identidade única por registro — sem duplicatas por divergência de case
        val identidades = arquivo.encomendas.map { "${it.transportadora}:${it.codigo}" }
        assertEquals(2, identidades.size)
        assertEquals(2, identidades.distinct().size, "cada encomenda tem uma única identidade CORREIOS|JT:código")

        // LWW: delta Salvar mais recente venceu, etiqueta e updatedAt são do delta
        val vivo = arquivo.encomendas.single { !it.tombstone }
        assertEquals("CORREIOS", vivo.transportadora)
        assertEquals("AA123456789BR", vivo.codigo)
        assertEquals("Fone novo", vivo.etiqueta)
        assertEquals("2026-09-01T12:00:00Z", vivo.updatedAt)

        // LWW: delta Excluir mais recente virou tombstone JT
        val tombstone = arquivo.encomendas.single { it.tombstone }
        assertEquals("JT", tombstone.transportadora)
        assertEquals("888123456789", tombstone.codigo)
        assertEquals("2026-09-01T12:00:00Z", tombstone.updatedAt)

        assertTrue(repo.deltas.isEmpty(), "deltas processados não podem ficar órfãos")
    }

    // --- SCHEMA_MAIOR: arquivo com schema mais novo não é sobrescrito; aviso persistido; sync pausa ---

    @Test
    fun `schema maior nao sobrescreve e sinaliza pausa com aviso`() = runTest {
        val sinicial = """{"schemaVersion":3,"encomendas":[],"preferencias":[]}"""
        val drive = DriveSimulado(initial = sinicial)
        val repo = RepositorioFake().apply {
            deltas += DeltaPendente.Salvar(encomenda(), "correios:AA123456789BR", "2026-09-01T12:00:00Z")
        }
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = coordenador(drive, repo, token)

        val ok = coord.vincular()

        assertTrue(ok)
        // aviso discreto persistido no estado
        val estado = coord.syncState.value
        assertTrue(estado is SyncState.SincronizacaoEmPausa, "espera SincronizacaoEmPausa, veio $estado")
        val aviso = estado as SyncState.SincronizacaoEmPausa
        assertTrue(aviso.motivo.isNotBlank(), "motivo do aviso não pode ser vazio")
        // não sobrescreve: nenhuma escrita (revisão intacta, conteúdo original preservado)
        assertEquals(0, drive.revisao)
        assertEquals(sinicial, drive.conteudo)
        // sync pausa nesse arquivo: deltas não descarregados nem removidos
        assertEquals(1, repo.deltas.size)
    }

    // --- HAPPY_RESTORE: pull-only, Ativos+Fechados espelhados, NENHUMA escrita ---

    @Test
    fun `restaurar e pull-only e espelha o Room sem gravar no Drive`() = runTest {
        val ativo = encomenda(id = "correios:AA123456789BR", codigo = "AA123456789BR")
        val fechada = encomenda(
            id = "correios:BB000000000BR",
            codigo = "BB000000000BR",
            etiqueta = "Fechado",
            atualizadaEm = "2026-09-01T11:00:00Z",
        ).copy(fechadaEm = "2026-09-01T11:00:00Z", atualizadaEm = "2026-09-01T11:00:00Z")
        val canonico = SchemaBoxpace.codificar(
            BoxpaceArquivo(
                encomendas = listOf(
                    SchemaBoxpace.encomendaParaRegistro(ativo),
                    SchemaBoxpace.encomendaParaRegistro(fechada),
                ),
            ),
        )
        val drive = DriveSimulado(initial = canonico)
        val repo = RepositorioFake()
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = coordenador(drive, repo, token)

        assertTrue(coord.restaurar())

        assertEquals(SyncState.Vinculado, coord.syncState.value)
        val ids = repo.room.value.map { it.id }
        assertTrue("correios:AA123456789BR" in ids, "Ativos devem ser espelhados")
        assertTrue("correios:BB000000000BR" in ids, "Fechados devem ser espelhados")
        // NENHUMA escrita no Drive; deltas continuam vazios
        assertEquals(0, drive.revisao)
        assertEquals(canonico, drive.conteudo)
        assertTrue(repo.deltas.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `restaurar transita por Restaurando e termina Vinculado`() = runTest {
        val canonico = SchemaBoxpace.codificar(
            BoxpaceArquivo(encomendas = listOf(SchemaBoxpace.encomendaParaRegistro(encomenda()))),
        )
        val drive = DriveSimulado(initial = canonico)
        val repo = RepositorioFake()
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = coordenador(drive, repo, token)
        val estados = mutableListOf<SyncState>()
        val coletor = launch(UnconfinedTestDispatcher(testScheduler)) {
            coord.syncState.collect { estados += it }
        }

        assertTrue(coord.restaurar())
        coletor.cancel()

        assertTrue(SyncState.Restaurando in estados, "estado Restaurando deve ser observável ($estados)")
        assertEquals(SyncState.Vinculado, estados.last())
    }

    // --- HAPPY_RESTORE_DELTA: delta mais novo vence o LWW no Room e NÃO é limpo pelo restore ---

    @Test
    fun `restaurar aplica LWW no Room e o auto-sync drena o delta ao Drive`() = runTest {
        val canonico = SchemaBoxpace.codificar(
            BoxpaceArquivo(
                encomendas = listOf(
                    SchemaBoxpace.encomendaParaRegistro(encomenda(atualizadaEm = "2026-09-01T10:00:00Z")),
                ),
            ),
        )
        val drive = DriveSimulado(initial = canonico)
        val repo = RepositorioFake().apply {
            deltas += DeltaPendente.Salvar(
                encomenda = encomenda(etiqueta = "Fone novo", atualizadaEm = "2026-09-01T12:00:00Z"),
                alvoId = "correios:AA123456789BR",
                criadoEm = "2026-09-01T12:00:00Z",
            )
        }
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = CoordenadorDeSync(
            encomendaRepository = repo,
            preferenciasRepository = PrefsFake(),
            drive = DriveCliente(HttpClient(drive.engine()), token),
            tokenProvider = token,
            scope = this,
        )

        assertTrue(coord.restaurar())

        // o restore é pull-only e imediato: LWW no Room, deltas intactos, NENHUMA escrita
        val refletida = repo.room.value.single { it.id == "correios:AA123456789BR" }
        assertEquals("Fone novo", refletida.etiqueta, "delta mais novo deve vencer o LWW")
        assertEquals("2026-09-01T12:00:00Z", refletida.atualizadaEm)
        assertEquals(1, repo.deltas.size, "restore nunca consome deltas (AD-SYNC-9)")
        assertEquals(0, drive.revisao, "E nenhuma escrita vem do restore em si")

        // auto-sync pós-restore leva o delta ao canônico do Drive sem dispararSync manual
        aguardarDreno { repo.deltas.isEmpty() && drive.revisao == 1 }

        val arquivo = SchemaBoxpace.decodificar(drive.conteudo!!)
        assertEquals("Fone novo", arquivo.encomendas.single().etiqueta)
        assertEquals("2026-09-01T12:00:00Z", arquivo.encomendas.single().updatedAt)
        assertEquals(SyncState.Vinculado, coord.syncState.value)
    }

    // --- ERROR_SEM_REDE: local intacto, sem spinner congelado ---

    @Test
    fun `restaurar sem rede deixa local intacto e volta a Desvinculado`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
        val client = HttpClient(engine)
        val repo = RepositorioFake().apply { room.value = listOf(encomenda()) }
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = CoordenadorDeSync(
            encomendaRepository = repo,
            preferenciasRepository = PrefsFake(),
            drive = DriveCliente(client, token),
            tokenProvider = token,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertFalse(coord.restaurar())

        assertEquals(SyncState.Desvinculado, coord.syncState.value)
        assertEquals(1, repo.room.value.size, "local intacto na falha de rede")
    }

    // --- ERROR_SEM_VINCULO: sem token, restaurar é recusado sem ciclo ---

    @Test
    fun `restaurar sem vinculo retorna false e nao dispara ciclo`() = runTest {
        val drive = DriveSimulado()
        val repo = RepositorioFake()
        val token = TokenOAuthProvider()
        val coord = coordenador(drive, repo, token)

        assertFalse(coord.restaurar())

        assertEquals(SyncState.Desvinculado, coord.syncState.value)
        assertEquals(0, drive.revisao, "sem token não há nem listagem nem escrita")
    }

    // --- EDGE_CANONICO_AUSENTE: nada a restaurar — local intocado, Vinculado ---

    @Test
    fun `restaurar sem canonico fica Vinculado e deixa o Room intocado`() = runTest {
        val drive = DriveSimulado(initial = null)
        val repo = RepositorioFake().apply { room.value = listOf(encomenda()) }
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = coordenador(drive, repo, token)

        assertTrue(coord.restaurar())

        assertEquals(SyncState.Vinculado, coord.syncState.value)
        assertEquals(1, repo.room.value.size, "canônico ausente não apaga o local")
        assertEquals(0, drive.revisao)
        assertTrue(drive.conteudo == null, "nada é gravado no Drive")
    }

    // --- EDGE_SCHEMA_MAIOR: restore é pulado; nada espelhado nem escrito ---

    @Test
    fun `restaurar com schema maior nao espelha nada e sinaliza pausa`() = runTest {
        val sinicial = """{"schemaVersion":3,"encomendas":[],"preferencias":[]}"""
        val drive = DriveSimulado(initial = sinicial)
        val repo = RepositorioFake().apply { room.value = listOf(encomenda()) }
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = coordenador(drive, repo, token)

        val ok = coord.restaurar()

        assertTrue(ok)
        val estado = coord.syncState.value
        assertTrue(estado is SyncState.SincronizacaoEmPausa, "espera pausa, veio $estado")
        assertEquals(1, repo.room.value.size, "nada é espelhado (local intocado)")
        assertEquals(0, drive.revisao)
        assertEquals(sinicial, drive.conteudo)
    }

    // --- EDGE_PRIMEIRO_LINK_EXISTE: vínculo com canônico presente = pull-only restore ---

    @Test
    fun `primeiro vinculo com canonico existente e restore pull-only`() = runTest {
        val canonico = SchemaBoxpace.codificar(
            BoxpaceArquivo(encomendas = listOf(SchemaBoxpace.encomendaParaRegistro(encomenda()))),
        )
        val drive = DriveSimulado(initial = canonico)
        val repo = RepositorioFake()
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = coordenador(drive, repo, token)

        assertTrue(coord.vincular())

        assertEquals(SyncState.Vinculado, coord.syncState.value)
        assertEquals(1, repo.room.value.size, "Room espelhado no primeiro vínculo")
        // não grava: o arquivo do Drive permanece intocado
        assertEquals(0, drive.revisao)
        assertEquals(canonico, drive.conteudo)
    }

    @Test
    fun `primeiro vinculo com canonico e 401 leva a Perdida e preserva deltas`() = runTest {
        // lista responde OK com o arquivo; a leitura (alt=media) devolve 401 no restore
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get && request.url.parameters.contains("q")) {
                respondJson(
                    """{"files":[{"id":"FILE_ID","name":"${SchemaBoxpace.NOME_ARQUIVO}","headRevisionId":"rev-0"}]}""",
                )
            } else {
                respondJson("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized)
            }
        }
        val client = HttpClient(engine)
        val repo = RepositorioFake().apply {
            deltas += DeltaPendente.Salvar(encomenda(), "correios:AA123456789BR", "2026-09-01T12:00:00Z")
        }
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = CoordenadorDeSync(
            encomendaRepository = repo,
            preferenciasRepository = PrefsFake(),
            drive = DriveCliente(client, token),
            tokenProvider = token,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertFalse(coord.vincular())

        assertEquals(SyncState.SincronizacaoPerdida, coord.syncState.value)
        assertEquals(1, repo.deltas.size, "deltas devem sobreviver ao 401")
    }

    // --- EDGE_PRIMEIRO_LINK_VAZIO: promoção existente (Drive sem arquivo) preservada ---

    @Test
    fun `primeiro vinculo com drive vazio ainda promove o cache local`() = runTest {
        val drive = DriveSimulado(initial = null)
        val repo = RepositorioFake().apply { room.value = listOf(encomenda()) }
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = coordenador(drive, repo, token)

        assertTrue(coord.vincular())

        assertEquals(SyncState.Vinculado, coord.syncState.value)
        assertTrue(drive.conteudo != null, "Drive vazio ⇒ promoção grava o canônico")
        val arquivo = SchemaBoxpace.decodificar(drive.conteudo!!)
        assertTrue(arquivo.encomendas.any { it.codigo == "AA123456789BR" })
    }

    // --- AUTO_SYNC_POS_RESTORE: vínculo com delta pendente drena SEM dispararSync manual ---

    @Test
    fun `primeiro vinculo com canonico e delta pendente auto-sincroniza sem dispararSync manual`() = runTest {
        val canonico = SchemaBoxpace.codificar(
            BoxpaceArquivo(
                encomendas = listOf(
                    SchemaBoxpace.encomendaParaRegistro(encomenda(atualizadaEm = "2026-09-01T10:00:00Z")),
                ),
            ),
        )
        val drive = DriveSimulado(initial = canonico)
        val repo = RepositorioFake().apply {
            deltas += DeltaPendente.Salvar(
                encomenda = encomenda(etiqueta = "Fone novo", atualizadaEm = "2026-09-01T12:00:00Z"),
                alvoId = "correios:AA123456789BR",
                criadoEm = "2026-09-01T12:00:00Z",
            )
        }
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = CoordenadorDeSync(
            encomendaRepository = repo,
            preferenciasRepository = PrefsFake(),
            drive = DriveCliente(HttpClient(drive.engine()), token),
            tokenProvider = token,
            scope = this,
        )

        assertTrue(coord.vincular())

        // vínculo com canônico presente virou restore pull-only: Room reflete o LWW
        assertEquals(SyncState.Vinculado, coord.syncState.value)
        val refletida = repo.room.value.single { it.id == "correios:AA123456789BR" }
        assertEquals("Fone novo", refletida.etiqueta, "delta mais novo vence o LWW já no restore")

        // SEM dispararSync manual, o auto-sync pós-restore leva o delta ao canônico
        aguardarDreno { repo.deltas.isEmpty() && drive.revisao == 1 }

        val arquivo = SchemaBoxpace.decodificar(drive.conteudo!!)
        assertEquals("Fone novo", arquivo.encomendas.single().etiqueta)
        assertEquals("2026-09-01T12:00:00Z", arquivo.encomendas.single().updatedAt)
        assertTrue(repo.deltas.isEmpty(), "delta processado pelo auto-sync")
        assertEquals(SyncState.Vinculado, coord.syncState.value)
    }

    // --- FAIL_RESTORE_COM_VINCULO: retorna false E preserva o vínculo + estado terminal ---

    @Test
    fun `restaurar falho com vinculo previo mantem Vinculado e retorna false`() = runTest {
        val canonico = SchemaBoxpace.codificar(
            BoxpaceArquivo(encomendas = listOf(SchemaBoxpace.encomendaParaRegistro(encomenda()))),
        )
        val drive = DriveSimulado(initial = canonico)
        val repo = RepositorioFake()
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = coordenador(drive, repo, token)

        assertTrue(coord.vincular())
        assertEquals(SyncState.Vinculado, coord.syncState.value)

        repo.falharDuranteRestore = true
        assertFalse(coord.restaurar())

        assertEquals(SyncState.Vinculado, coord.syncState.value, "vínculo anterior é preservado")
        assertTrue(coord.syncState.value != SyncState.Restaurando, "não pode ficar preso em Restaurando")
    }

    @Test
    fun `excecao durante o restore termina ocioso e nunca preso em Restaurando`() = runTest {
        val drive = DriveSimulado(initial = SchemaBoxpace.codificar(BoxpaceArquivo()))
        val repo = RepositorioFake().apply {
            deltas += DeltaPendente.Salvar(
                encomenda = encomenda(),
                alvoId = "correios:AA123456789BR",
                criadoEm = "2026-09-01T12:00:00Z",
            )
            falharDuranteRestore = true
        }
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = coordenador(drive, repo, token)

        assertFalse(coord.restaurar())

        assertEquals(SyncState.Desvinculado, coord.syncState.value, "estado terminal, não Restaurando")
        assertEquals(1, repo.deltas.size, "deltas preservados em falha")
    }

    // --- READ_FAIL_ON_FIRST_LINK: falha/401 na leitura nunca promovem nem escrevem ---

    @Test
    fun `falha de leitura no primeiro vinculo nao promove cache nem escreve`() = runTest {
        var escritas = 0
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post || request.method == HttpMethod.Patch) escritas++
            respond("", HttpStatusCode.InternalServerError)
        }
        val client = HttpClient(engine)
        val repo = RepositorioFake().apply { room.value = listOf(encomenda()) }
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = CoordenadorDeSync(
            encomendaRepository = repo,
            preferenciasRepository = PrefsFake(),
            drive = DriveCliente(client, token),
            tokenProvider = token,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertFalse(coord.vincular())

        assertEquals(SyncState.Desvinculado, coord.syncState.value)
        assertEquals(0, escritas, "falha de leitura nunca promove às cegas")
        assertEquals(1, repo.room.value.size, "local intacto")
    }

    @Test
    fun `401 no primeiro vinculo leva a Perdida sem escrever nada`() = runTest {
        var escritas = 0
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post || request.method == HttpMethod.Patch) escritas++
            respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized)
        }
        val client = HttpClient(engine)
        val repo = RepositorioFake().apply {
            room.value = listOf(encomenda())
            deltas += DeltaPendente.Salvar(encomenda(), "correios:AA123456789BR", "2026-09-01T12:00:00Z")
        }
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = CoordenadorDeSync(
            encomendaRepository = repo,
            preferenciasRepository = PrefsFake(),
            drive = DriveCliente(client, token),
            tokenProvider = token,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertFalse(coord.vincular())

        assertEquals(SyncState.SincronizacaoPerdida, coord.syncState.value)
        assertEquals(0, escritas, "401 nunca promove nem grava")
        assertEquals(1, repo.deltas.size, "deltas preservados")
    }

    // --- desvincular: volta a Desvinculado ---

    @Test
    fun `desvincular volta a Desvinculado e limpa token`() = runTest {
        val drive = DriveSimulado(initial = SchemaBoxpace.codificar(BoxpaceArquivo()))
        val repo = RepositorioFake()
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = coordenador(drive, repo, token)
        assertTrue(coord.vincular())

        assertTrue(coord.desvincular())
        assertEquals(SyncState.Desvinculado, coord.syncState.value)
        assertEquals(null, token.atual())
    }
}

/** Aguarda o dreno fire-and-forget do [CoordenadorDeSync.dispararSync] terminar. */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.aguardarDreno(condicao: () -> Boolean) {
    repeat(200) {
        testScheduler.advanceUntilIdle()
        runCurrent()
        if (condicao()) return
        delay(1)
    }
    assertTrue(condicao(), "dreno não concluiu em tempo hábil")
}

private suspend fun capturarCorpoMultipart(request: HttpRequestData): String? {
    val texto = request.body.toByteArray().decodeToString()
    val inicio = texto.indexOf("name=\"file\"")
    if (inicio < 0) return null
    val comeco = texto.indexOf("\r\n\r\n", inicio) + 4
    val fim = texto.indexOf("\r\n--", comeco)
    return if (fim > comeco) texto.substring(comeco, fim) else texto.substring(comeco)
}

private fun MockRequestHandleScope.respondJson(
    corpo: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = corpo,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)
