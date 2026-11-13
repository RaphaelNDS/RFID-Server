package org.example.service

import com.sun.net.httpserver.HttpServer
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mail.javamail.JavaMailSender
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Avisos pelo Telegram (1.20.0), contra um servidor HTTP local no lugar do api.telegram.org. */
class TelegramServiceTest {

    private val token = "123456:SEGREDO-do-robo"
    private val recebidas = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    private var resposta: (String) -> Pair<Int, String> = { 200 to """{"ok":true,"result":{}}""" }

    private val servidor = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { troca ->
            val corpo = troca.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            val campos = corpo.split('&').filter { it.contains('=') }.associate {
                val (k, v) = it.split('=', limit = 2); k to URLDecoder.decode(v, StandardCharsets.UTF_8)
            }
            recebidas += troca.requestURI.path to campos
            val (status, json) = resposta(troca.requestURI.path)
            val bytes = json.toByteArray()
            troca.sendResponseHeaders(status, bytes.size.toLong())
            troca.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private val url = "http://127.0.0.1:${servidor.address.port}"

    @AfterEach
    fun parar() = servidor.stop(0)

    @Test
    fun `desligado sem token ou sem conversa`() {
        assertThat(TelegramService("", "123", url).ligado).isFalse()
        assertThat(TelegramService(token, "", url).ligado).isFalse()
        assertThat(TelegramService(token, "", url).configurado).isTrue()
        assertThat(TelegramService(token, " 123 ; -100200, abc ,123", url).conversas).containsExactly("123", "-100200")
    }

    @Test
    fun `manda para todas as conversas`() {
        TelegramService(token, "111,-222", url).enviar("Backup atrasado")
        assertThat(recebidas.map { it.first }.distinct()).containsExactly("/bot$token/sendMessage")
        assertThat(recebidas.map { it.second["chat_id"] }).containsExactly("111", "-222")
        assertThat(recebidas.first().second["text"]).isEqualTo("Backup atrasado")
    }

    @Test
    fun `erro do telegram explica a causa e nao mostra o token`() {
        resposta = { 401 to """{"ok":false,"error_code":401,"description":"Unauthorized"}""" }
        assertThatThrownBy { TelegramService(token, "111", url).enviar("x") }
            .hasMessageContaining("Unauthorized").hasMessageContaining("token errado")
        val foraDoAr = TelegramService(token, "111", "http://127.0.0.1:1")
        assertThatThrownBy { foraDoAr.enviar("x") }.hasMessageContaining("sem resposta").satisfies({
            assertThat(it.message).doesNotContain("SEGREDO")
        })
    }

    @Test
    fun `descobre as conversas que falaram com o robo`() {
        resposta = { 200 to """{"ok":true,"result":[
            {"update_id":1,"message":{"chat":{"id":555,"type":"private","first_name":"Ana","last_name":"Lima"},"text":"oi"}},
            {"update_id":2,"message":{"chat":{"id":555,"type":"private","first_name":"Ana"},"text":"de novo"}},
            {"update_id":3,"my_chat_member":{"chat":{"id":-1009,"type":"supergroup","title":"Expedição"}}}
        ]}""" }
        val lista = TelegramService(token, "", url).descobrirConversas()
        assertThat(lista.map { it.id to it.nome }).containsExactly("555" to "Ana Lima", "-1009" to "Expedição")
    }

    @Test
    fun `o aviso do AlertaService vai pelo telegram mesmo sem email`() {
        val semEmail = mockk<ObjectProvider<JavaMailSender>>(relaxed = true)
        val alertas = AlertaService(semEmail, "", "", "", "", 24, Clock.systemDefaultZone(), TelegramService(token, "777", url))
        assertThat(alertas.ligado).isFalse()
        assertThat(alertas.telegramLigado).isTrue()
        alertas.problema("backup-banco", "Backup do banco com problema", "atrasado há 30 h")
        val limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (recebidas.isEmpty() && System.nanoTime() < limite) Thread.onSpinWait()
        assertThat(recebidas.single().second["text"]).contains("Backup do banco com problema").contains("atrasado há 30 h")
        assertThat(alertas.situacaoTelegram()).startsWith("ligados, para 1 conversa(s)")

        assertThat(alertas.enviarTesteTelegram("ana@x.com").getOrThrow()).isEqualTo(1)
        resposta = { 400 to """{"ok":false,"description":"Bad Request: chat not found"}""" }
        assertThat(alertas.enviarTesteTelegram("ana@x.com").exceptionOrNull()!!.message).contains("chat not found")
        assertThat(alertas.comProblema).isTrue()
    }
}
