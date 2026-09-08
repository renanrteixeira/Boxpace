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
        override suspend fun listarDeltasPendentes(): List<DeltaPendente> = deltas.toList()
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
        val repo = RepositorioFake().apply {
            deltas += DeltaPendente.Salvar(
                encomenda = encomenda(),
                alvoId = "correios:AA123456789BR",
                criadoEm = "2026-09-01T12:00:00Z",
            )
        }
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = coordenador(drive, repo, token)

        assertTrue(coord.vincular())
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
        val repo = RepositorioFake().apply {
            deltas += DeltaPendente.Excluir(alvoId = "correios:AA123456789BR", criadoEm = "2026-09-01T13:00:00Z")
        }
        val token = TokenOAuthProvider().apply { fornecer("token") }
        val coord = coordenador(drive, repo, token)

        assertTrue(coord.vincular())
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
        // Simula falsificação: grava mas devolve outro conteúdo no read-back.
        var gravados = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val isMedia = request.url.parameters.contains("alt")
            if (request.method == HttpMethod.Get && isMedia) {
                respondJson("""{"schemaVersion":1,"encomendas":[],"preferencias":[]}""") // sempre vazio → mismatch
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

    // --- SCHEMA_MAIOR: arquivo com schema mais novo não é sobrescrito; aviso persistido; sync pausa ---

    @Test
    fun `schema maior nao sobrescreve e sinaliza pausa com aviso`() = runTest {
        val sinicial = """{"schemaVersion":2,"encomendas":[],"preferencias":[]}"""
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
