package com.boxpace.presentation.vm

import com.boxpace.domain.Encomenda
import com.boxpace.domain.EncomendaRemoteDataSource
import com.boxpace.domain.ErroDeRastreio
import com.boxpace.domain.Evento
import com.boxpace.domain.RastrearEncomendaUseCase
import com.boxpace.domain.RastreioResult
import com.boxpace.domain.Transportadora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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

    private lateinit var remote: RemoteStub

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        remote = RemoteStub()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): AdicionarEncomendaViewModel =
        AdicionarEncomendaViewModel(rastrear = RastrearEncomendaUseCase(remote), agora = { "2026-09-01T12:00:00Z" })

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
        val vm = viewModel()
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
    fun `codigo invalido bloqueia sem tocar a rede`() = runTest {
        val vm = viewModel()
        vm.codigoMudou("AA123BR")
        vm.etiquetaMudou("Fone de ouvido")

        vm.adicionar()

        assertEquals("Confere o código?", vm.form.value.erro)
        assertEquals(0, remote.chamadas)
        assertTrue(vm.encomendas.value.isEmpty())
    }

    @Test
    fun `etiqueta vazia ou so espacos bloqueia sem tocar a rede`() = runTest {
        val vm = viewModel()
        vm.codigoMudou("AA123456789BR")
        vm.etiquetaMudou("   ")

        vm.adicionar()

        assertEquals("Dá um nome pra essa encomenda", vm.form.value.erro)
        assertEquals(0, remote.chamadas)
        assertTrue(vm.encomendas.value.isEmpty())
    }

    @Test
    fun `jt sem cpf valido bloqueia com mensagem clara`() = runTest {
        val vm = viewModel()
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
        val vm = viewModel()
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
        val vm = viewModel()
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
        val vm = viewModel()
        preencherCorreios(vm)

        vm.adicionar()

        assertEquals("Não deu pra adicionar agora. Tente de novo.", vm.form.value.erro)
        assertEquals("AA123456789BR", vm.form.value.codigo)
        assertTrue(vm.encomendas.value.isEmpty())
    }

    @Test
    fun `provedor nao implementado preserva campos e informa`() = runTest {
        remote.resultado = { _, transportadora, _ -> RastreioResult.NaoImplementado(transportadora) }
        val vm = viewModel()
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
        val vm = viewModel()
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
        val vm = viewModel()
        preencherCorreios(vm)

        vm.adicionar()

        assertTrue(vm.encomendas.value.single().statusEntregue)
    }

    @Test
    fun `descartar limpa o formulario sem tocar a lista`() = runTest {
        val vm = viewModel()
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
        val vm = viewModel()
        adicionarEncomenda(vm, "AA111111111BR", "Ativa um")
        adicionarEncomenda(vm, "AA222222222BR", "Fecho esta")

        val fecharId = vm.buscarPorId("correios:AA222222222BR")!!.id
        vm.arquivar(fecharId)

        assertEquals(1, vm.encomendasAtivas().size)
        assertEquals("Ativa um", vm.encomendasAtivas().single().etiqueta)
        assertEquals(1, vm.encomendasFechadas().size)
        assertEquals("Fecho esta", vm.encomendasFechadas().single().etiqueta)
    }

    @Test
    fun `arquivar seta fechadaEm e move para fechadas`() = runTest {
        val vm = viewModel()
        adicionarEncomenda(vm, "AA111111111BR", "Um")
        val id = vm.buscarPorId("correios:AA111111111BR")!!.id

        vm.arquivar(id)

        assertNotNull(vm.buscarPorId(id)?.fechadaEm)
        assertTrue(vm.encomendasAtivas().isEmpty())
        assertEquals(1, vm.encomendasFechadas().size)
    }

    @Test
    fun `reabrir volta ao topo de ativas com atualizadaEm agora`() = runTest {
        val vm = viewModel()
        adicionarEncomenda(vm, "AA111111111BR", "Um")
        adicionarEncomenda(vm, "AA222222222BR", "Dois")
        val idReaberta = vm.buscarPorId("correios:AA222222222BR")!!.id
        vm.arquivar(idReaberta)

        vm.reabrir(idReaberta)

        assertEquals(idReaberta, vm.encomendasAtivas().first().id)
        assertEquals(2, vm.encomendasAtivas().size)
        assertNull(vm.buscarPorId(idReaberta)?.fechadaEm)
        assertEquals("2026-09-01T12:00:00Z", vm.buscarPorId(idReaberta)?.atualizadaEm)
        assertTrue(vm.encomendasFechadas().isEmpty())
    }

    @Test
    fun `excluir ativa remove da lista`() = runTest {
        val vm = viewModel()
        adicionarEncomenda(vm, "AA111111111BR", "Um")
        val id = vm.buscarPorId("correios:AA111111111BR")!!.id

        vm.excluir(id)

        assertNull(vm.buscarPorId(id))
        assertTrue(vm.encomendas.value.isEmpty())
    }

    @Test
    fun `excluir fechada remove da lista`() = runTest {
        val vm = viewModel()
        adicionarEncomenda(vm, "AA111111111BR", "Um")
        val id = vm.buscarPorId("correios:AA111111111BR")!!.id
        vm.arquivar(id)

        vm.excluir(id)

        assertNull(vm.buscarPorId(id))
        assertTrue(vm.encomendas.value.isEmpty())
    }

    @Test
    fun `revalidar sucesso atualiza eventos in place preservando fechadaEm`() = runTest {
        val vm = viewModel()
        remote.resultado = { c, _, _ ->
            RastreioResult.Sucesso(codigo = c, eventos = listOf(Evento("2026-09-01T09:00:00", "Objeto postado")))
        }
        vm.codigoMudou("AA111111111BR")
        vm.etiquetaMudou("Um")
        vm.adicionar()
        val id = vm.buscarPorId("correios:AA111111111BR")!!.id
        vm.arquivar(id)

        remote.resultado = { c, _, _ ->
            RastreioResult.Sucesso(
                codigo = c,
                eventos = listOf(Evento("2026-09-01T10:00:00", "Objeto entregue ao destinatário")),
            )
        }
        vm.revalidar(id)

        val atualizada = vm.buscarPorId(id)!!
        assertEquals("Objeto entregue ao destinatário", atualizada.ultimoStatus)
        assertEquals(1, atualizada.eventos.size)
        assertEquals("Objeto entregue ao destinatário", atualizada.eventos.single().descricao)
        assertTrue(atualizada.statusEntregue)
        assertNotNull(atualizada.fechadaEm)
    }

    @Test
    fun `revalidar erro mantem cache`() = runTest {
        val vm = viewModel()
        adicionarEncomenda(vm, "AA111111111BR", "Um")
        val id = vm.buscarPorId("correios:AA111111111BR")!!.id
        val antes = vm.buscarPorId(id)!!.atualizadaEm

        remote.resultado = { _, _, _ -> throw ErroDeRastreio.SemConexao() }
        vm.revalidar(id)

        val depois = vm.buscarPorId(id)!!
        assertEquals(antes, depois.atualizadaEm)
        assertTrue(depois.eventos.isEmpty())
    }

    @Test
    fun `revalidar id inexistente e no-op silencioso`() = runTest {
        val vm = viewModel()
        adicionarEncomenda(vm, "AA111111111BR", "Um")
        val id = vm.buscarPorId("correios:AA111111111BR")!!.id
        val chamadasAntes = remote.chamadas
        vm.excluir(id)

        vm.revalidar(id)

        assertEquals(chamadasAntes, remote.chamadas)
        assertNull(vm.buscarPorId(id))
    }

    @Test
    fun `buscarPorId retorna encomenda ou null`() = runTest {
        val vm = viewModel()
        adicionarEncomenda(vm, "AA111111111BR", "Um")

        assertNotNull(vm.buscarPorId("correios:AA111111111BR"))
        assertNull(vm.buscarPorId("correios:NAOEXISTE"))
    }
}