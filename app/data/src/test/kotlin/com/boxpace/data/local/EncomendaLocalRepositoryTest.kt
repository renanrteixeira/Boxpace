package com.boxpace.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.boxpace.domain.DeltaPendente
import com.boxpace.domain.Encomenda
import com.boxpace.domain.Evento
import com.boxpace.domain.Preferencias
import com.boxpace.domain.Tema
import com.boxpace.domain.Transportadora
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncomendaLocalRepositoryTest {

    private lateinit var database: EncomendaDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EncomendaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun repositorio(): EncomendaLocalRepository =
        EncomendaLocalRepository(database, Json { ignoreUnknownKeys = true })

    private fun encomenda(
        codigo: String = "AA123456789BR",
        transportadora: Transportadora = Transportadora.CORREIOS,
        eventos: List<Evento> = emptyList(),
        fechadaEm: String? = null,
        atualizadaEm: String = "2026-09-01T12:00:00Z",
    ): Encomenda = Encomenda(
        id = "${transportadora.scraperId}:$codigo",
        codigo = codigo,
        transportadora = transportadora,
        etiqueta = "Fone de ouvido",
        ultimoStatus = eventos.lastOrNull()?.descricao,
        statusEntregue = eventos.any { it.descricao.contains("entregue", ignoreCase = true) },
        eventos = eventos,
        criadaEm = "2026-09-01T11:00:00Z",
        atualizadaEm = atualizadaEm,
        fechadaEm = fechadaEm,
    )

    @Test
    fun PERSISTE_REINICIO_salvar_e_reler_de_novo_repositorio_traz_do_Room() = runBlocking {
        val repo = repositorio()
        val evento = Evento(data = "2026-09-01T10:00:00Z", descricao = "Objeto postado")
        repo.salvar(encomenda(eventos = listOf(evento)))

        val novoRepo = repositorio()
        val recuperada = novoRepo.buscarPorCodigo("AA123456789BR", Transportadora.CORREIOS)

        assertTrue(recuperada != null)
        assertEquals("AA123456789BR", recuperada!!.codigo)
        assertEquals(listOf(evento), recuperada.eventos)
    }

    @Test
    fun OBSERVA_MUTACAO_observar_emite_apos_salvar_e_excluir() = runBlocking {
        val repo = repositorio()
        val fluxo = repositorio().observar()

        assertEquals(emptyList<Encomenda>(), fluxo.first())

        val enc = encomenda("AA111111111BR")
        repo.salvar(enc)
        assertEquals(listOf("AA111111111BR"), fluxo.first().map { it.codigo })

        repo.excluir(enc.id)
        assertEquals(emptyList<Encomenda>(), fluxo.first())
    }

    @Test
    fun OBSERVA_REATIVA_EVENTOS_observar_restaura_timeline_de_eventos() = runBlocking {
        val repo = repositorio()
        val fluxo = repositorio().observar()
        val eventos = listOf(
            Evento(data = "2026-09-01T10:00:00Z", descricao = "Objeto postado", unidade = "CTE CUIABA"),
            Evento(data = "2026-09-01T11:00:00Z", descricao = "Saiu para entrega", unidade = "CTE CUIABA"),
        )
        val enc = encomenda("AA111111111BR", eventos = eventos)

        repo.salvar(enc)

        val observada = fluxo.first().single()
        assertEquals("AA111111111BR", observada.codigo)
        assertEquals(eventos, observada.eventos)
        assertEquals("Saiu para entrega", observada.ultimoStatus)
    }

    @Test
    fun ORDENACAO_observar_e_listar_melhor_atualizadaEm_primeiro() = runBlocking {
        val repo = repositorio()
        val antiga = encomenda("AA111111111BR", atualizadaEm = "2026-09-01T12:00:00Z")
        val nova = encomenda("AA222222222BR", atualizadaEm = "2026-09-05T12:00:00Z")
        repo.salvar(antiga)
        repo.salvar(nova)

        assertEquals(
            listOf("AA222222222BR", "AA111111111BR"),
            repo.observar().first().map { it.codigo },
        )
        assertEquals(
            listOf("AA222222222BR", "AA111111111BR"),
            repo.listar().map { it.codigo },
        )
    }

    @Test
    fun MUTACAO_REGISTRA_DELTA_salvar_e_excluir_registram_deltas_com_LWW() = runBlocking {
        val repo = repositorio()
        val enc = encomenda("AA111111111BR")

        repo.registrarDeltaPendente(
            DeltaPendente.Salvar(encomenda = enc, alvoId = enc.id, criadoEm = "2026-09-01T10:00:00Z"),
        )
        repo.registrarDeltaPendente(
            DeltaPendente.Excluir(alvoId = "correios:AA222222222BR", criadoEm = "2026-09-01T11:00:00Z"),
        )

        val deltas = repo.listarDeltasPendentes()
        assertEquals(2, deltas.size)
        assertEquals("correios:AA222222222BR", deltas.last().alvoId)
        assertTrue(deltas[0] is DeltaPendente.Salvar)
        assertEquals("AA111111111BR", (deltas[0] as DeltaPendente.Salvar).encomenda.codigo)
        assertTrue(deltas[1] is DeltaPendente.Excluir)

        repo.limparDeltasPendentes()
        assertEquals(emptyList<DeltaPendente>(), repo.listarDeltasPendentes())
    }

    @Test
    fun PURGA_FECHADAS_purga_fechado_antigo_e_preserva_ativo_e_fechado_recente() = runBlocking {
        val repo = repositorio()
        val antigo = encomenda("AA111111111BR", fechadaEm = "2020-01-01T00:00:00Z")
        val ativo = encomenda("AA222222222BR", fechadaEm = null)
        val recente = encomenda(
            "AA333333333BR",
            fechadaEm = Instant.now().minus(10, ChronoUnit.DAYS).toString(),
        )
        repo.salvar(antigo)
        repo.salvar(ativo)
        repo.salvar(recente)

        repo.purgarFechadasAntigas(90)

        val restantes = repo.listar().map { it.codigo }.toSet()
        assertEquals(setOf("AA222222222BR", "AA333333333BR"), restantes)
        assertNull(repo.buscarPorId(antigo.id))
    }

    @Test
    fun RE_ADICIONAR_mesma_identidade_atualiza_sem_duplicar_e_eventos_nao_duplicam() = runBlocking {
        val repo = repositorio()
        val primeira = encomenda(
            "AA111111111BR",
            eventos = listOf(Evento("2026-09-01T10:00:00Z", "Objeto postado", unidade = "CTE CUIABA")),
        )
        val segunda = encomenda(
            "AA111111111BR",
            eventos = listOf(
                Evento("2026-09-01T10:00:00Z", "Objeto postado", unidade = "CTE CUIABA"),
                Evento("2026-09-01T11:00:00Z", "Saiu para entrega", unidade = "CTE CUIABA"),
            ),
            atualizadaEm = "2026-09-02T12:00:00Z",
        )

        repo.salvar(primeira)
        repo.salvar(segunda)

        val todas = repo.listar()
        assertEquals(1, todas.size)
        assertEquals(2, todas.single().eventos.size)
        assertEquals("2026-09-02T12:00:00Z", todas.single().atualizadaEm)
    }

    @Test
    fun REABRIR_VOLTA_AO_TOPO_atualizadaEm_mais_recente_ordena_primeiro() = runBlocking {
        val repo = repositorio()
        val fechada = encomenda("AA111111111BR", fechadaEm = "2026-09-01T12:00:00Z")
        val ativa = encomenda("AA222222222BR")
        repo.salvar(fechada)
        repo.salvar(ativa)

        val reaberta = fechada.copy(fechadaEm = null, atualizadaEm = "2026-09-10T12:00:00Z")
        repo.salvar(reaberta)

        assertEquals(
            listOf("AA111111111BR", "AA222222222BR"),
            repo.observar().first().map { it.codigo },
        )
        assertNull(repo.buscarPorId(reaberta.id)?.fechadaEm)
    }

    @Test
    fun ROUND_TRIP_DELTA_salvar_paraJson_doJson_preserva_todos_os_campos() = runBlocking {
        val repo = repositorio()
        val enc = Encomenda(
            id = "jt:888123456",
            codigo = "888123456",
            transportadora = Transportadora.JT,
            etiqueta = "Fone de ouvido",
            ultimoStatus = "Entregue",
            statusEntregue = true,
            eventos = listOf(
                Evento(
                    data = "2026-09-01T10:00:00Z",
                    descricao = "Objeto postado",
                    cidade = "Cuiabá",
                    uf = "MT",
                    unidade = "CTE CUIABA",
                ),
            ),
            criadaEm = "2026-09-01T09:00:00Z",
            atualizadaEm = "2026-09-02T12:00:00Z",
            fechadaEm = "2026-09-03T12:00:00Z",
            cpfDestinatario = "12345678909",
            buscasSemEventos = 2,
        )

        repo.registrarDeltaPendente(
            DeltaPendente.Salvar(encomenda = enc, alvoId = enc.id, criadoEm = "2026-09-03T12:30:00Z"),
        )

        val recuperado = (repo.listarDeltasPendentes().single() as DeltaPendente.Salvar).encomenda
        assertEquals(enc, recuperado)
    }

    @Test
    fun PARSING_DEFENSIVO_payload_corrompido_e_tipo_desconhecido_sao_ignorados_sem_quebrar() = runBlocking {
        val repo = repositorio()
        val dao = database.deltaPendenteDao()

        dao.inserir(
            DeltaPendenteEntity(alvoId = "jt:888123456", tipo = "salvar", criadoEm = "2026-09-01T10:00:00Z", payload = "{{{nao-e-json"),
        )
        dao.inserir(
            DeltaPendenteEntity(alvoId = "x:1", tipo = "tipo-desconhecido", criadoEm = "2026-09-01T10:00:00Z", payload = null),
        )
        dao.inserir(
            DeltaPendenteEntity(alvoId = "correios:AA222222222BR", tipo = "excluir", criadoEm = "2026-09-01T11:00:00Z", payload = null),
        )

        val deltas = repo.listarDeltasPendentes()

        assertEquals(1, deltas.size)
        val unico = deltas.single()
        assertTrue(unico is DeltaPendente.Excluir)
        assertEquals("correios:AA222222222BR", unico.alvoId)
    }

    @Test
    fun ROUND_TRIP_PREFERENCIA_salvarPreferencia_paraJson_doJson_preserva_tema_e_updatedAt() = runBlocking {
        val repo = repositorio()
        val preferencias = Preferencias(tema = Tema.ESCURO, updatedAt = "2026-09-01T12:30:00Z")

        repo.registrarDeltaPendente(
            DeltaPendente.SalvarPreferencia(
                preferencias = preferencias,
                alvoId = "preferencias:tema",
                criadoEm = preferencias.updatedAt,
            ),
        )

        val recuperado = repo.listarDeltasPendentes().single() as DeltaPendente.SalvarPreferencia
        assertEquals(preferencias, recuperado.preferencias)
        assertEquals("preferencias:tema", recuperado.alvoId)
        assertEquals("2026-09-01T12:30:00Z", recuperado.criadoEm)
    }

    @Test
    fun PREFERENCIA_CORROMPIDA_payload_invalido_de_salvar_preferencia_e_ignorado_sem_excecao() = runBlocking {
        val repo = repositorio()
        val dao = database.deltaPendenteDao()

        dao.inserir(
            DeltaPendenteEntity(
                alvoId = "preferencias:tema",
                tipo = "salvar-preferencia",
                criadoEm = "2026-09-01T10:00:00Z",
                payload = "{{{nao-e-json",
            ),
        )

        val deltas = repo.listarDeltasPendentes()
        assertEquals(emptyList<DeltaPendente>(), deltas)
    }

    @Test
    fun CONTADOR_SEM_DADOS_round_trip_persiste_buscasSemEventos_no_room() = runBlocking {
        val repo = repositorio()
        val semDados = encomenda("AA111111111BR").copy(buscasSemEventos = 3)

        repo.salvar(semDados)

        assertEquals(3, repo.buscarPorCodigo("AA111111111BR", Transportadora.CORREIOS)?.buscasSemEventos)
        assertEquals(3, repo.listar().single().buscasSemEventos)
        assertEquals(3, repo.observar().first().single().buscasSemEventos)
    }

    @Test
    fun EXCLUIR_CASCATA_cancela_delta_Salvar_pendente_e_registra_Excluir_em_transacao() = runBlocking {
        val repo = repositorio()
        val enc = encomenda("AA111111111BR")
        repo.salvar(enc)
        repo.registrarDeltaPendente(
            DeltaPendente.Salvar(encomenda = enc, alvoId = enc.id, criadoEm = "2026-09-01T10:00:00Z"),
        )
        repo.registrarDeltaPendente(
            DeltaPendente.Excluir(alvoId = "correios:AA222222222BR", criadoEm = "2026-09-01T09:00:00Z"),
        )

        repo.excluir(enc.id, "2026-09-01T12:00:00Z")

        // Encomenda removida do Room sem resíduo.
        assertNull(repo.buscarPorId(enc.id))
        assertTrue(repo.listar().none { it.id == enc.id })

        // O `Salvar` pendente daquela `alvoId` foi cancelado e só resta o `Excluir` registrado.
        val deltas = repo.listarDeltasPendentes()
        assertEquals(2, deltas.size)
        assertTrue(deltas.all { it is DeltaPendente.Excluir })
        assertTrue(deltas.any { it.alvoId == enc.id && it.criadoEm == "2026-09-01T12:00:00Z" })
    }

    @Test
    fun LIMPEZA_POR_LOTE_remove_apenas_o_marcador_do_lote_e_preserva_o_mid_cycle() = runBlocking {
        val repo = repositorio()
        val lote = listOf(
            DeltaPendente.Salvar(encomenda("AA111111111BR"), "correios:AA111111111BR", "2026-09-01T10:00:00Z"),
            DeltaPendente.Salvar(encomenda("AA222222222BR"), "correios:AA222222222BR", "2026-09-01T11:00:00Z"),
        )
        lote.forEach { repo.registrarDeltaPendente(it) }
        // chegaram "durante o ciclo": um Excluir e um SalvarPreferencia com o mesmo alvoId? não — alvos distintos
        repo.registrarDeltaPendente(
            DeltaPendente.Excluir("correios:AA333333333BR", "2026-09-01T12:00:00Z"),
        )
        repo.registrarDeltaPendente(
            DeltaPendente.SalvarPreferencia(
                preferencias = Preferencias(tema = Tema.ESCURO, updatedAt = "2026-09-01T12:30:00Z"),
                alvoId = "preferencias:tema",
                criadoEm = "2026-09-01T12:30:00Z",
            ),
        )

        val removidos = repo.limparDeltasPendentes(lote)

        assertEquals("só o lote do ciclo é consumido", 2, removidos)
        val deltas = repo.listarDeltasPendentes()
        assertEquals("deltas mid-cycle permanecem pendentes (AD-SYNC-9)", 2, deltas.size)
        assertTrue(deltas.any { it.criadoEm == "2026-09-01T12:00:00Z" && it is DeltaPendente.Excluir })
        assertTrue(deltas.any { it.criadoEm == "2026-09-01T12:30:00Z" && it is DeltaPendente.SalvarPreferencia })
    }

    @Test
    fun REMOVER_ESPELHO_remove_fantasma_sem_registrar_Excluir_e_cancela_deltas_do_alvo() = runBlocking {
        val repo = repositorio()
        val fantasma = encomenda("AA111111111BR")
        repo.salvar(fantasma)
        repo.registrarDeltaPendente(
            DeltaPendente.Salvar(encomenda = fantasma, alvoId = fantasma.id, criadoEm = "2026-09-01T10:00:00Z"),
        )

        repo.removerEspelho(fantasma.id)

        // fantasma sumiu do Room sem resíduo
        assertNull(repo.buscarPorId(fantasma.id))
        assertTrue(repo.listar().none { it.id == fantasma.id })
        // reconciliação de espelho não é exclusão do usuário: nenhum tombstone nasce
        assertTrue(repo.listarDeltasPendentes().none { it is DeltaPendente.Excluir })
        // e os deltas em voo daquele alvo são cancelados (não podem re-materializar o fantasma)
        assertEquals(emptyList<DeltaPendente>(), repo.listarDeltasPendentes())
    }
}
