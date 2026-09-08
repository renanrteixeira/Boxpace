package com.boxpace.data.cloud

import com.boxpace.domain.DeltaPendente
import com.boxpace.domain.Encomenda
import com.boxpace.domain.Evento
import com.boxpace.domain.Preferencias
import com.boxpace.domain.Tema
import com.boxpace.domain.Transportadora
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Testes do merge LWW por registro (AD-SYNC-5/5A) e do SchemaBoxpace round-trip.
 * Sem Android SDK — testáveis como Kotlin puro.
 */
class MergeRegistrosTest {

    private fun encomenda(
        codigo: String = "AA123456789BR",
        transportadora: Transportadora = Transportadora.CORREIOS,
        etiqueta: String = "Fone",
        criadaEm: String = "2026-09-01T10:00:00Z",
        atualizadaEm: String = "2026-09-01T10:00:00Z",
    ) = Encomenda(
        id = "${transportadora.scraperId}:$codigo",
        codigo = codigo,
        transportadora = transportadora,
        etiqueta = etiqueta,
        ultimoStatus = "Objeto postado",
        eventos = listOf(Evento("2026-09-01T10:00:00", "Objeto postado")),
        criadaEm = criadaEm,
        atualizadaEm = atualizadaEm,
        fechadaEm = null,
    )

    // --- promoção do 1º vínculo (canônico ∅, LWW; tombstone de Excluir) ---

    @Test
    fun `promocao importa cache local e tombstone do delta Excluir`() {
        val local = encomenda(atualizadaEm = "2026-09-01T12:00:00Z")
        val deltas = listOf<DeltaPendente>(
            DeltaPendente.Excluir(alvoId = "correios:ZZ000", criadoEm = "2026-09-01T11:00:00Z"),
        )

        val arquivo = MergeRegistros.aplicarDeltas(
            MergeRegistros.promover(listOf(local)),
            deltas,
        )

        val vivo = arquivo.encomendas.first { !it.tombstone }
        assertEquals("AA123456789BR", vivo.codigo)
        val tombstone = arquivo.encomendas.first { it.tombstone }
        assertEquals("ZZ000", tombstone.codigo)
        assertEquals("2026-09-01T11:00:00Z", tombstone.updatedAt)
    }

    // --- LWW: delta mais recente vence; canônico mais recente é mantido ---

    @Test
    fun `lww delta salvar mais recente que canonico vence`() {
        val canonico = BoxpaceArquivo(
            encomendas = listOf(
                SchemaBoxpace.encomendaParaRegistro(encomenda(atualizadaEm = "2026-09-01T09:00:00Z")),
            ),
        )
        val delta = DeltaPendente.Salvar(
            encomenda = encomenda(etiqueta = "Fone novo", atualizadaEm = "2026-09-01T12:00:00Z"),
            alvoId = "correios:AA123456789BR",
            criadoEm = "2026-09-01T12:00:00Z",
        )

        val resultado = MergeRegistros.aplicarDeltas(canonico, listOf(delta))

        assertEquals("Fone novo", resultado.encomendas.single().etiqueta)
        assertEquals("2026-09-01T12:00:00Z", resultado.encomendas.single().updatedAt)
    }

    @Test
    fun `lww mantem canonico quando delta e mais antigo`() {
        val canonico = BoxpaceArquivo(
            encomendas = listOf(
                SchemaBoxpace.encomendaParaRegistro(encomenda(etiqueta = "Originais", atualizadaEm = "2026-09-01T15:00:00Z")),
            ),
        )
        val delta = DeltaPendente.Salvar(
            encomenda = encomenda(etiqueta = "Antigo", atualizadaEm = "2026-09-01T10:00:00Z"),
            alvoId = "correios:AA123456789BR",
            criadoEm = "2026-09-01T10:00:00Z",
        )

        val resultado = MergeRegistros.aplicarDeltas(canonico, listOf(delta))

        assertEquals("Originais", resultado.encomendas.single().etiqueta)
    }

    // --- tombstone mais recente remove cópia viva; cópia viva mais nova substitui ---

    @Test
    fun `tombstone mais recente remove copia viva`() {
        val canonico = BoxpaceArquivo(
            encomendas = listOf(
                SchemaBoxpace.encomendaParaRegistro(encomenda(atualizadaEm = "2026-09-01T10:00:00Z")),
            ),
        )
        val delta = DeltaPendente.Excluir(alvoId = "correios:AA123456789BR", criadoEm = "2026-09-01T12:00:00Z")

        val resultado = MergeRegistros.aplicarDeltas(canonico, listOf(delta))

        val registro = resultado.encomendas.single()
        assertTrue(registro.tombstone)
        assertFalse(registro.statusEntregue)
    }

    @Test
    fun `copia viva mais nova substitui tombstone antigo`() {
        val canonico = BoxpaceArquivo(
            encomendas = listOf(
                SchemaBoxpace.tombstoneParaRegistro("AA123456789BR", Transportadora.CORREIOS, "2026-09-01T09:00:00Z"),
            ),
        )
        val delta = DeltaPendente.Salvar(
            encomenda = encomenda(atualizadaEm = "2026-09-01T12:00:00Z"),
            alvoId = "correios:AA123456789BR",
            criadoEm = "2026-09-01T12:00:00Z",
        )

        val resultado = MergeRegistros.aplicarDeltas(canonico, listOf(delta))

        val registro = resultado.encomendas.single()
        assertFalse(registro.tombstone)
        assertEquals("AA123456789BR", registro.codigo)
    }

    // --- drena Salvar/Excluir/SalvarPreferencia em lote único ---

    @Test
    fun `drena salvar excluir e prefs em um unico lote`() {
        val canonico = BoxpaceArquivo()
        val deltas = listOf<DeltaPendente>(
            DeltaPendente.Salvar(encomenda = encomenda(), alvoId = "correios:AA123456789BR", criadoEm = "2026-09-01T12:00:00Z"),
            DeltaPendente.SalvarPreferencia(
                preferencias = Preferencias(tema = Tema.ESCURO, updatedAt = "2026-09-01T12:00:00Z"),
                alvoId = "preferencias:tema",
                criadoEm = "2026-09-01T12:00:00Z",
            ),
        )

        val resultado = MergeRegistros.aplicarDeltas(canonico, deltas)

        assertEquals(1, resultado.encomendas.size)
        assertEquals(1, resultado.preferencias.size)
        assertEquals("preferencias:tema", resultado.preferencias.single().chave)
        assertEquals("escuro", resultado.preferencias.single().valor)
    }

    // --- preferências LWW por chave ---

    @Test
    fun `preferencia lww por chave vence por updatedAt`() {
        val resultado = MergeRegistros.aplicarDeltas(
            BoxpaceArquivo(
                preferencias = listOf(
                    RegistroPreferencia("preferencias:tema", "escuro", "2026-09-01T12:00:00Z"),
                ),
            ),
            listOf(
                DeltaPendente.SalvarPreferencia(
                    preferencias = Preferencias(tema = Tema.CLARO, updatedAt = "2026-09-01T08:00:00Z"),
                    alvoId = "preferencias:tema",
                    criadoEm = "2026-09-01T08:00:00Z",
                ),
            ),
        )
        assertEquals("escuro", resultado.preferencias.single().valor)
    }

    // --- round-trip SchemaBoxpace (registro vivo <-> domínio) ---

    @Test
    fun `schema round trip preserva encomenda`() {
        val original = encomenda(
            etiqueta = "Fone",
            atualizadaEm = "2026-09-01T12:00:00Z",
        )
        val registro = SchemaBoxpace.encomendaParaRegistro(original)
        val conteudo = SchemaBoxpace.codificar(BoxpaceArquivo(encomendas = listOf(registro)))
        val deVolta = SchemaBoxpace.decodificar(conteudo).encomendas.single()

        assertEquals(original.codigo, deVolta.codigo)
        assertEquals("correios", deVolta.transportadora)
        assertEquals(original.etiqueta, deVolta.etiqueta)
        assertEquals(original.atualizadaEm, deVolta.updatedAt)
        assertNull(deVolta.fechado)

        val dominio = SchemaBoxpace.registroParaEncomenda(deVolta)
        assertEquals(original.id, dominio.id)
        assertEquals(original.etiqueta, dominio.etiqueta)
        assertEquals(original.atualizadaEm, dominio.atualizadaEm)
        assertNull(dominio.fechadaEm)
    }

    @Test
    fun `maisRecente compara ISO-8601`() {
        assertTrue(MergeRegistros.maisRecente("2026-09-01T12:00:00Z", "2026-09-01T09:00:00Z"))
        assertFalse(MergeRegistros.maisRecente("2026-09-01T09:00:00Z", "2026-09-01T12:00:00Z"))
    }
}
