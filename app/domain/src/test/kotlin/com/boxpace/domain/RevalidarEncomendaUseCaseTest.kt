package com.boxpace.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Testes do use case canônico de revalidação (Story 3.1) — a lógica de
 * transição e migração compartilhada entre foreground e worker.
 */
class RevalidarEncomendaUseCaseTest {

    /** Fake do repositório espelhando Room (salvar por id). */
    class RepositorioFake : EncomendaRepository {
        private val state = MutableStateFlow<List<Encomenda>>(emptyList())
        val deltas = mutableListOf<DeltaPendente>()
        var falharEmSalvar = false

        override fun observar(): Flow<List<Encomenda>> = state
        override suspend fun salvar(encomenda: Encomenda) {
            if (falharEmSalvar) throw RuntimeException("write failed")
            state.value = listOf(encomenda) + state.value.filterNot { it.id == encomenda.id }
        }
        override suspend fun buscarPorId(id: String): Encomenda? = state.value.firstOrNull { it.id == id }
        override suspend fun buscarPorCodigo(codigo: String, transportadora: Transportadora): Encomenda? =
            state.value.firstOrNull { it.codigo == codigo && it.transportadora == transportadora }
        override suspend fun listar(): List<Encomenda> = state.value
        override suspend fun listarAtivas(): List<Encomenda> = state.value.filter { it.fechadaEm == null }
        override suspend fun listarFechadas(): List<Encomenda> = state.value.filter { it.fechadaEm != null }
        override suspend fun excluir(id: String, criadoEm: String) {
            state.value = state.value.filterNot { it.id == id }
        }
        override suspend fun registrarDeltaPendente(delta: DeltaPendente) { deltas += delta }
        override suspend fun listarDeltasPendentes(): List<DeltaPendente> = deltas
        override suspend fun limparDeltasPendentes() { deltas.clear() }
        override suspend fun purgarFechadasAntigas(dias: Int) {}
    }

    private fun encomenda(
        ultimoStatus: String? = null,
        eventos: List<Evento> = emptyList(),
        fechadaEm: String? = null,
        buscasSemEventos: Int = 0,
    ) = Encomenda(
        id = "correios:AA123456789BR",
        codigo = "AA123456789BR",
        transportadora = Transportadora.CORREIOS,
        etiqueta = "Fone de ouvido",
        ultimoStatus = ultimoStatus,
        eventos = eventos,
        criadaEm = "2026-09-01T12:00:00Z",
        atualizadaEm = "2026-09-01T12:00:00Z",
        fechadaEm = fechadaEm,
        buscasSemEventos = buscasSemEventos,
    )

    private fun sucesso(vararg descricoes: String) =
        RastreioResult.Sucesso(
            codigo = "AA123456789BR",
            eventos = descricoes.mapIndexed { i, d ->
                Evento(data = "2026-09-01T10:00:0${i}", descricao = d)
            },
        )

    // --- HAPPY_TRANSICAO: ultimoStatus muda → transitou = true ---

    @Test
    fun `transicao de status marca transitou e persiste`() = runTest {
        val repo = RepositorioFake()
        val useCase = RevalidarEncomendaUseCase(repo, agora = { "2026-09-01T12:00:00Z" })
        val atual = encomenda(ultimoStatus = "Objeto postado")

        val r = useCase.executar(atual, sucesso("Saiu para entrega"))

        val sucesso = assertIs<RevalidarEncomendaUseCase.Resultado.Sucesso>(r)
        assertTrue(sucesso.transitou)
        assertEquals("Saiu para entrega", sucesso.encomenda.ultimoStatus)
        val persistida = repo.buscarPorId("correios:AA123456789BR")!!
        assertEquals("Saiu para entrega", persistida.ultimoStatus)
        assertTrue(repo.deltas.single() is DeltaPendente.Salvar)
    }

    // --- SEM_TRANSICAO: ultimoStatus igual → transitou = false, mas persiste ---

    @Test
    fun `mesmo status nao marca transicao mas persiste`() = runTest {
        val repo = RepositorioFake()
        val useCase = RevalidarEncomendaUseCase(repo, agora = { "2026-09-01T12:00:00Z" })
        val atual = encomenda(ultimoStatus = "Saiu para entrega")

        val r = useCase.executar(atual, sucesso("Saiu para entrega"))

        val sucesso = assertIs<RevalidarEncomendaUseCase.Resultado.Sucesso>(r)
        assertFalse(sucesso.transitou)
        assertEquals("Saiu para entrega", sucesso.encomenda.ultimoStatus)
        assertEquals(1, repo.deltas.size)
    }

    // --- Migração automática para Fechados (AD-6/AD-FECHADO) ---

    @Test
    fun `entregue no fetch migra ativa para fechada setando fechadaEm`() = runTest {
        val repo = RepositorioFake()
        val useCase = RevalidarEncomendaUseCase(repo, agora = { "2026-09-01T12:00:00Z" })
        val atual = encomenda(ultimoStatus = "Saiu para entrega")

        val r = useCase.executar(atual, sucesso("Objeto entregue ao destinatário"))

        val sucesso = assertIs<RevalidarEncomendaUseCase.Resultado.Sucesso>(r)
        assertTrue(sucesso.encomenda.estaEntregue())
        assertTrue(sucesso.encomenda.estaFechada())
        assertEquals("2026-09-01T12:00:00Z", sucesso.encomenda.fechadaEm)
        assertTrue(repo.listarFechadas().isNotEmpty())
        assertTrue(repo.listarAtivas().isEmpty())
    }

    @Test
    fun `migracao nao sobrescreve fechadaEm de encomenda ja arquivada`() = runTest {
        val repo = RepositorioFake()
        val useCase = RevalidarEncomendaUseCase(repo, agora = { "2026-09-01T12:00:00Z" })
        val atual = encomenda(
            ultimoStatus = "Saiu para entrega",
            fechadaEm = "2026-09-01T08:00:00Z",
        )

        val r = useCase.executar(atual, sucesso("Objeto entregue ao destinatário"))

        val sucesso = assertIs<RevalidarEncomendaUseCase.Resultado.Sucesso>(r)
        assertEquals("2026-09-01T08:00:00Z", sucesso.encomenda.fechadaEm)
    }

    // --- Sem eventos: mantém contador (append idempotente / badge "Sem dados") ---

    @Test
    fun `sem eventos incrementa buscasSemEventos e zera quando aparecem`() = runTest {
        val repo = RepositorioFake()
        val useCase = RevalidarEncomendaUseCase(repo, agora = { "2026-09-01T12:00:00Z" })
        val atual = encomenda(ultimoStatus = null, buscasSemEventos = 1)

        val primeiro = assertIs<RevalidarEncomendaUseCase.Resultado.Sucesso>(
            useCase.executar(atual, RastreioResult.Sucesso("AA123456789BR", emptyList())),
        )
        assertEquals(2, primeiro.encomenda.buscasSemEventos)

        val segundo = assertIs<RevalidarEncomendaUseCase.Resultado.Sucesso>(
            useCase.executar(primeiro.encomenda, sucesso("Objeto postado")),
        )
        assertEquals(0, segundo.encomenda.buscasSemEventos)
    }

    // --- FETCH_FALHOU: fetch meio-vazio com encomenda ja com eventos = no-op ---

    @Test
    fun `fetch vazio com eventos previos e no-op (mantem cache, sem delta)`() = runTest {
        val repo = RepositorioFake()
        val useCase = RevalidarEncomendaUseCase(repo, agora = { "2026-09-01T12:00:00Z" })
        val atual = encomenda(
            ultimoStatus = "Saiu para entrega",
            eventos = listOf(Evento("2026-09-01T10:00:00", "Saiu para entrega")),
        )

        val r = useCase.executar(atual, RastreioResult.Sucesso("AA123456789BR", emptyList()))

        assertIs<RevalidarEncomendaUseCase.Resultado.NadaAFazer>(r)
        assertTrue(repo.deltas.isEmpty())
    }

    // --- Provedor não implementado = no-op ---

    @Test
    fun `provedor nao implementado e no-op`() = runTest {
        val repo = RepositorioFake()
        val useCase = RevalidarEncomendaUseCase(repo)
        val atual = encomenda()

        val r = useCase.executar(atual, RastreioResult.NaoImplementado(Transportadora.JT))

        assertIs<RevalidarEncomendaUseCase.Resultado.NadaAFazer>(r)
        assertTrue(repo.deltas.isEmpty())
    }

    // --- Falha de persistência no Room → não notifica nem persiste ---

    @Test
    fun `falha na escrita nao marca transicao nem persiste`() = runTest {
        val repo = RepositorioFake().apply { falharEmSalvar = true }
        val useCase = RevalidarEncomendaUseCase(repo, agora = { "2026-09-01T12:00:00Z" })
        val atual = encomenda(ultimoStatus = "Objeto postado")

        val r = useCase.executar(atual, sucesso("Saiu para entrega"))

        assertIs<RevalidarEncomendaUseCase.Resultado.FalhaNaPersistencia>(r)
        assertNull(repo.buscarPorId("correios:AA123456789BR"))
        assertTrue(repo.deltas.isEmpty())
    }

    // --- SEM_TRANSICAO: nada -> nenhum status, ambos null, sem transição ---

    @Test
    fun `status permanece nulo sem transicao`() = runTest {
        val repo = RepositorioFake()
        val useCase = RevalidarEncomendaUseCase(repo, agora = { "2026-09-01T12:00:00Z" })
        val atual = encomenda(ultimoStatus = null)

        val r = useCase.executar(atual, RastreioResult.Sucesso("AA123456789BR", emptyList()))

        val sucesso = assertIs<RevalidarEncomendaUseCase.Resultado.Sucesso>(r)
        assertFalse(sucesso.transitou)
        assertNull(sucesso.encomenda.ultimoStatus)
    }
}
