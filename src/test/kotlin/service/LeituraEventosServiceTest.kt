package org.example.service

import org.example.model.LeituraAndroidEntity
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier
import java.time.Duration

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
}
