package org.example.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock

/**
 * Guarda na sessão web o instante da última requisição feita pela pessoa (abrir uma tela, filtrar, salvar...).
 *
 * A conexão em tempo real do dashboard (SSE) NÃO conta: o navegador a reabre sozinho a cada ~30 s, e isso renovava
 * a sessão para sempre numa aba esquecida aberta. O LeituraEventosService usa este instante para encerrar a
 * conexão e a sessão depois de server.servlet.session.timeout sem uso de verdade.
 *
 * Roda depois da segurança (filtro comum do servlet) e só mexe em sessão que já existe; a API dos coletores não
 * tem sessão.
 */
@Component
class InteracaoWebFilter(private val relogio: Clock) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (!ehTempoReal(request)) {
            request.getSession(false)?.let { sessao ->
                runCatching { sessao.setAttribute(ULTIMA_INTERACAO, relogio.millis()) } // sessão invalidada no meio: ignora
            }
        }
        chain.doFilter(request, response)
    }

    companion object {
        /** Atributo da sessão: instante (epoch em ms) da última requisição que não foi a conexão em tempo real. */
        const val ULTIMA_INTERACAO = "rfid.ultimaInteracao"

        /** Conexão SSE: o EventSource do navegador sempre pede text/event-stream. */
        fun ehTempoReal(request: HttpServletRequest): Boolean =
            request.getHeader("Accept")?.contains(MediaType.TEXT_EVENT_STREAM_VALUE) == true
    }
}
