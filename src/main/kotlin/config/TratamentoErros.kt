package org.example.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.ModelAndView
import org.springframework.http.converter.HttpMessageNotReadableException
import java.time.format.DateTimeParseException
import java.util.UUID

/** Resultado da tradução de uma exceção em resposta para o usuário. */
data class ErroTratado(val status: HttpStatus, val mensagem: String, val codigo: String? = null)

/**
 * Converte exceções em respostas amigáveis (em vez da página branca de erro 500)
 * e registra no log o que aconteceu.
 */
object TradutorErros {

    private val log = LoggerFactory.getLogger(TratamentoErros::class.java)

    /**
     * Navegador fechou a conexão antes do fim da resposta (no SSE do dashboard, sempre que a tela é
     * fechada ou recarregada: a falha só aparece na próxima escrita). Não é erro do servidor nem há
     * para quem responder; os tratamentos abaixo encerram a requisição em silêncio. Se a exceção
     * voltasse para o Spring, o Tomcat registraria um ERROR com stack trace a cada tela fechada.
     */
    fun clienteDesconectou(ex: Exception, request: HttpServletRequest): Boolean {
        if (ex !is java.io.IOException) return false

        log.debug("Cliente desconectou durante {} {}: {}", request.method, request.requestURI, ex.message)
        return true
    }

    fun traduzir(ex: Exception, request: HttpServletRequest, respostaJaEnviada: Boolean): ErroTratado {

        // Acesso negado e falta de login ficam com o Spring Security (403 / tela de login)
        if (ex is AccessDeniedException || ex is AuthenticationException) throw ex

        // Navegador fechou a conexão (comum em SSE) ou a resposta já começou a ser enviada:
        // não há como mostrar uma página de erro, então deixa o Spring encerrar a requisição
        if (ex is java.io.IOException || respostaJaEnviada) throw ex

        val tratado = when (ex) {
            is IllegalArgumentException ->
                ErroTratado(HttpStatus.BAD_REQUEST, ex.message ?: "Dados inválidos")

            is DateTimeParseException ->
                ErroTratado(HttpStatus.BAD_REQUEST, "Data inválida: '${ex.parsedString}'. Use o formato AAAA-MM-DD")

            is MissingServletRequestParameterException ->
                ErroTratado(HttpStatus.BAD_REQUEST, "Campo obrigatório não informado: ${ex.parameterName}")

            is MethodArgumentTypeMismatchException ->
                ErroTratado(HttpStatus.BAD_REQUEST, "Valor inválido para '${ex.name}': ${ex.value}")

            is HttpMessageNotReadableException ->
                ErroTratado(HttpStatus.BAD_REQUEST, "Corpo da requisição inválido ou com campos obrigatórios faltando")

            is IllegalStateException ->
                ErroTratado(HttpStatus.CONFLICT, ex.message ?: "Operação não permitida no estado atual")

            is DataIntegrityViolationException ->
                ErroTratado(HttpStatus.CONFLICT, "Registro duplicado ou ainda em uso por outro cadastro")

            is NoSuchElementException ->
                ErroTratado(HttpStatus.NOT_FOUND, "Registro não encontrado")

            // Exceções do próprio Spring que já sabem o status (404, 405, 415...)
            is ErrorResponse ->
                ErroTratado(
                    HttpStatus.resolve(ex.statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.body.detail ?: "Requisição inválida"
                )

            else -> ErroTratado(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Ocorreu um erro inesperado. Informe o código ao suporte.",
                codigo = UUID.randomUUID().toString().substring(0, 8)
            )
        }

        registrar(ex, tratado, request)
        return tratado
    }

    private fun registrar(ex: Exception, erro: ErroTratado, request: HttpServletRequest) {
        val usuario = SecurityContextHolder.getContext().authentication?.name ?: "anônimo"
        val rota = "${request.method} ${request.requestURI}"

        when {
            erro.codigo != null ->
                log.error("Erro inesperado [código {}] em {} (usuário {})", erro.codigo, rota, usuario, ex)

            ex is DataIntegrityViolationException ->
                log.warn(
                    "Violação de integridade no banco em {} (usuário {}): {}",
                    rota, usuario, ex.mostSpecificCause.message?.lineSequence()?.firstOrNull()
                )

            else ->
                log.warn("Requisição recusada ({}) em {} (usuário {}): {}", erro.status.value(), rota, usuario, erro.mensagem)
        }
    }
}

/** APIs (@RestController): responde JSON. Tem prioridade sobre o tratamento das telas. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(annotations = [RestController::class])
class TratamentoErrosApi {

    // null = requisição encerrada sem corpo (cliente já desconectou)
    @ExceptionHandler(Exception::class)
    fun tratar(ex: Exception, request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Map<String, Any?>>? {
        if (TradutorErros.clienteDesconectou(ex, request)) return null

        val erro = TradutorErros.traduzir(ex, request, response.isCommitted)

        return ResponseEntity.status(erro.status).body(
            mapOf(
                "status" to erro.status.value(),
                "mensagem" to erro.mensagem,
                "codigo" to erro.codigo,
                "caminho" to request.requestURI
            )
        )
    }
}

/** Telas (@Controller): mostra a página de erro amigável (templates/error.html). */
@ControllerAdvice(annotations = [Controller::class])
class TratamentoErros {

    @ExceptionHandler(Exception::class)
    fun tratar(ex: Exception, request: HttpServletRequest, response: HttpServletResponse): ModelAndView {
        // ModelAndView vazio = requisição encerrada sem página (cliente já desconectou)
        if (TradutorErros.clienteDesconectou(ex, request)) return ModelAndView()

        val erro = TradutorErros.traduzir(ex, request, response.isCommitted)

        return ModelAndView("error", erro.status).apply {
            addObject("status", erro.status.value())
            addObject("mensagem", erro.mensagem)
            addObject("codigo", erro.codigo)
        }
    }
}
