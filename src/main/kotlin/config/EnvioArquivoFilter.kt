package org.example.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.example.controller.AnexoBemWebController
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Envio de arquivo (multipart) só onde existe: o APK do app, no POST de /admin/app, por um ADMIN logado, e a planilha
 * de etiquetas (1.6.0), no POST de /admin/tags-cadastradas/importar, por quem pode cadastrar etiquetas, e as fotos e
 * anexos do bem (1.9.0), no POST de /admin/etiquetas/{id}/anexos, por quem pode editar etiquetas, e o comprovante de
 * entrega com fotos (1.23.0), no POST de /admin/pedidos/{id}/entregas, por quem pode editar na Locação.
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
        if (permitido == null || request.method != "POST") return false
        val caminho = request.requestURI.removePrefix(request.contextPath)
        val auth = SecurityContextHolder.getContext().authentication?.takeIf { it.isAuthenticated } ?: return false
        val autoridades = auth.authorities.map { it.authority }.toSet()
        return when (caminho) {
            // APK do app: só ADMIN
            permitido -> "ROLE_ADMIN" in autoridades
            // Importação de planilha de etiquetas (1.6.0): quem pode cadastrar etiquetas
            IMPORTACAO_ETIQUETAS -> "ROLE_ADMIN" in autoridades || "RFID_CREATE" in autoridades
            // Fotos e anexos do bem (1.9.0): quem pode editar etiquetas
            else -> (AnexoBemWebController.CAMINHO_ENVIO.matches(caminho) &&
                ("ROLE_ADMIN" in autoridades || "RFID_UPDATE" in autoridades)) ||
                // Comprovante de entrega com fotos (1.23.0): quem pode editar na Locação
                (org.example.controller.EntregaWebController.CAMINHO_ENVIO.matches(caminho) &&
                    ("ROLE_ADMIN" in autoridades || "LOCACAO_UPDATE" in autoridades))
        }
    }

    companion object {
        const val IMPORTACAO_ETIQUETAS = "/admin/tags-cadastradas/importar"
    }
}
