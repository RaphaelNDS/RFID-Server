package org.example.controller

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class RotasAntigasColetorControllerTest {

    private class RelogioDeTeste(var agora: Instant = Instant.parse("2026-10-13T10:00:00Z")) : Clock() {
        override fun instant() = agora
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?) = this
    }

    private val relogio = RelogioDeTeste()
    private val controller = RotasAntigasColetorController(relogio)

    private val logger = LoggerFactory.getLogger(RotasAntigasColetorController::class.java) as Logger
    private val avisos = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun capturarLog() { avisos.start(); logger.addAppender(avisos) }

    @AfterEach
    fun soltarLog() { logger.detachAppender(avisos) }

    private fun chamar(ip: String = "10.0.0.5", rota: String = "/api/tags/ping") =
        controller.rotaRemovida(MockHttpServletRequest("GET", rota).apply { remoteAddr = ip })

    @Test
    fun `responde 410 com a mensagem`() {
        val resposta = chamar()

        assertThat(resposta.statusCode.value()).isEqualTo(410)
        assertThat(resposta.body!!["mensagem"]).isEqualTo(RotasAntigasColetorController.MENSAGEM)
    }

    @Test
    fun `avisa no log uma vez por hora para o mesmo coletor e rota`() {
        repeat(30) { chamar() } // app antigo insistindo

        relogio.agora = relogio.agora.plus(Duration.ofMinutes(59))
        chamar()
        assertThat(avisos.list).hasSize(1)

        relogio.agora = relogio.agora.plus(Duration.ofMinutes(2))
        chamar()
        assertThat(avisos.list).hasSize(2)
    }

    @Test
    fun `coletores e rotas diferentes geram avisos separados`() {
        chamar(ip = "10.0.0.5")
        chamar(ip = "10.0.0.6")
        chamar(ip = "10.0.0.5", rota = "/api/rfid/ler")

        assertThat(avisos.list).hasSize(3)
    }

    @Test
    fun `aviso diz o que fazer e de onde veio`() {
        chamar(ip = "10.0.0.7")

        assertThat(avisos.list.single().formattedMessage)
            .contains("Rota antiga dos coletores chamada: GET /api/tags/ping")
            .contains("IP 10.0.0.7")
            .contains("atualize o app do coletor")
    }
}
