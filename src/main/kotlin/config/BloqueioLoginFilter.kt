package org.example.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.example.service.TentativasLoginService
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Barra o POST /login de email ou IP bloqueado ANTES de conferir a senha.
 * Assim, durante o bloqueio, nem a senha certa entra (o atacante não consegue continuar testando).
 *
 * Não é @Component de propósito: é registrado só na cadeia de segurança das telas (SecurityConfig).
 */
class BloqueioLoginFilter(private val tentativas: TentativasLoginService) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    // Usa a URL (e não servletPath), que não depende de como o servlet está mapeado
    override fun shouldNotFilter(request: HttpServletRequest) =
        !(request.method == "POST" && request.requestURI.removePrefix(request.contextPath) == "/login")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val email = request.getParameter("username")
        val restante = tentativas.tempoRestanteDeBloqueio(email, request.remoteAddr)

        if (restante == null) {
            chain.doFilter(request, response)
            return
        }

        // Arredonda para cima: "faltam 0 minutos" confundiria o usuário
        val minutos = (restante.toSeconds() + 59) / 60
        log.warn(
            "Tentativa de login recusada sem conferir a senha: '{}' (IP {}) está bloqueado por mais {} min",
            email?.trim()?.lowercase(), request.remoteAddr, minutos
        )
        response.sendRedirect("${request.contextPath}/login?bloqueado=$minutos")
    }
}
