package org.example.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.example.security.UserPrincipal
import org.example.service.PoliticaSenha
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler

/**
 * Depois do login: se a senha usada não atende às regras atuais (senhas criadas antes da regra existir),
 * leva o usuário direto para a troca de senha com um aviso. O login é o único momento em que o sistema
 * vê a senha em texto: no banco só existe o hash.
 */
class SucessoLoginHandler(private val politicaSenha: PoliticaSenha) : SimpleUrlAuthenticationSuccessHandler("/redirect") {

    private val log = LoggerFactory.getLogger(javaClass)

    init {
        isAlwaysUseDefaultTargetUrl = true
    }

    override fun onAuthenticationSuccess(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication) {
        // Senha temporária gerada pelo ADMIN: só libera o sistema depois de criar uma senha nova
        if ((authentication.principal as? UserPrincipal)?.trocarSenha == true) {
            log.info("Usuário '{}' entrou com senha temporária: encaminhado para criar uma senha nova", authentication.name)
            request.getSession(true).setAttribute(TrocaSenhaObrigatoriaFilter.ATRIBUTO_SESSAO, true)
            clearAuthenticationAttributes(request)
            redirectStrategy.sendRedirect(request, response, "/perfil/senha?temporaria")
            return
        }

        val senha = request.getParameter("password").orEmpty()
        val nome = (authentication.principal as? UserPrincipal)?.nome
        val problemas = politicaSenha.problemas(senha, authentication.name, nome)

        if (problemas.isEmpty()) {
            super.onAuthenticationSuccess(request, response, authentication)
            return
        }

        log.warn(
            "Usuário '{}' entrou com senha fora das regras ({}): encaminhado para a troca de senha",
            authentication.name, problemas.joinToString("; ")
        )
        clearAuthenticationAttributes(request)
        redirectStrategy.sendRedirect(request, response, "/perfil/senha?fraca")
    }
}
