package org.example.service

import org.example.model.LeituraAndroidEntity
import org.slf4j.LoggerFactory
import org.springframework.http.codec.ServerSentEvent
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.SignalType
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Distribui as leituras recebidas do Android para as telas abertas (SSE).
 */
@Service
class LeituraEventosService {

    private val log = LoggerFactory.getLogger(javaClass)

    // directBestEffort: não acumula eventos quando não há ninguém ouvindo e,
    // principalmente, NÃO se encerra quando o último cliente desconecta
    private val leituras = Sinks.many().multicast().directBestEffort<LeituraAndroidEntity>()

    private val conexoesAtivas = AtomicInteger()

    // tryEmitNext não pode ser chamado por duas threads ao mesmo tempo (o evento seria descartado)
    @Synchronized
    fun publicar(leitura: LeituraAndroidEntity) {
        val resultado = leituras.tryEmitNext(leitura)

        if (resultado.isFailure && resultado != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            log.warn("Falha ao enviar a leitura da tag {} para as telas em tempo real: {}", leitura.tag, resultado)
        }
    }

    fun leituras(): Flux<LeituraAndroidEntity> = leituras.asFlux()

    /** Avisos para o dashboard recarregar: no máximo um a cada [INTERVALO_DASHBOARD]. */
    fun avisosDashboard(): Flux<String> =
        leituras.asFlux()
            .sample(INTERVALO_DASHBOARD)
            .map { "update" }

    /**
     * Transforma um fluxo em SSE com heartbeat periódico e logs de conexão.
     * O heartbeat é um comentário SSE (o navegador ignora); ele serve para manter proxies abertos
     * e para descobrir conexões mortas, já que só uma escrita falha revela que o cliente saiu.
     */
    fun <T : Any> comoSse(canal: String, eventos: Flux<T>): Flux<ServerSentEvent<T>> {
        val usuario = SecurityContextHolder.getContext().authentication?.name ?: "anônimo"

        val heartbeat = Flux.interval(HEARTBEAT)
            .map { ServerSentEvent.builder<T>().comment("ping").build() }

        return Flux.merge(eventos.map { ServerSentEvent.builder(it).build() }, heartbeat)
            .doOnSubscribe {
                log.info(
                    "Tela conectada ao canal '{}' em tempo real: {} ({} conexão(ões) ativa(s))",
                    canal, usuario, conexoesAtivas.incrementAndGet()
                )
            }
            .doFinally { sinal ->
                log.info(
                    "Tela desconectada do canal '{}': {} ({}; {} conexão(ões) ativa(s))",
                    canal, usuario, motivo(sinal), conexoesAtivas.decrementAndGet()
                )
            }
    }

    private fun motivo(sinal: SignalType) = when (sinal) {
        SignalType.CANCEL -> "conexão fechada pelo navegador"
        SignalType.ON_ERROR -> "erro na conexão"
        SignalType.ON_COMPLETE -> "fluxo encerrado pelo servidor"
        else -> sinal.toString()
    }

    companion object {
        val HEARTBEAT: Duration = Duration.ofSeconds(20)
        val INTERVALO_DASHBOARD: Duration = Duration.ofSeconds(5)
    }
}
