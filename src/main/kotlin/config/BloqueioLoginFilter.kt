package org.example.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.example.service.TentativasLoginService
import org.example.util.EmailLogin
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Barra o POST /login de email ou IP bloqueado ANTES de conferir a senha.
 * Assim, durante o bloqueio, nem a senha certa entra (o atacante não consegue continuar testando).
 *
 * Também recusa, como uma falha de login comum, email maior que [EmailLogin.TAMANHO_MAXIMO]: nenhum email cadastrado
 * passa disso, e um campo de vários MB iria ao banco, ao log e aos contadores de tentativas.
 *
 * Não é @Component de propósito: é registrado só na cadeia de segurança das telas (SecurityConfig).
 */
class BloqueioLoginFilter(private val tentativas: TentativasLoginService) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    // Usa a URL (e não servletPath), que não depende de como o servlet está mapeado
    override fun shouldNotFilter(request: HttpServletRequest) =
        !(request.method == "POST" && request.requestURI.removePrefix(request.contextPath) == "/login")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val digitado = request.getParameter("username")
        // Forma curta e sem caracteres de controle: é a que vai para o log e para os contadores
        val email = EmailLogin.normalizar(digitado)
        val bloqueio = tentativas.consultarBloqueio(digitado?.let { email }, request.remoteAddr)

        if (bloqueio != null) {
            // Arredonda para cima: "faltam 0 minutos" confundiria o usuário
            val minutos = (bloqueio.restante.toSeconds() + 59) / 60
            // Uma linha por bloqueio: quem insiste durante o bloqueio não enche o log
            if (bloqueio.primeiraRecusa) {
                log.warn(
                    "Tentativa de login recusada sem conferir a senha: '{}' (IP {}) está bloqueado por mais {} min " +
                        "(as próximas tentativas durante este bloqueio não são registradas)",
                    email, request.remoteAddr, minutos
                )
            }
            response.sendRedirect("${request.contextPath}/login?bloqueado=$minutos")
            return
        }

        if (EmailLogin.longoDemais(digitado)) {
            // Conta como senha errada: quem repete acaba com a origem bloqueada (e o log para de receber estas linhas)
            log.warn(
                "Login recusado sem conferir a senha: email com {} caracteres (máximo {}; início: '{}', IP {})",
                digitado!!.length, EmailLogin.TAMANHO_MAXIMO, email.take(40), request.remoteAddr
            )
            tentativas.registrarFalha(email, request.remoteAddr)
            response.sendRedirect("${request.contextPath}/login?error")
            return
        }

        chain.doFilter(request, response)
    }
}
