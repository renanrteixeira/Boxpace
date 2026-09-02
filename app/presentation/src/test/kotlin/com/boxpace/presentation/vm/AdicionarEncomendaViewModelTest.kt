package com.boxpace.presentation.vm

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
}