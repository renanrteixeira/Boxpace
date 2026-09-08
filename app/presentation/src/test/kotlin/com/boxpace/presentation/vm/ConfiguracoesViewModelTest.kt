package com.boxpace.presentation.vm

import com.boxpace.domain.DeltaPendente
import com.boxpace.domain.Encomenda
import com.boxpace.domain.EncomendaRepository
import com.boxpace.domain.Preferencias
import com.boxpace.domain.PreferenciasRepository
import com.boxpace.domain.Tema
import com.boxpace.domain.Transportadora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConfiguracoesViewModelTest {

    class PreferenciasRepoFake(
        var preferencias: Preferencias = Preferencias(),
    ) : PreferenciasRepository {
        override suspend fun carregar(): Preferencias = preferencias

        override suspend fun salvar(preferencias: Preferencias) {
            this.preferencias = preferencias
        }
    }

    class EncomendaRepoFake : EncomendaRepository {
        val deltas = mutableListOf<DeltaPendente>()
        var falharAoRegistrarDelta = false

        override fun observar(): Flow<List<Encomenda>> = MutableStateFlow(emptyList())

        override suspend fun salvar(encomenda: Encomenda) {}

        override suspend fun salvarComDelta(encomenda: Encomenda): Boolean = true

        override suspend fun buscarPorId(id: String): Encomenda? = null

        override suspend fun buscarPorCodigo(codigo: String, transportadora: Transportadora): Encomenda? = null

        override suspend fun listar(): List<Encomenda> = emptyList()

        override suspend fun listarAtivas(): List<Encomenda> = emptyList()

        override suspend fun listarFechadas(): List<Encomenda> = emptyList()

        override suspend fun excluir(id: String, criadoEm: String) {}

        override suspend fun registrarDeltaPendente(delta: DeltaPendente) {
            if (falharAoRegistrarDelta) throw RuntimeException("falha no registro de delta")
            deltas += delta
        }

        override suspend fun listarDeltasPendentes(): List<DeltaPendente> = deltas

        override suspend fun limparDeltasPendentes() {
            deltas.clear()
        }

        override suspend fun purgarFechadasAntigas(dias: Int) {}
    }

    private lateinit var preferenciasRepo: PreferenciasRepoFake
    private lateinit var encomendaRepo: EncomendaRepoFake

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        preferenciasRepo = PreferenciasRepoFake()
        encomendaRepo = EncomendaRepoFake()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun criarVm(): ConfiguracoesViewModel = ConfiguracoesViewModel(
        preferenciasRepository = preferenciasRepo,
        encomendaRepository = encomendaRepo,
        agora = { "2026-09-01T12:00:00Z" },
    )

    @Test
    fun DEFAULT_sem_preferencia_inicia_em_SISTEMA() = runTest {
        val vm = criarVm()
        assertEquals(Tema.SISTEMA, vm.tema.value)
    }

    @Test
    fun CARREGA_preferencia_persistida_ESCURO_inicia_em_ESCURO() = runTest {
        preferenciasRepo.preferencias = Preferencias(tema = Tema.ESCURO, updatedAt = "2026-09-01T11:00:00Z")

        val vm = criarVm()

        assertEquals(Tema.ESCURO, vm.tema.value)
    }

    @Test
    fun ALTERNAR_salva_preferencia_com_updatedAt_e_registra_delta_equivalente() = runTest {
        val vm = criarVm()

        vm.alternarTema(Tema.ESCURO)

        val persistida = preferenciasRepo.preferencias
        assertEquals(Tema.ESCURO, persistida.tema)
        assertEquals("2026-09-01T12:00:00Z", persistida.updatedAt)

        val delta = encomendaRepo.deltas.single() as DeltaPendente.SalvarPreferencia
        assertEquals("preferencias:tema", delta.alvoId)
        assertEquals(persistida.updatedAt, delta.criadoEm)
        assertEquals(persistida, delta.preferencias)

        val listados = encomendaRepo.listarDeltasPendentes()
        assertEquals(persistida, (listados.single() as DeltaPendente.SalvarPreferencia).preferencias)
    }

    @Test
    fun FALHA_DELTA_registro_de_delta_falha_mas_tema_continua_ESCURO() = runTest {
        val vm = criarVm()
        encomendaRepo.falharAoRegistrarDelta = true

        vm.alternarTema(Tema.ESCURO)

        assertEquals(Tema.ESCURO, vm.tema.value)
        assertEquals(Tema.ESCURO, preferenciasRepo.preferencias.tema)
        assertTrue(encomendaRepo.deltas.isEmpty())
    }
}