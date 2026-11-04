package org.example.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Envio de arquivo (multipart) só onde existe: o APK do app, no POST de /admin/app, por um ADMIN logado.
 *
 * Sem este filtro, o CsrfFilter do Spring Security procura o campo _csrf do formulário com getParameter(), e o
 * Tomcat lê o multipart inteiro (até 101 MB, gravando em disco) ANTES de qualquer conferência de permissão: qualquer
 * pessoa na rede, sem login, podia encher o disco temporário mandando arquivos grandes para /login ou outra rota.
 * Este filtro roda antes do CsrfFilter (o login da sessão já está carregado) e recusa sem ler o corpo.
 *
 * [permitido] = null: nenhuma rota aceita arquivo (API dos coletores).
 */
class EnvioArquivoFilter(private val permitido: String? = "/admin/app") : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (!ehEnvioDeArquivo(request) || liberado(request)) {
            chain.doFilter(request, response)
            return
        }

        val quem = SecurityContextHolder.getContext().authentication?.takeIf { it.isAuthenticated }?.name ?: "anônimo"
        log.warn("Envio de arquivo recusado antes de ler o corpo: {} {} ({} bytes informados) por {} (IP {})",
            request.method, request.requestURI, request.contentLengthLong, quem, request.remoteAddr)

        // Avisa que a conexão vai fechar: o Tomcat não lê o resto do corpo
        response.setHeader("Connection", "close")
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write("""{"status":403,"mensagem":"Envio de arquivo não permitido aqui"}""")
    }

    private fun ehEnvioDeArquivo(request: HttpServletRequest) =
        request.contentType?.lowercase()?.startsWith("multipart/") == true

    private fun liberado(request: HttpServletRequest): Boolean {
        val caminho = permitido ?: return false
        if (request.method != "POST" || request.requestURI.removePrefix(request.contextPath) != caminho) return false
        val auth = SecurityContextHolder.getContext().authentication ?: return false
        return auth.isAuthenticated && auth.authorities.any { it.authority == "ROLE_ADMIN" }
    }
}
