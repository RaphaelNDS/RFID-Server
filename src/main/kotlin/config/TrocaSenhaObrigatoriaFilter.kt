package org.example.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Quem entrou com senha temporária (gerada pelo ADMIN) só acessa a troca de senha até criar a nova:
 * qualquer outra tela redireciona para /perfil/senha. A marca fica na sessão (posta por SucessoLoginHandler
 * e tirada por PerfilController depois da troca).
 */
class TrocaSenhaObrigatoriaFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val obrigatoria = request.getSession(false)?.getAttribute(ATRIBUTO_SESSAO) == true
        val caminho = request.requestURI.removePrefix(request.contextPath)

        if (obrigatoria && LIBERADOS.none { caminho == it || caminho.startsWith("$it/") }) {
            response.sendRedirect("${request.contextPath}/perfil/senha?temporaria")
            return
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val ATRIBUTO_SESSAO = "RFID_TROCA_SENHA_OBRIGATORIA"

        // A própria troca, sair, e o que a tela precisa para aparecer (estilos, ícones, scripts)
        private val LIBERADOS = listOf("/perfil/senha", "/logout", "/css", "/js", "/img", "/images", "/webjars", "/error", "/favicon.ico")
    }
}
