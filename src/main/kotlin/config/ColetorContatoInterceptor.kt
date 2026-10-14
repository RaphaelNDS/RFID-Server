package org.example.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.example.security.OperadorNoColetor
import org.example.service.ColetorService
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Registra cada contato ACEITO de um coletor em /api/android (chave válida e, quando exigido, operador válido:
 * os filtros de segurança já rodaram) para a tela Coletores. A versão do app vem nos headers
 * X-Versao-App e X-Versao-Codigo, que são de fora: valor inválido é ignorado, nunca derruba a requisição.
 */
class ColetorContatoInterceptor(private val coletores: ColetorService) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val auth = SecurityContextHolder.getContext().authentication ?: return true
        if (!auth.isAuthenticated) return true

        coletores.registrarContato(
            nome = auth.name,
            ip = request.remoteAddr,
            versaoApp = versaoApp(request.getHeader(HEADER_VERSAO_APP)),
            codigoVersao = codigoVersao(request.getHeader(HEADER_VERSAO_CODIGO)),
            operador = (auth.principal as? OperadorNoColetor)?.operador?.nome
        )
        return true
    }

    companion object {
        const val HEADER_VERSAO_APP = "X-Versao-App"
        const val HEADER_VERSAO_CODIGO = "X-Versao-Codigo"
        private const val TAMANHO_MAXIMO_VERSAO = 50

        /** "1.4.0": só letras, números, ponto, hífen, + e espaço; no máximo 50 caracteres. Fora disso, ignora. */
        fun versaoApp(texto: String?): String? =
            texto?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= TAMANHO_MAXIMO_VERSAO && it.all { c -> c.isLetterOrDigit() || c in ".-+ _" } }

        fun codigoVersao(texto: String?): Int? = texto?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    }
}

@Configuration
class ColetoresWebConfig(private val coletores: ColetorService) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(ColetorContatoInterceptor(coletores)).addPathPatterns("/api/android/**")
    }
}
