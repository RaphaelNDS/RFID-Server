package org.example.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.security.SecureRandom
import java.util.Base64

/**
 * Gera um nonce por requisição para a Content-Security-Policy das telas.
 *
 * Antes a CSP aceitava qualquer script embutido ('unsafe-inline'): se um dia algum texto escapasse do Thymeleaf
 * (XSS), o script injetado rodaria. Agora só rodam os <script> das próprias telas, que levam
 * th:attr="nonce=${cspNonce}" (atributo da requisição, visível no Thymeleaf), e nenhum onclick="..." no HTML.
 */
class NonceCspFilter : OncePerRequestFilter() {

    private val aleatorio = SecureRandom()

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val bytes = ByteArray(16).also(aleatorio::nextBytes)
        request.setAttribute(ATRIBUTO, Base64.getEncoder().encodeToString(bytes))
        chain.doFilter(request, response)
    }

    companion object {
        const val ATRIBUTO = "cspNonce"

        /** Política das telas com o nonce desta requisição (sem nonce, nenhum script embutido roda). */
        fun politica(nonce: String?): String {
            val scripts = if (nonce.isNullOrBlank()) "'self'" else "'self' 'nonce-$nonce'"
            return "default-src 'self'; script-src $scripts; style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
                "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'self'"
        }
    }
}
