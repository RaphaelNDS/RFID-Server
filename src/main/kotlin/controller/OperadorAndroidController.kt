package org.example.controller

import jakarta.servlet.http.HttpServletRequest
import org.example.config.OperadorFilter
import org.example.service.LoginRecusadoException
import org.example.service.SessaoCriada
import org.example.service.SessaoOperadorService
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

/**
 * Login do operador no coletor Android (o coletor já se identificou pela chave).
 * O token devolvido vai no header X-Sessao-Operador das próximas requisições.
 */
@RestController
@RequestMapping("/api/android")
class OperadorAndroidController(
    private val sessoes: SessaoOperadorService
) {

    data class LoginRequest(val email: String = "", val senha: String = "")

    @PostMapping("/login")
    fun entrar(@RequestBody req: LoginRequest, auth: Authentication, request: HttpServletRequest): SessaoCriada =
        sessoes.entrar(req.email, req.senha, auth.name, request.remoteAddr)

    @PostMapping("/logout")
    fun sair(request: HttpServletRequest, auth: Authentication): Map<String, String> {
        request.getHeader(OperadorFilter.HEADER_SESSAO)?.takeIf { it.isNotBlank() }?.let { sessoes.sair(it.trim(), auth.name) }
        return mapOf("status" to "ok")
    }

    // Recusa do login (senha errada, bloqueio, sem permissão): status e mensagem próprios, em JSON
    @ExceptionHandler(LoginRecusadoException::class)
    fun loginRecusado(ex: LoginRecusadoException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val resposta = ResponseEntity.status(ex.status)
        ex.retryAfterSegundos?.let { resposta.header(HttpHeaders.RETRY_AFTER, it.toString()) }
        return resposta.body(
            mapOf("status" to ex.status, "mensagem" to ex.message, "caminho" to request.requestURI)
        )
    }
}
