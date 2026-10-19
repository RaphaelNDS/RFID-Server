package org.example.config

import jakarta.servlet.http.HttpServletRequest
import org.aopalliance.intercept.MethodInvocation
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.authorization.event.AuthorizationDeniedEvent
import org.springframework.security.web.authentication.WebAuthenticationDetails
import org.springframework.stereotype.Component

@Component
class SecurityEventLogger {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun loginComSucesso(event: AuthenticationSuccessEvent) {
        log.info("Login realizado: {} (IP: {})", event.authentication.name, ip(event.authentication.details))
    }

    @EventListener
    fun loginComFalha(event: AbstractAuthenticationFailureEvent) {
        log.warn(
            "Falha de login para '{}' (IP: {}): {}",
            event.authentication.name, ip(event.authentication.details), event.exception.message
        )
    }

    @EventListener
    fun acessoNegado(event: AuthorizationDeniedEvent<*>) {
        val auth = event.authentication.get()

        // Usuário não logado é só redirecionado para o login, não vale um aviso
        if (auth == null || auth is AnonymousAuthenticationToken) return

        val recurso = when (val alvo = event.source) {
            is HttpServletRequest -> "${alvo.method} ${alvo.requestURI}"
            is MethodInvocation ->
                "${alvo.method.declaringClass.simpleName}.${alvo.method.name}()"
            else -> alvo.toString()
        }

        log.warn("Acesso negado para o usuário {} em {}", auth.name, recurso)
    }

    private fun ip(details: Any?): String =
        (details as? WebAuthenticationDetails)?.remoteAddress ?: "desconhecido"
}
