package org.example.controller

import jakarta.servlet.http.HttpServletRequest
import org.example.config.OperadorFilter
import org.example.service.LoginRecusadoException
import org.example.service.RecuperacaoSenhaService
import org.example.service.SessaoCriada
import org.example.service.SessaoOperadorService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
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
    private val sessoes: SessaoOperadorService,
    private val recuperacao: RecuperacaoSenhaService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class LoginRequest(val email: String = "", val senha: String = "")

    data class EsqueciSenhaRequest(val email: String = "")

    @PostMapping("/login")
    fun entrar(@RequestBody req: LoginRequest, auth: Authentication, request: HttpServletRequest): SessaoCriada =
        sessoes.entrar(req.email, req.senha, auth.name, request.remoteAddr)

    @PostMapping("/logout")
    fun sair(request: HttpServletRequest, auth: Authentication): Map<String, String> {
        request.getHeader(OperadorFilter.HEADER_SESSAO)?.takeIf { it.isNotBlank() }?.let { sessoes.sair(it.trim(), auth.name) }
        return mapOf("status" to "ok")
    }

    /**
     * "Esqueceu a senha?" da tela de login do coletor: o pedido vai para o ADMIN, em Usuários (o mesmo da web).
     * A resposta é sempre a mesma, exista ou não o email, para a rota não revelar quem tem conta.
     */
    @PostMapping("/esqueci-senha")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun esqueciSenha(@RequestBody req: EsqueciSenhaRequest, auth: Authentication, request: HttpServletRequest): Map<String, String> {
        log.info("Pedido de nova senha recebido pelo coletor {} (IP {})", auth.name, request.remoteAddr)
        // Limite de pedidos por coletor + IP: coletores atrás do mesmo IP (NAT) não travam uns aos outros
        recuperacao.pedir(req.email, "${auth.name}@${request.remoteAddr}")
        return mapOf("status" to "ok", "mensagem" to MENSAGEM_PEDIDO_SENHA)
    }

    // Recusa do login (senha errada, bloqueio, sem permissão, senha temporária): status e mensagem próprios, em JSON
    @ExceptionHandler(LoginRecusadoException::class)
    fun loginRecusado(ex: LoginRecusadoException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val resposta = ResponseEntity.status(ex.status)
        ex.retryAfterSegundos?.let { resposta.header(HttpHeaders.RETRY_AFTER, it.toString()) }
        return resposta.body(
            mapOf("status" to ex.status, "mensagem" to ex.message, "caminho" to request.requestURI)
        )
    }

    companion object {
        const val MENSAGEM_PEDIDO_SENHA =
            "Se o email estiver cadastrado, o pedido foi enviado ao administrador. Ele vai te passar uma senha temporária: " +
                "entre com ela pela web, crie sua senha nova e depois entre no coletor."
    }
}
