package org.example.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Uma conversa que mandou mensagem para o robô (para descobrir o número dela na tela Status). */
data class ConversaTelegram(val id: String, val nome: String, val tipo: String)

/**
 * Avisos pelo Telegram (1.20.0): o mesmo aviso do email (AlertaService) vai também para as conversas configuradas.
 * Liga com o token do robô (rfid.alertas.telegram-token = RFID_TELEGRAM_TOKEN, criado no @BotFather) e pelo menos
 * uma conversa (rfid.alertas.telegram-chats = RFID_TELEGRAM_CHATS, números separados por vírgula). Para achar o
 * número: mande uma mensagem ao robô (ou ponha o robô num grupo) e use "Descobrir conversas" na tela Status.
 *
 * O token nunca vai para o log nem para a tela: nos erros ele é trocado por "***".
 */
@Service
class TelegramService(
    @Value("\${rfid.alertas.telegram-token:}") token: String,
    @Value("\${rfid.alertas.telegram-chats:}") chats: String,
    @Value("\${rfid.alertas.telegram-url:https://api.telegram.org}") url: String
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val token = token.trim()
    private val base = url.trim().trimEnd('/')
    private val json = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    val conversas: List<String> = chats.split(',', ';').map { it.trim() }.filter { it.matches(Regex("-?\\d+")) }.distinct()

    val configurado: Boolean get() = token.isNotEmpty()

    val ligado: Boolean get() = configurado && conversas.isNotEmpty()

    fun motivoDesligado(): String = when {
        !configurado -> "defina o token do robô (RFID_TELEGRAM_TOKEN) e as conversas (RFID_TELEGRAM_CHATS)"
        else -> "defina as conversas em RFID_TELEGRAM_CHATS (use \"Descobrir conversas\" na tela Status)"
    }

    /** Manda [texto] para todas as conversas. Erro de uma conversa não impede as outras; a primeira falha é lançada no fim. */
    fun enviar(texto: String) {
        check(ligado) { motivoDesligado() }
        var falha: Exception? = null
        conversas.forEach { chat ->
            try {
                chamar("sendMessage", mapOf("chat_id" to chat, "text" to texto.take(LIMITE_TEXTO), "disable_web_page_preview" to "true"))
            } catch (e: Exception) {
                log.warn("Aviso pelo Telegram não foi para a conversa {}: {}", chat, e.message)
                if (falha == null) falha = e
            }
        }
        falha?.let { throw it }
    }

    /**
     * As conversas que mandaram mensagem ao robô nas últimas 24 h (getUpdates). Só funciona com o token configurado e
     * sem webhook no robô.
     */
    fun descobrirConversas(): List<ConversaTelegram> {
        check(configurado) { motivoDesligado() }
        val resposta = chamar("getUpdates", emptyMap())
        return resposta.path("result").mapNotNull { u ->
            val chat = listOf("message", "channel_post", "my_chat_member", "edited_message")
                .map { u.path(it).path("chat") }.firstOrNull { !it.isMissingNode } ?: return@mapNotNull null
            val nome = chat.path("title").asText("").ifEmpty {
                listOf(chat.path("first_name").asText(""), chat.path("last_name").asText("")).filter { it.isNotEmpty() }.joinToString(" ")
            }.ifEmpty { chat.path("username").asText("?") }
            ConversaTelegram(chat.path("id").asText(), nome, chat.path("type").asText(""))
        }.distinctBy { it.id }
    }

    private fun chamar(metodo: String, campos: Map<String, String>): com.fasterxml.jackson.databind.JsonNode {
        val corpo = campos.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8)}" }
        val req = HttpRequest.newBuilder(URI.create("$base/bot$token/$metodo"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(corpo))
            .build()
        val resp = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw IllegalStateException("sem resposta do Telegram (${esconder(e.message ?: e.javaClass.simpleName)})")
        }
        val no = runCatching { json.readTree(resp.body()) }.getOrNull()
        if (resp.statusCode() != 200 || no?.path("ok")?.asBoolean() != true) {
            val descricao = no?.path("description")?.asText()?.takeIf { it.isNotEmpty() } ?: "HTTP ${resp.statusCode()}"
            throw IllegalStateException("o Telegram recusou: ${esconder(descricao)}" + when (resp.statusCode()) {
                401, 404 -> " (token errado?)"
                400 -> " (conversa errada, ou ninguém mandou mensagem ao robô?)"
                403 -> " (o robô foi bloqueado ou saiu do grupo)"
                else -> ""
            })
        }
        return no
    }

    private fun esconder(texto: String): String = if (token.isEmpty()) texto else texto.replace(token, "***")

    private companion object {
        /** O Telegram aceita até 4096 caracteres por mensagem. */
        const val LIMITE_TEXTO = 4000
    }
}
