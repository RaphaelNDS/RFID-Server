package org.example.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Quem entrou com senha temporária (gerada pelo ADMIN) ou com senha fora das regras só acessa a troca de senha
 * até criar a nova: qualquer outra tela redireciona para /perfil/senha. A marca fica na sessão (posta por SucessoLoginHandler
 * e tirada por PerfilController depois da troca).
 */
class TrocaSenhaObrigatoriaFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val obrigatoria = request.getSession(false)?.getAttribute(ATRIBUTO_SESSAO) == true
        val caminho = request.requestURI.removePrefix(request.contextPath)

        if (obrigatoria && LIBERADOS.none { caminho == it || caminho.startsWith("$it/") }) {
            // O motivo (?temporaria ou ?fraca) escolhe o aviso da tela de troca
            val motivo = request.getSession(false)?.getAttribute(ATRIBUTO_MOTIVO) as? String ?: MOTIVO_TEMPORARIA
            response.sendRedirect("${request.contextPath}/perfil/senha?$motivo")
            return
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val ATRIBUTO_SESSAO = "RFID_TROCA_SENHA_OBRIGATORIA"
        const val ATRIBUTO_MOTIVO = "RFID_TROCA_SENHA_MOTIVO"
        const val MOTIVO_TEMPORARIA = "temporaria"
        const val MOTIVO_FRACA = "fraca"

        // A própria troca, sair, e o que a tela precisa para aparecer (estilos, ícones, scripts)
        private val LIBERADOS = listOf("/perfil/senha", "/logout", "/css", "/js", "/img", "/images", "/webjars", "/error", "/favicon.ico")
    }
}
