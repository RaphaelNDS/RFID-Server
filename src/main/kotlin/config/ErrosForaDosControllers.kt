package org.example.config

import org.slf4j.LoggerFactory
import org.springframework.boot.web.error.ErrorAttributeOptions
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.context.request.WebRequest

/**
 * Registra no log os erros que não passam pelos controllers e por isso não chegam ao
 * TratamentoErros: principalmente endereços inexistentes (404), como páginas removidas
 * ou links antigos. Substitui o DefaultErrorAttributes padrão do Spring Boot.
 */
@Component
class ErrosForaDosControllers : DefaultErrorAttributes() {

    private val log = LoggerFactory.getLogger(TratamentoErros::class.java)

    override fun getErrorAttributes(webRequest: WebRequest, options: ErrorAttributeOptions): MutableMap<String, Any> {
        val atributos = super.getErrorAttributes(webRequest, options)

        val status = atributos["status"] as? Int
        val caminho = atributos["path"] ?: "?"
        val usuario = SecurityContextHolder.getContext().authentication
            ?.takeUnless { it is AnonymousAuthenticationToken }?.name ?: "anônimo"

        // 403 já é registrado pelo SecurityEventLogger; aqui só o que ninguém mais registra
        when {
            status == 404 ->
                log.warn("Endereço inexistente acessado: {} (usuário {})", caminho, usuario)

            status != null && status >= 500 ->
                log.error("Erro {} fora dos controllers em {} (usuário {})", status, caminho, usuario, getError(webRequest))
        }

        return atributos
    }
}
