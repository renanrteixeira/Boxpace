package com.boxpace.data.cloud

import com.boxpace.domain.DeltaPendente
import com.boxpace.domain.Encomenda
import com.boxpace.domain.Preferencias
import com.boxpace.domain.Transportadora
import java.time.Instant

/**
 * Merge LWW (last-write-wins) **por registro** entre o canônico do Drive e as
 * mutações locais (deltas pendentes do Room) — AD-SYNC-5.
 *
 * Função pura, usada igualmente em sync e promoção (AD-SYNC-5A): na promoção o
 * canônico é ∅ (importa tudo), o tombstone é processado da mesma forma. Merge
 * por **campo** é proibido.
 *
 * Identidade por registro:
 * - Encomenda → `transportadora:código` (via [DeltaPendente.alvoId]).
 * - Preferência → chave `preferencias:<nome>`.
 *
 * Vence o `updatedAt`/`criadoEm` mais recente (ISO-8601 sortable).
 */
object MergeRegistros {

    /**
     * Aplica [deltas] sobre [canonico], devolvendo um novo canônico reconciliado.
     * Deltas com timestamp estritamente mais recente que o canônico vencem;
     * caso contrário o canônico é mantido (nunca substituir dados locais cegamente).
     */
    fun aplicarDeltas(canonico: BoxpaceArquivo, deltas: List<DeltaPendente>): BoxpaceArquivo {
        val encomendas = LinkedHashMap<String, RegistroEncomenda>()
        canonico.encomendas.forEach { encomendas[identidadeDe(it)] = it }

        val preferencias = LinkedHashMap<String, RegistroPreferencia>()
        canonico.preferencias.forEach { preferencias[it.chave] = it }

        deltas.forEach { delta ->
            when (delta) {
                is DeltaPendente.Salvar -> {
                    val registro = SchemaBoxpace.encomendaParaRegistro(delta.encomenda)
                        .copy(updatedAt = delta.criadoEm)
                    // identidade a partir do registro normalizado (CORREIOS|JT):
                    // delta.alvoId traz o scraperId minúsculo (id do domínio)
                    upsertEncomenda(encomendas, identidadeDe(registro), registro)
                }

                is DeltaPendente.Excluir -> {
                    val (scraperId, codigo) = alvoIdPara(delta.alvoId)
                    // transportadora desconhecida/ausente nunca descarta o tombstone:
                    // cai para CORREIOS (mesmo fallback do repo) — A-4.
                    val sm = Transportadora.fromScraperId(scraperId) ?: Transportadora.CORREIOS
                    val tombstone = SchemaBoxpace.tombstoneParaRegistro(codigo, sm, delta.criadoEm)
                    upsertEncomenda(encomendas, identidadeDe(tombstone), tombstone)
                }

                is DeltaPendente.SalvarPreferencia -> {
                    // reuso do mapper (nota: `updatedAt == criadoEm` na injeção) — F-3
                    val novo = preferenciaParaRegistro(delta.alvoId, delta.preferencias)
                    upsertPreferencia(preferencias, novo)
                }
            }
        }

        return BoxpaceArquivo(
            schemaVersion = canonico.schemaVersion,
            encomendas = encomendas.values.toList(),
            preferencias = preferencias.values.toList(),
        )
    }

    /** Merge LWW entre dois canônicos (usado quando canônico presente = sync). */
    fun mergeArquivos(a: BoxpaceArquivo, b: BoxpaceArquivo): BoxpaceArquivo {
        val encomendas = LinkedHashMap<String, RegistroEncomenda>()
        a.encomendas.forEach { encomendas[identidadeDe(it)] = it }
        b.encomendas.forEach { upsertEncomenda(encomendas, identidadeDe(it), it) }

        val preferencias = LinkedHashMap<String, RegistroPreferencia>()
        a.preferencias.forEach { preferencias[it.chave] = it }
        b.preferencias.forEach { upsertPreferencia(preferencias, it) }

        return BoxpaceArquivo(
            schemaVersion = maxOf(a.schemaVersion, b.schemaVersion),
            encomendas = encomendas.values.toList(),
            preferencias = preferencias.values.toList(),
        )
    }

    /** Promoção: importa os registros locais (atual do Room) + preferências locais. Canônico tratado como ∅. */
    fun promover(
        encomendas: List<Encomenda>,
        preferencias: List<RegistroPreferencia> = emptyList(),
    ): BoxpaceArquivo {
        val mapa = LinkedHashMap<String, RegistroEncomenda>()
        encomendas.map { SchemaBoxpace.encomendaParaRegistro(it) }
            .forEach { upsertEncomenda(mapa, identidadeDe(it), it) }
        return BoxpaceArquivo(
            schemaVersion = SchemaBoxpace.SCHEMA_VERSION,
            encomendas = mapa.values.toList(),
            preferencias = preferencias,
        )
    }

    /**
     * Combina as preferências locais vivas sobre [canonico] com LWW **estrito**
     * (B-3): cada registro local só substitui o atual se for estritamente mais
     * recente — a preferência ativa do aparelho nunca regride.
     */
    fun combinarPreferenciasVivas(
        canonico: BoxpaceArquivo,
        locais: List<RegistroPreferencia>,
    ): BoxpaceArquivo {
        val preferencias = LinkedHashMap<String, RegistroPreferencia>()
        canonico.preferencias.forEach { preferencias[it.chave] = it }
        locais.forEach { upsertPreferencia(preferencias, it) }
        return canonico.copy(preferencias = preferencias.values.toList())
    }

    /** Preferência (repositório de preferências) → registro canônico, LWW por [Preferencias.updatedAt]. */
    fun preferenciaParaRegistro(chave: String, preferencias: Preferencias): RegistroPreferencia =
        RegistroPreferencia(chave = chave, valor = preferencias.tema.id, updatedAt = preferencias.updatedAt)

    // --- helpers ---

    private fun identidadeDe(registro: RegistroEncomenda): String =
        "${registro.transportadora}:${registro.codigo}"

    private fun upsertEncomenda(
        mapa: MutableMap<String, RegistroEncomenda>,
        identidade: String,
        registro: RegistroEncomenda,
    ) {
        val atual = mapa[identidade]
        mapa[identidade] = if (atual == null || maisRecente(registro.updatedAt, atual.updatedAt)) registro else atual
    }

    private fun upsertPreferencia(mapa: MutableMap<String, RegistroPreferencia>, nova: RegistroPreferencia) {
        val atual = mapa[nova.chave]
        mapa[nova.chave] = if (atual == null || maisRecente(nova.updatedAt, atual.updatedAt)) nova else atual
    }

    private fun alvoIdPara(alvoId: String): Pair<String, String> {
        val idx = alvoId.indexOf(':')
        if (idx < 0) return alvoId to alvoId
        return alvoId.substring(0, idx) to alvoId.substring(idx + 1)
    }

    /** ISO-8601 é sortable; perdoa formatos divergentes caindo para comparação lexicográfica. */
    fun maisRecente(a: String, b: String): Boolean {
        val aMillis = a.parseMillis()
        val bMillis = b.parseMillis()
        return if (aMillis != null && bMillis != null) aMillis > bMillis else a > b
    }

    private fun String.parseMillis(): Long? = try {
        Instant.parse(this).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}
