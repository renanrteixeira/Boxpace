package com.boxpace.presentation.vm

import com.boxpace.domain.DeltaPendente
import com.boxpace.domain.Encomenda
import com.boxpace.domain.EncomendaRemoteDataSource
import com.boxpace.domain.EncomendaRepository
import com.boxpace.domain.ErroDeRastreio
import com.boxpace.domain.Evento
import com.boxpace.domain.RastrearEncomendaUseCase
import com.boxpace.domain.RastreioResult
import com.boxpace.domain.Transportadora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AdicionarEncomendaViewModelTest {

    class RemoteStub(
        var resultado: (String, Transportadora, String?) -> RastreioResult =
            { _, _, _ -> RastreioResult.Sucesso("", emptyList()) },
    ) : EncomendaRemoteDataSource {
        var chamadas: Int = 0
        var ultimoCpf: String? = null

        override suspend fun rastrear(
            codigo: String,
            transportadora: Transportadora,
            cpfDestinatario: String?,
        ): RastreioResult {
            chamadas++
            ultimoCpf = cpfDestinatario
            return resultado(codigo, transportadora, cpfDestinatario)
        }
    }

    /** Fake do repositório espelhando Room: `observar()` é um `MutableStateFlow`. */
    class RepositorioFake : EncomendaRepository {
        private val state = MutableStateFlow<List<Encomenda>>(emptyList())
        val deltas = mutableListOf<DeltaPendente>()
        var chamadasPurga = 0

        override fun observar(): Flow<List<Encomenda>> = state

        /** Se `true`, `salvar` falha (simula erro de escrita no Room). */
        var falharEmSalvar = false

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

        override suspend fun excluir(id: String) {
            state.value = state.value.filterNot { it.id == id }
        }

        override suspend fun registrarDeltaPendente(delta: DeltaPendente) {
            deltas += delta
        }

        override suspend fun listarDeltasPendentes(): List<DeltaPendente> = deltas

        override suspend fun limparDeltasPendentes() {
            deltas.clear()
        }

        override suspend fun purgarFechadasAntigas(dias: Int) {
            chamadasPurga++
        }
    }

    private lateinit var remote: RemoteStub
    private lateinit var repo: RepositorioFake

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        remote = RemoteStub()
        repo = RepositorioFake()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Cria o VM e ativa a coleta dos flows derivados para o estado propagar. */
    private fun TestScope.criarVm(): AdicionarEncomendaViewModel {
        val vm = AdicionarEncomendaViewModel(
            rastrear = RastrearEncomendaUseCase(remote),
            repository = repo,
            agora = { "2026-09-01T12:00:00Z" },
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.encomendas.collect { } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.encomendasAtivas.collect { } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.encomendasFechadas.collect { } }
        return vm
    }

    private fun preencherCorreios(vm: AdicionarEncomendaViewModel) {
        vm.codigoMudou("AA123456789BR")
        vm.etiquetaMudou("Fone de ouvido")
    }

    @Test
    fun `happy path correios agrega no topo e emite fechar`() = runTest {
        remote.resultado = { _, _, _ ->
            RastreioResult.Sucesso(
                codigo = "AA123456789BR",
                eventos = listOf(
                    Evento(data = "2026-09-01T10:00:00", descricao = "Objeto postado"),
                ),
            )
        }
        val vm = criarVm()
        preencherCorreios(vm)

        vm.adicionar()

        val encomendas = vm.encomendas.value
        assertEquals(1, encomendas.size)
        assertEquals("AA123456789BR", encomendas.single().codigo)
        assertEquals("Fone de ouvido", encomendas.single().etiqueta)
        assertEquals("Objeto postado", encomendas.single().ultimoStatus)
        assertNull(vm.form.value.erro)
        assertFalse(vm.form.value.carregando)
        assertEquals(AdicionarEncomendaViewModel.UiEvent.Fechar, vm.eventos.first())
    }

    @Test
    fun `falha de persistencia mantem dialog aberto com erro e nao fecha`() = runTest {
        remote.resultado = { _, _, _ ->
            RastreioResult.Sucesso(
                codigo = "AA123456789BR",
                eventos = listOf(Evento(data = "2026-09-01T10:00:00", descricao = "Objeto postado")),
            )
        }
        repo.falharEmSalvar = true
        val vm = criarVm()
        preencherCorreios(vm)

        vm.adicionar()

        assertTrue(vm.encomendas.value.isEmpty())
        assertEquals("Não deu pra salvar agora. Tente de novo.", vm.form.value.erro)
        assertFalse(vm.form.value.carregando)
        assertEquals("Fone de ouvido", vm.form.value.etiqueta)
    }

    @Test
    fun `aguardandoServidor comeca falso e zera apos sucesso`() = runTest {
        val vm = criarVm()
        assertFalse(vm.form.value.aguardandoServidor)

        preencherCorreios(vm)
        vm.adicionar()

        assertTrue(vm.encomendas.value.isNotEmpty())
        assertFalse(vm.form.value.carregando)
        assertFalse(vm.form.value.aguardandoServidor)
    }

    @Test
    fun `codigo invalido bloqueia sem tocar a rede`() = runTest {
        val vm = criarVm()
        vm.codigoMudou("AA123BR")
        vm.etiquetaMudou("Fone de ouvido")

        vm.adicionar()

        assertEquals("Confere o código?", vm.form.value.erro)
        assertEquals(0, remote.chamadas)
        assertTrue(vm.encomendas.value.isEmpty())
    }

    @Test
    fun `etiqueta vazia ou so espacos bloqueia sem tocar a rede`() = runTest {
        val vm = criarVm()
        vm.codigoMudou("AA123456789BR")
        vm.etiquetaMudou("   ")

        vm.adicionar()

        assertEquals("Dá um nome pra essa encomenda", vm.form.value.erro)
        assertEquals(0, remote.chamadas)
        assertTrue(vm.encomendas.value.isEmpty())
    }

    @Test
    fun `jt sem cpf valido bloqueia com mensagem clara`() = runTest {
        val vm = criarVm()
        vm.transportadoraMudou(Transportadora.JT)
        vm.codigoMudou("888123456")
        vm.etiquetaMudou("Fone de ouvido")
        vm.cpfMudou("123")

        vm.adicionar()

        assertEquals("Confere o CPF do destinatário?", vm.form.value.erro)
        assertEquals(0, remote.chamadas)
    }

    @Test
    fun `jt com cpf mascarado sanitiza e envia somente digitos`() = runTest {
        remote.resultado = { _, _, _ -> RastreioResult.Sucesso(codigo = "888123456", eventos = emptyList()) }
        val vm = criarVm()
        vm.transportadoraMudou(Transportadora.JT)
        vm.codigoMudou("888123456")
        vm.etiquetaMudou("Fone de ouvido")
        vm.cpfMudou("123.456.789-09")

        vm.adicionar()

        assertEquals(1, remote.chamadas)
        assertEquals("12345678909", remote.ultimoCpf)
        assertTrue(vm.encomendas.value.isNotEmpty())
        assertEquals("12345678909", vm.encomendas.value.single().cpfDestinatario)
    }

    @Test
    fun `falha de rede preserva campos e nao cria fantasma`() = runTest {
        remote.resultado = { _, _, _ -> throw ErroDeRastreio.SemConexao() }
        val vm = criarVm()
        preencherCorreios(vm)

        vm.adicionar()

        assertEquals("Sem conexão agora", vm.form.value.erro)
        assertEquals("AA123456789BR", vm.form.value.codigo)
        assertEquals("Fone de ouvido", vm.form.value.etiqueta)
        assertTrue(vm.encomendas.value.isEmpty())
    }

    @Test
    fun `falha generica usa mensagem neutra em vez de sem conexao`() = runTest {
        remote.resultado = { _, _, _ -> throw IllegalStateException("bug do provedor") }
        val vm = criarVm()
        preencherCorreios(vm)

        vm.adicionar()

        assertEquals("Não deu pra adicionar agora. Tente de novo.", vm.form.value.erro)
        assertEquals("AA123456789BR", vm.form.value.codigo)
        assertTrue(vm.encomendas.value.isEmpty())
    }

    @Test
    fun `provedor nao implementado preserva campos e informa`() = runTest {
        remote.resultado = { _, transportadora, _ -> RastreioResult.NaoImplementado(transportadora) }
        val vm = criarVm()
        vm.transportadoraMudou(Transportadora.JT)
        vm.codigoMudou("888123456")
        vm.etiquetaMudou("Fone de ouvido")
        vm.cpfMudou("12345678909")

        vm.adicionar()

        assertEquals("Provedor não disponível.", vm.form.value.erro)
        assertEquals("888123456", vm.form.value.codigo)
        assertTrue(vm.encomendas.value.isEmpty())
    }

    @Test
    fun `re-adicionar mesma identidade atualiza e nao duplica`() = runTest {
        val vm = criarVm()
        remote.resultado = { _, _, _ -> RastreioResult.Sucesso(codigo = "AA123456789BR", eventos = emptyList()) }
        preencherCorreios(vm)
        vm.adicionar()

        remote.resultado = { _, _, _ ->
            RastreioResult.Sucesso(
                codigo = "AA123456789BR",
                eventos = listOf(Evento(data = "2026-09-01T11:00:00", descricao = "Saiu para entrega")),
            )
        }
        preencherCorreios(vm)
        vm.adicionar()

        assertEquals(1, vm.encomendas.value.size)
        assertEquals("Saiu para entrega", vm.encomendas.value.single().ultimoStatus)
    }

    @Test
    fun `status entregue derivado do evento`() = runTest {
        remote.resultado = { _, _, _ ->
            RastreioResult.Sucesso(
                codigo = "AA123456789BR",
                eventos = listOf(Evento(data = "2026-09-01T11:00:00", descricao = "Objeto entregue ao destinatário")),
            )
        }
        val vm = criarVm()
        preencherCorreios(vm)

        vm.adicionar()

        assertTrue(vm.encomendas.value.single().statusEntregue)
    }

    @Test
    fun `descartar limpa o formulario sem tocar a lista`() = runTest {
        val vm = criarVm()
        preencherCorreios(vm)

        vm.descartar()

        assertEquals("", vm.form.value.codigo)
        assertEquals("", vm.form.value.etiqueta)
        assertTrue(vm.encomendas.value.isEmpty())
    }

    private fun encomenda(
        etiqueta: String,
        codigo: String = "AA123456789BR",
        transportadora: Transportadora = Transportadora.CORREIOS,
    ): Encomenda = Encomenda(
        id = "${transportadora.scraperId}:$codigo",
        codigo = codigo,
        transportadora = transportadora,
        etiqueta = etiqueta,
        criadaEm = "2026-09-01T12:00:00Z",
        atualizadaEm = "2026-09-01T12:00:00Z",
    )

    @Test
    fun `filtrar busca por fragmento de etiqueta case-insensitive`() {
        val lista = listOf(
            encomenda("Fone de ouvido", "AA111111111BR"),
            encomenda("Teclado mecânico", "AA222222222BR"),
        )

        assertEquals(1, AdicionarEncomendaViewModel.filtrarBusca(lista, "fone").size)
        assertEquals(1, AdicionarEncomendaViewModel.filtrarBusca(lista, "FONE").size)
        assertEquals("Fone de ouvido", AdicionarEncomendaViewModel.filtrarBusca(lista, "ouvido").single().etiqueta)
        assertEquals(2, AdicionarEncomendaViewModel.filtrarBusca(lista, "").size)
    }

    @Test
    fun `filtrar busca ignora espacos ao redor do termo`() {
        val lista = listOf(
            encomenda("Fone de ouvido", "AA111111111BR"),
            encomenda("Teclado mecânico", "AA222222222BR"),
        )

        assertEquals(1, AdicionarEncomendaViewModel.filtrarBusca(lista, "  fone  ").size)
        assertEquals("Fone de ouvido", AdicionarEncomendaViewModel.filtrarBusca(lista, "  fone  ").single().etiqueta)
        assertEquals(1, AdicionarEncomendaViewModel.filtrarBusca(lista, "  111  ").size)
    }

    @Test
    fun `filtrar busca por fragmento de codigo case-insensitive`() {
        val lista = listOf(
            encomenda("A", "AA111111111BR"),
            encomenda("B", "aa222222222br"),
        )

        assertEquals(1, AdicionarEncomendaViewModel.filtrarBusca(lista, "111").size)
        assertEquals("B", AdicionarEncomendaViewModel.filtrarBusca(lista, "222222").single().etiqueta)
    }

    @Test
    fun `filtrar sem match retorna lista vazia`() {
        val lista = listOf(encomenda("Fone de ouvido"))

        assertTrue(AdicionarEncomendaViewModel.filtrarBusca(lista, "teclado").isEmpty())
    }

    @Test
    fun `filtrar nao faz dedup por etiqueta repetida`() {
        val lista = listOf(
            encomenda("Caixa", "AA111111111BR"),
            encomenda("Caixa", "AA222222222BR"),
            encomenda("Caixa", "AA333333333BR"),
        )

        val resultado = AdicionarEncomendaViewModel.filtrarBusca(lista, "caixa")
        assertEquals(3, resultado.size)
        assertEquals(lista, resultado)
    }

    private fun adicionarEncomenda(vm: AdicionarEncomendaViewModel, codigo: String, etiqueta: String) {
        remote.resultado = { c, _, _ -> RastreioResult.Sucesso(codigo = c, eventos = emptyList()) }
        vm.codigoMudou(codigo)
        vm.etiquetaMudou(etiqueta)
        vm.adicionar()
    }

    @Test
    fun `ativas e fechadas derivadas por fechadaEm`() = runTest {
        val vm = criarVm()
        adicionarEncomenda(vm, "AA111111111BR", "Ativa um")
        adicionarEncomenda(vm, "AA222222222BR", "Fecho esta")

        val idFechada = vm.encomendas.value.first { it.codigo == "AA222222222BR" }.id
        vm.arquivar(idFechada)
        testScheduler.advanceUntilIdle()

        assertEquals(1, vm.encomendasAtivas.value.size)
        assertEquals("Ativa um", vm.encomendasAtivas.value.single().etiqueta)
        assertEquals(1, vm.encomendasFechadas.value.size)
        assertEquals("Fecho esta", vm.encomendasFechadas.value.single().etiqueta)
    }

    @Test
    fun `arquivar seta fechadaEm e move para fechadas`() = runTest {
        val vm = criarVm()
        adicionarEncomenda(vm, "AA111111111BR", "Um")
        val id = vm.encomendas.value.single().id

        vm.arquivar(id)
        testScheduler.advanceUntilIdle()

        assertNotNull(vm.detalhe(id).first()?.fechadaEm)
        assertTrue(vm.encomendasAtivas.value.isEmpty())
        assertEquals(1, vm.encomendasFechadas.value.size)
    }

    @Test
    fun `reabrir volta ao topo de ativas com atualizadaEm agora`() = runTest {
        val vm = criarVm()
        adicionarEncomenda(vm, "AA111111111BR", "Um")
        adicionarEncomenda(vm, "AA222222222BR", "Dois")
        val idReaberta = vm.encomendas.value.first { it.codigo == "AA222222222BR" }.id
        vm.arquivar(idReaberta)
        testScheduler.advanceUntilIdle()

        vm.reabrir(idReaberta)
        testScheduler.advanceUntilIdle()

        assertEquals(idReaberta, vm.encomendasAtivas.value.first().id)
        assertEquals(2, vm.encomendasAtivas.value.size)
        assertNull(vm.detalhe(idReaberta).first()?.fechadaEm)
        assertEquals("2026-09-01T12:00:00Z", vm.detalhe(idReaberta).first()?.atualizadaEm)
        assertTrue(vm.encomendasFechadas.value.isEmpty())
    }

    @Test
    fun `excluir ativa remove da lista`() = runTest {
        val vm = criarVm()
        adicionarEncomenda(vm, "AA111111111BR", "Um")
        val id = vm.encomendas.value.single().id

        vm.excluir(id)
        testScheduler.advanceUntilIdle()

        assertNull(vm.detalhe(id).first())
        assertTrue(vm.encomendas.value.isEmpty())
    }

    @Test
    fun `excluir fechada remove da lista`() = runTest {
        val vm = criarVm()
        adicionarEncomenda(vm, "AA111111111BR", "Um")
        val id = vm.encomendas.value.single().id
        vm.arquivar(id)
        testScheduler.advanceUntilIdle()

        vm.excluir(id)

        assertNull(vm.detalhe(id).first())
        assertTrue(vm.encomendas.value.isEmpty())
    }

    @Test
    fun `revalidar sucesso atualiza eventos in place preservando fechadaEm`() = runTest {
        val vm = criarVm()
        remote.resultado = { c, _, _ ->
            RastreioResult.Sucesso(codigo = c, eventos = listOf(Evento("2026-09-01T09:00:00", "Objeto postado")))
        }
        vm.codigoMudou("AA111111111BR")
        vm.etiquetaMudou("Um")
        vm.adicionar()
        val id = vm.encomendas.value.single().id
        vm.arquivar(id)
        testScheduler.advanceUntilIdle()

        remote.resultado = { c, _, _ ->
            RastreioResult.Sucesso(
                codigo = c,
                eventos = listOf(Evento("2026-09-01T10:00:00", "Objeto entregue ao destinatário")),
            )
        }
        vm.revalidar(id)
        testScheduler.advanceUntilIdle()

        val atualizada = vm.detalhe(id).first()!!
        assertEquals("Objeto entregue ao destinatário", atualizada.ultimoStatus)
        assertEquals(1, atualizada.eventos.size)
        assertEquals("Objeto entregue ao destinatário", atualizada.eventos.single().descricao)
        assertTrue(atualizada.statusEntregue)
        assertNotNull(atualizada.fechadaEm)
    }

    @Test
    fun `revalidar erro mantem cache`() = runTest {
        val vm = criarVm()
        adicionarEncomenda(vm, "AA111111111BR", "Um")
        val id = vm.encomendas.value.single().id
        val antes = vm.detalhe(id).first()!!.atualizadaEm

        remote.resultado = { _, _, _ -> throw ErroDeRastreio.SemConexao() }
        vm.revalidar(id)
        testScheduler.advanceUntilIdle()

        val depois = vm.detalhe(id).first()!!
        assertEquals(antes, depois.atualizadaEm)
        assertTrue(depois.eventos.isEmpty())
    }

    @Test
    fun `revalidar id inexistente e no-op silencioso`() = runTest {
        val vm = criarVm()
        adicionarEncomenda(vm, "AA111111111BR", "Um")
        val id = vm.encomendas.value.single().id
        val chamadasAntes = remote.chamadas
        vm.excluir(id)
        testScheduler.advanceUntilIdle()

        vm.revalidar(id)

        assertEquals(chamadasAntes, remote.chamadas)
        assertNull(vm.detalhe(id).first())
    }

    @Test
    fun `detalhe retorna encomenda ou null`() = runTest {
        val vm = criarVm()
        adicionarEncomenda(vm, "AA111111111BR", "Um")

        assertNotNull(vm.detalhe("correios:AA111111111BR").first())
        assertNull(vm.detalhe("correios:NAOEXISTE").first())
    }

    @Test
    fun `adicionar registra delta de Salvar`() = runTest {
        val vm = criarVm()
        adicionarEncomenda(vm, "AA111111111BR", "Um")

        assertEquals(1, repo.deltas.size)
        val delta = repo.deltas.single()
        assertTrue(delta is DeltaPendente.Salvar)
        assertEquals("correios:AA111111111BR", delta.alvoId)
        assertEquals("2026-09-01T12:00:00Z", delta.criadoEm)
    }

    @Test
    fun `arquivar reabrir e excluir registram deltas`() = runTest {
        val vm = criarVm()
        adicionarEncomenda(vm, "AA111111111BR", "Um")
        val id = vm.encomendas.value.single().id

        vm.arquivar(id)
        testScheduler.advanceUntilIdle()
        assertTrue(repo.deltas.last() is DeltaPendente.Salvar)
        assertNotNull((repo.deltas.last() as DeltaPendente.Salvar).encomenda.fechadaEm)

        vm.reabrir(id)
        testScheduler.advanceUntilIdle()
        assertTrue(repo.deltas.last() is DeltaPendente.Salvar)
        assertNull((repo.deltas.last() as DeltaPendente.Salvar).encomenda.fechadaEm)

        vm.excluir(id)
        testScheduler.advanceUntilIdle()
        assertEquals("correios:AA111111111BR", repo.deltas.last().alvoId)
        assertTrue(repo.deltas.last() is DeltaPendente.Excluir)
    }

    @Test
    fun `init purga fechadas antigas 90 dias`() = runTest {
        criarVm()
        assertEquals(1, repo.chamadasPurga)
    }

    @Test
    fun `revalidar sucesso dispara purga apos`() = runTest {
        val vm = criarVm()
        adicionarEncomenda(vm, "AA111111111BR", "Um")
        val chamadasAntes = repo.chamadasPurga

        remote.resultado = { c, _, _ -> RastreioResult.Sucesso(codigo = c, eventos = emptyList()) }
        val id = vm.encomendas.value.single().id
        vm.revalidar(id)
        testScheduler.advanceUntilIdle()

        assertEquals(chamadasAntes + 1, repo.chamadasPurga)
    }

    @Test
    fun `adicionar sem eventos inicia contador em 1 e com eventos em 0`() = runTest {
        val vm = criarVm()
        remote.resultado = { c, _, _ -> RastreioResult.Sucesso(codigo = c, eventos = emptyList()) }
        vm.codigoMudou("AA111111111BR")
        vm.etiquetaMudou("Um")
        vm.adicionar()
        assertEquals(1, vm.encomendas.value.single().buscasSemEventos)

        remote.resultado = { c, _, _ ->
            RastreioResult.Sucesso(codigo = c, eventos = listOf(Evento("2026-09-01T10:00:00", "Objeto postado")))
        }
        vm.codigoMudou("AA222222222BR")
        vm.etiquetaMudou("Dois")
        vm.adicionar()
        assertEquals(0, vm.encomendas.value.first { it.codigo == "AA222222222BR" }.buscasSemEventos)
    }

    @Test
    fun `revalidar sem eventos incrementa e zera quando aparecem eventos`() = runTest {
        val vm = criarVm()
        adicionarEncomenda(vm, "AA111111111BR", "Um")
        val id = vm.encomendas.value.single().id
        assertEquals(1, vm.encomendas.value.single().buscasSemEventos)

        remote.resultado = { c, _, _ -> RastreioResult.Sucesso(codigo = c, eventos = emptyList()) }
        vm.revalidar(id)
        testScheduler.advanceUntilIdle()
        assertEquals(2, vm.encomendas.value.single().buscasSemEventos)

        remote.resultado = { c, _, _ ->
            RastreioResult.Sucesso(codigo = c, eventos = listOf(Evento("2026-09-01T10:00:00", "Objeto postado")))
        }
        vm.revalidar(id)
        testScheduler.advanceUntilIdle()
        assertEquals(0, vm.encomendas.value.single().buscasSemEventos)
    }

    @Test
    fun `semDados sinaliza somente a partir de 3 buscas sem eventos`() = runTest {
        val vm = criarVm()
        val semEventos = Encomenda(
            id = "correios:X",
            codigo = "X",
            transportadora = Transportadora.CORREIOS,
            etiqueta = "Algo",
            criadaEm = "2026-09-01T10:00:00Z",
            atualizadaEm = "2026-09-01T10:00:00Z",
            buscasSemEventos = 2,
        )
        assertFalse(vm.semDados(semEventos))
        assertTrue(vm.semDados(semEventos.copy(buscasSemEventos = 3)))
        assertFalse(vm.semDados(semEventos.copy(buscasSemEventos = 3, eventos = listOf(Evento("2026-09-01T10:00:00", "x")))))
    }

    @Test
    fun `repetirBusca refaz a busca e incrementa o contador sem eventos`() = runTest {
        val vm = criarVm()
        adicionarEncomenda(vm, "AA111111111BR", "Um")
        val id = vm.encomendas.value.single().id
        val chamadasAntes = remote.chamadas

        vm.repetirBusca(id)
        testScheduler.advanceUntilIdle()

        assertEquals(chamadasAntes + 1, remote.chamadas)
        assertEquals(2, vm.encomendas.value.single().buscasSemEventos)
    }
}
