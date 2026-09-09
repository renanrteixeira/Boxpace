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

    private fun roundTrip(encomenda: Encomenda): Encomenda {
        val conteudo = SchemaBoxpace.codificar(
            BoxpaceArquivo(encomendas = listOf(SchemaBoxpace.encomendaParaRegistro(encomenda))),
        )
        return SchemaBoxpace.registroParaEncomenda(SchemaBoxpace.decodificar(conteudo).encomendas.single())
    }

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
        assertEquals("CORREIOS", deVolta.transportadora)
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
    fun `round trip JT com cpf etiqueta e eventos preserva tudo exceto buscasSemEventos`() {
        val original = Encomenda(
            id = "jt:888123456789",
            codigo = "888123456789",
            transportadora = Transportadora.JT,
            etiqueta = "Fone J&T",
            ultimoStatus = "Objeto em trânsito",
            statusEntregue = false,
            eventos = listOf(
                Evento("2026-09-01T10:00:00", "Objeto postado", "São Paulo", "SP", "AGF Centro"),
                Evento("2026-09-02T10:00:00", "Em trânsito", "Campinas", "SP", "CTE Campinas"),
            ),
            criadaEm = "2026-09-01T09:00:00Z",
            atualizadaEm = "2026-09-02T09:00:00Z",
            fechadaEm = null,
            cpfDestinatario = "12345678909",
            buscasSemEventos = 3,
        )

        val deVolta = roundTrip(original)

        assertEquals(original.copy(buscasSemEventos = 0), deVolta)
    }

    @Test
    fun `round trip preferencias preserva registro`() {
        val registro = MergeRegistros.preferenciaParaRegistro(
            "preferencias:tema",
            Preferencias(tema = Tema.ESCURO, updatedAt = "2026-09-01T12:00:00Z"),
        )

        val conteudo = SchemaBoxpace.codificar(BoxpaceArquivo(preferencias = listOf(registro)))
        val deVolta = SchemaBoxpace.decodificar(conteudo).preferencias.single()

        assertEquals(registro, deVolta)
    }

    // --- migração v1 -> v2 (etiqueta ausente + transportadora scraperId) ---

    @Test
    fun `migra v1 para v2 normalizando etiqueta e transportadora de vivos e tombstones`() {
        val v1 = """
            {
              "schemaVersion": 1,
              "encomendas": [
                {"codigo":"AA123456789BR","transportadora":"correios","updatedAt":"2026-09-01T10:00:00Z"},
                {"codigo":"888123456789","transportadora":"jt","etiqueta":null,"updatedAt":"2026-09-01T10:00:00Z"},
                {"tombstone":true,"codigo":"ZZ000","transportadora":"correios","updatedAt":"2026-09-01T09:00:00Z"}
              ],
              "preferencias": []
            }
        """.trimIndent()

        val migrado = SchemaBoxpace.migrarParaSchemaAtual(SchemaBoxpace.decodificar(v1))

        assertEquals(2, migrado.schemaVersion)
        val correios = migrado.encomendas.first { it.codigo == "AA123456789BR" }
        assertEquals("CORREIOS", correios.transportadora)
        assertEquals("AA123456789BR", correios.etiqueta)
        val jt = migrado.encomendas.first { it.codigo == "888123456789" }
        assertEquals("JT", jt.transportadora)
        // "etiqueta":null explícito é coagido para "" e preenchido no vivo
        assertEquals("888123456789", jt.etiqueta)
        val tombstone = migrado.encomendas.first { it.tombstone }
        assertEquals("CORREIOS", tombstone.transportadora)
        assertEquals("", tombstone.etiqueta)
    }

    @Test
    fun `transportadora desconhecida na migracao cai para CORREIOS`() {
        val v1 = """
            {
              "schemaVersion": 1,
              "encomendas": [
                {"codigo":"AA123456789BR","transportadora":"fedex","etiqueta":"Caixa","updatedAt":"2026-09-01T10:00:00Z"}
              ],
              "preferencias": []
            }
        """.trimIndent()

        val migrado = SchemaBoxpace.migrarParaSchemaAtual(SchemaBoxpace.decodificar(v1))

        assertEquals("CORREIOS", migrado.encomendas.single().transportadora)
        assertEquals("Caixa", migrado.encomendas.single().etiqueta)
    }

    @Test
    fun `arquivo ja atual nao e re-migrado`() {
        val v2 = SchemaBoxpace.codificar(
            BoxpaceArquivo(
                schemaVersion = 2,
                encomendas = listOf(SchemaBoxpace.encomendaParaRegistro(encomenda(etiqueta = "Fone"))),
            ),
        )

        val migrado = SchemaBoxpace.migrarParaSchemaAtual(SchemaBoxpace.decodificar(v2))

        assertEquals(2, migrado.schemaVersion)
        assertEquals("Fone", migrado.encomendas.single().etiqueta)
        assertEquals("CORREIOS", migrado.encomendas.single().transportadora)
    }

    @Test
    fun `registroParaEncomenda aceita nome enum do canonico v2`() {
        val v2 = """
            {
              "schemaVersion": 2,
              "encomendas": [
                {"codigo":"AA123456789BR","transportadora":"CORREIOS","etiqueta":"Fone",
                 "updatedAt":"2026-09-01T10:00:00Z"}
              ],
              "preferencias": []
            }
        """.trimIndent()

        val dominio = SchemaBoxpace.registroParaEncomenda(SchemaBoxpace.decodificar(v2).encomendas.single())

        assertEquals(Transportadora.CORREIOS, dominio.transportadora)
        assertEquals("Fone", dominio.etiqueta)
        assertEquals("correios:AA123456789BR", dominio.id)
    }

    // --- dedup idempotente de eventos por data+descricao+unidade ---

    @Test
    fun `encomendaParaRegistro deduplica eventos mantendo ultima copia e ordem`() {
        val original = Encomenda(
            id = "correios:AA123456789BR",
            codigo = "AA123456789BR",
            transportadora = Transportadora.CORREIOS,
            etiqueta = "Fone",
            ultimoStatus = "postado",
            eventos = listOf(
                Evento("2026-09-01T10:00:00", "Objeto postado", cidade = "SP", uf = "SP", unidade = "AGF Centro"),
                Evento("2026-09-01T12:00:00", "Em trânsito", cidade = "Campinas", uf = "SP", unidade = "CTE Campinas"),
                Evento("2026-09-01T10:00:00", "Objeto postado", cidade = "São Paulo", uf = "SP", unidade = "AGF Centro"),
                Evento("2026-09-01T10:00:00", "Objeto postado", cidade = "SP", uf = "SP", unidade = "AGF Oeste"),
            ),
            criadaEm = "2026-09-01T09:00:00Z",
            atualizadaEm = "2026-09-01T13:00:00Z",
        )

        val eventos = SchemaBoxpace.encomendaParaRegistro(original).eventos

        // a duplicata (mesma data+descricao+unidade) preserva a posição da primeira
        // ocorrência mantendo o VALOR da última cópia (cidade "São Paulo");
        // unidade diferente é evento distinto e permanece
        assertEquals(3, eventos.size)
        assertEquals("Objeto postado", eventos[0].descricao)
        assertEquals("AGF Centro", eventos[0].unidade)
        assertEquals("São Paulo", eventos[0].cidade)
        assertEquals("Em trânsito", eventos[1].descricao)
        assertEquals("AGF Oeste", eventos[2].unidade)
    }

    @Test
    fun `maisRecente compara ISO-8601`() {
        assertTrue(MergeRegistros.maisRecente("2026-09-01T12:00:00Z", "2026-09-01T09:00:00Z"))
        assertFalse(MergeRegistros.maisRecente("2026-09-01T09:00:00Z", "2026-09-01T12:00:00Z"))
    }
}
