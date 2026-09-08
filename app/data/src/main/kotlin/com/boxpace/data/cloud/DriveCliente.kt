package com.boxpace.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Headers
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Arquivo canônico no Drive (App Data Folder). [headRevisionId] é a "version"
 * usada no reconcile (AD-SYNC-9): muda a cada escrita e valida o read-back.
 */
data class DriveArquivo(
    val fileId: String,
    val conteudo: String,
    val headRevisionId: String? = null,
)

/** Resultado de uma chamada ao Drive — distingue 401 (reauth) de demais falhas. */
sealed interface DriveResultado<out T> {
    data class Sucesso<T>(val valor: T) : DriveResultado<T>
    data object NaoAutorizado : DriveResultado<Nothing>
    data object Falha : DriveResultado<Nothing>
}

/**
 * Cliente REST do Drive v3 (App Data Folder) via Ktor. Escopo exclusivo
 * `drive.appdata`; token em memória via [TokenOAuthProvider] (AD-SYNC-3/4).
 */
class DriveCliente(
    private val client: HttpClient,
    private val tokenProvider: TokenOAuthProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** Lista o `boxpace.json` na raiz do App Data Folder (∅ = canônico ausente). */
    suspend fun listarArquivoCanonico(): DriveResultado<ArquivoDriveMetadata?> {
        val token = tokenProvider.atual() ?: return DriveResultado.Falha
        val resposta = try {
            client.get(DRIVE_API.Base) {
                url {
                    parameters.append("spaces", "appDataFolder")
                    parameters.append("q", "name='${SchemaBoxpace.NOME_ARQUIVO}'")
                    parameters.append("fields", "files(id,name,headRevisionId)")
                }
                header(HttpHeaders.Authorization, bearer(token))
            }
        } catch (_: Exception) {
            return DriveResultado.Falha
        }
        return when {
            resposta.status == HttpStatusCode.Unauthorized -> DriveResultado.NaoAutorizado
            !resposta.status.isSuccess() -> DriveResultado.Falha
            else -> {
                val corpo = parse(resposta) ?: return DriveResultado.Falha
                val lista = corpo["files"] as? JsonArray ?: JsonArray(emptyList())
                val primeiro = lista.firstOrNull() as? JsonObject ?: return DriveResultado.Sucesso(null)
                DriveResultado.Sucesso(
                    ArquivoDriveMetadata(
                        id = (primeiro["id"] as? JsonPrimitive)?.content ?: "",
                        name = (primeiro["name"] as? JsonPrimitive)?.content.orEmpty(),
                        headRevisionId = (primeiro["headRevisionId"] as? JsonPrimitive)?.content,
                    ),
                )
            }
        }
    }

    /** Baixa o conteúdo do arquivo (alt=media). */
    suspend fun ler(arquivo: ArquivoDriveMetadata): DriveResultado<ArquivoComConteudo> {
        val token = tokenProvider.atual() ?: return DriveResultado.Falha
        val resposta = try {
            client.get(DRIVE_API.media(arquivo.id)) {
                header(HttpHeaders.Authorization, bearer(token))
            }
        } catch (_: Exception) {
            return DriveResultado.Falha
        }
        if (resposta.status == HttpStatusCode.Unauthorized) return DriveResultado.NaoAutorizado
        if (!resposta.status.isSuccess()) return DriveResultado.Falha
        val texto = try {
            resposta.bodyAsText()
        } catch (_: Exception) {
            return DriveResultado.Falha
        }
        return DriveResultado.Sucesso(ArquivoComConteudo(texto))
    }

    /** Cria o arquivo canônico (uploadType=multipart: metadata + media). */
    suspend fun criar(midia: String): DriveResultado<ArquivoDriveMetadata> {
        val token = tokenProvider.atual() ?: return DriveResultado.Falha
        val resposta = try {
            client.post(DRIVE_API.uploadCriar) {
                header(HttpHeaders.Authorization, bearer(token))
                setBody(multipart(midia))
            }
        } catch (_: Exception) {
            return DriveResultado.Falha
        }
        return when {
            resposta.status == HttpStatusCode.Unauthorized -> DriveResultado.NaoAutorizado
            !resposta.status.isSuccess() -> DriveResultado.Falha
            else -> {
                val id = extrairCampo(resposta, "id")
                if (id == null) DriveResultado.Falha
                else DriveResultado.Sucesso(ArquivoDriveMetadata(id, SchemaBoxpace.NOME_ARQUIVO, null))
            }
        }
    }

    /**
     * Atualiza o conteúdo (uploadType=media) e devolve a nova `headRevisionId`.
     * “Sucesso de escrita” = a versão re-lida após update bate (AD-SYNC-9).
     */
    suspend fun atualizar(arquivo: ArquivoDriveMetadata, midia: String): DriveResultado<String> {
        val token = tokenProvider.atual() ?: return DriveResultado.Falha
        val resposta = try {
            client.patch(DRIVE_API.uploadMedia(arquivo.id)) {
                header(HttpHeaders.Authorization, bearer(token))
                contentType(ContentType.Application.Json)
                setBody(midia)
            }
        } catch (_: Exception) {
            return DriveResultado.Falha
        }
        return when {
            resposta.status == HttpStatusCode.Unauthorized -> DriveResultado.NaoAutorizado
            !resposta.status.isSuccess() -> DriveResultado.Falha
            else -> DriveResultado.Sucesso(
                extrairCampo(resposta, "headRevisionId").orEmpty(),
            )
        }
    }

    // --- helpers ---

    private suspend fun parse(resposta: HttpResponse): JsonObject? = try {
        json.parseToJsonElement(resposta.bodyAsText()).jsonObject
    } catch (_: Exception) {
        null
    }

    private suspend fun extrairCampo(resposta: HttpResponse, campo: String): String? {
        val corpo = parse(resposta) ?: return null
        return (corpo[campo] as? JsonPrimitive)?.content
    }

    private fun multipart(midia: String): MultiPartFormDataContent {
        val metadata = JsonObject(
            mapOf(
                "name" to JsonPrimitive(SchemaBoxpace.NOME_ARQUIVO),
                "parents" to JsonArray(listOf(JsonPrimitive("appDataFolder"))),
            ),
        ).toString()
        return MultiPartFormDataContent(
            formData {
                append(
                    "metadata",
                    metadata,
                    Headers.build { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) },
                )
                append(
                    "file",
                    midia.toByteArray(),
                    Headers.build { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) },
                )
            },
        )
    }

    private fun bearer(token: String) = "Bearer $token"

    private object DRIVE_API {
        const val Base = "https://www.googleapis.com/drive/v3/files"
        fun media(id: String) = "$Base/$id?alt=media"
        const val uploadCriar = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
        fun uploadMedia(id: String) = "https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media&fields=headRevisionId"
    }
}

/** Metadados de um arquivo listado do Drive. */
@Serializable
data class ArquivoDriveMetadata(
    val id: String = "",
    val name: String = "",
    val headRevisionId: String? = null,
)

/** Conteúdo de um arquivo lido. */
data class ArquivoComConteudo(val conteudo: String)
