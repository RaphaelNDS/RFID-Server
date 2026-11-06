package org.example.service

import org.assertj.core.api.Assertions.assertThat
import org.example.config.InteracaoWebFilter
import org.example.model.LeituraAndroidEntity
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpSession
import reactor.test.StepVerifier
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class LeituraEventosServiceTest {

    private val service = LeituraEventosService()

    private fun leitura(tag: String) = LeituraAndroidEntity(tag = tag)

    @Test
    fun `quem esta conectado recebe as leituras publicadas`() {
        StepVerifier.create(service.leituras())
            .then { service.publicar(leitura("E1")); service.publicar(leitura("E2")) }
            .expectNextMatches { it.tag == "E1" }
            .expectNextMatches { it.tag == "E2" }
            .thenCancel()
            .verify(Duration.ofSeconds(5))
    }

    @Test
    fun `canal continua funcionando depois que o ultimo cliente desconecta`() {
        // Regressão: com onBackpressureBuffer() o sink se encerrava quando o 1º navegador fechava a aba
        StepVerifier.create(service.leituras())
            .then { service.publicar(leitura("A")) }
            .expectNextCount(1)
            .thenCancel()
            .verify(Duration.ofSeconds(5))

        StepVerifier.create(service.leituras())
            .then { service.publicar(leitura("B")) }
            .expectNextMatches { it.tag == "B" }
            .thenCancel()
            .verify(Duration.ofSeconds(5))
    }

    @Test
    fun `publicar sem ninguem conectado nao falha nem acumula eventos antigos`() {
        service.publicar(leitura("ANTIGA"))

        StepVerifier.create(service.leituras())
            .then { service.publicar(leitura("NOVA")) }
            .expectNextMatches { it.tag == "NOVA" }
            .thenCancel()
            .verify(Duration.ofSeconds(5))
    }

    @Test
    fun `publicacao simultanea de varias threads nao perde leituras`() {
        val total = 200

        StepVerifier.create(service.leituras())
            .then {
                val threads = (1..4).map { t ->
                    Thread { repeat(total / 4) { i -> service.publicar(leitura("T$t-$i")) } }
                }
                threads.forEach { it.start() }
                threads.forEach { it.join() }
            }
            .expectNextCount(total.toLong())
            .thenCancel()
            .verify(Duration.ofSeconds(10))
    }

    @Test
    fun `dashboard recebe um unico aviso para uma rajada de leituras`() {
        StepVerifier.withVirtualTime { service.avisosDashboard() }
            .expectSubscription()
            .then { repeat(30) { service.publicar(leitura("E$it")) } }
            .thenAwait(LeituraEventosService.INTERVALO_DASHBOARD)
            .expectNext("update")
            .thenAwait(LeituraEventosService.INTERVALO_DASHBOARD)
            .expectNoEvent(Duration.ofSeconds(1)) // sem leituras novas, sem aviso
            .thenCancel()
            .verify()
    }

    @Test
    fun `sse envia heartbeat periodico sem dados`() {
        StepVerifier.withVirtualTime { service.comoSse("teste", service.leituras()) }
            .expectSubscription()
            .thenAwait(LeituraEventosService.HEARTBEAT)
            .expectNextMatches { it.comment() == "ping" && it.data() == null }
            .then { service.publicar(leitura("E1")) }
            .expectNextMatches { it.data()?.tag == "E1" }
            .thenCancel()
            .verify()
    }

    @Test
    fun `sse termina no proximo ping quando a sessao web da pessoa foi encerrada`() {
        val registro = org.springframework.security.core.session.SessionRegistryImpl()
        registro.registerNewSession("sessao-1", "ana@x.com")
        val comSessao = LeituraEventosService(registro)

        StepVerifier.withVirtualTime { comSessao.comoSse("leituras", comSessao.leituras(), MockHttpSession(null, "sessao-1")) }
            .expectSubscription()
            .thenAwait(LeituraEventosService.HEARTBEAT)
            .expectNextMatches { it.comment() == "ping" }
            // Usuário desativado (ou rebaixado, ou senha trocada): a sessão web expira
            .then { registro.getSessionInformation("sessao-1")!!.expireNow() }
            .thenAwait(LeituraEventosService.HEARTBEAT)
            .verifyComplete()
    }

    @Test
    fun `sse termina tambem quando a sessao sumiu do registro (logout em outra aba, inatividade)`() {
        val registro = org.springframework.security.core.session.SessionRegistryImpl()
        registro.registerNewSession("sessao-2", "ana@x.com")
        val comSessao = LeituraEventosService(registro)

        StepVerifier.withVirtualTime { comSessao.comoSse("leituras", comSessao.leituras(), MockHttpSession(null, "sessao-2")) }
            .expectSubscription()
            .thenAwait(LeituraEventosService.HEARTBEAT)
            .expectNextMatches { it.comment() == "ping" }
            // expireNow() seguido de outra requisição: o logout invalida a sessão e o registro a apaga
            .then { registro.removeSessionInformation("sessao-2") }
            .thenAwait(LeituraEventosService.HEARTBEAT)
            .verifyComplete()
    }

    // ---------------- aba esquecida aberta: o SSE não renova a sessão ----------------

    /** Relógio que o teste avança na mão (o Flux.interval usa o tempo virtual; a inatividade, este relógio). */
    private class RelogioDeTeste(var agora: Instant = Instant.parse("2026-11-06T10:00:00Z")) : Clock() {
        override fun instant() = agora
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?) = this
    }

    private fun sessaoUsadaEm(instante: Instant, id: String) = MockHttpSession(null, id).apply {
        setAttribute(InteracaoWebFilter.ULTIMA_INTERACAO, instante.toEpochMilli())
    }

    @Test
    fun `sse de quem parou de usar o sistema termina e a sessao e invalidada`() {
        val relogio = RelogioDeTeste()
        val comInatividade = LeituraEventosService(null, relogio, Duration.ofMinutes(30))
        val sessao = sessaoUsadaEm(relogio.agora.minus(Duration.ofMinutes(29)), "sessao-3")

        StepVerifier.withVirtualTime { comInatividade.comoSse("dashboard", comInatividade.leituras(), sessao) }
            .expectSubscription()
            .thenAwait(LeituraEventosService.HEARTBEAT)
            .expectNextMatches { it.comment() == "ping" }
            // Passam 2 min sem nenhuma outra requisição: passou dos 30 min sem uso
            .then { relogio.agora = relogio.agora.plus(Duration.ofMinutes(2)) }
            .thenAwait(LeituraEventosService.HEARTBEAT)
            .verifyComplete()

        assertThat(sessao.isInvalid).isTrue()
    }

    @Test
    fun `sse reaberto depois do tempo sem uso nem conecta`() {
        val relogio = RelogioDeTeste()
        val comInatividade = LeituraEventosService(null, relogio, Duration.ofMinutes(30))
        val sessao = sessaoUsadaEm(relogio.agora.minus(Duration.ofMinutes(31)), "sessao-4")

        StepVerifier.create(comInatividade.comoSse("dashboard", comInatividade.leituras(), sessao))
            .verifyComplete()

        assertThat(sessao.isInvalid).isTrue()
    }

    @Test
    fun `sse de quem esta usando o sistema continua`() {
        val relogio = RelogioDeTeste()
        val comInatividade = LeituraEventosService(null, relogio, Duration.ofMinutes(30))
        val sessao = sessaoUsadaEm(relogio.agora.minus(Duration.ofMinutes(5)), "sessao-5")

        StepVerifier.withVirtualTime { comInatividade.comoSse("dashboard", comInatividade.leituras(), sessao) }
            .expectSubscription()
            .thenAwait(LeituraEventosService.HEARTBEAT)
            .expectNextMatches { it.comment() == "ping" }
            .thenCancel()
            .verify()

        assertThat(sessao.isInvalid).isFalse()
    }
}