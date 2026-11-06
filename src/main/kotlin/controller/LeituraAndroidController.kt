package org.example.controller

import jakarta.servlet.http.HttpServletRequest
import org.example.config.OperadorFilter
import org.example.security.nomeOperador
import org.example.service.LeituraAndroidService
import org.example.service.SessaoOperadorService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

@RestController
@RequestMapping("/api/android")
class LeituraAndroidController(
    private val leituraService: LeituraAndroidService,
    private val sessoes: SessaoOperadorService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class LeituraRequest(
        val tag: String,
        // Quando o coletor leu a tag (ISO-8601 com fuso, ex.: 2026-10-13T13:03:51-03:00).
        // Leituras feitas offline chegam depois: sem este campo, valeria a hora do envio.
        val lidoEm: OffsetDateTime? = null
    )

    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @PostMapping("/leitura")
    fun receberLeitura(@RequestBody req: LeituraRequest, auth: Authentication, request: HttpServletRequest): ResponseEntity<Any> {
        // A hora da leitura precisa caber na sessão do operador, como no lote: antes qualquer data passada era gravada
        val token = request.getHeader(OperadorFilter.HEADER_SESSAO)?.trim().orEmpty()
        sessoes.conferirHorasDoColetor(token, auth.name, listOf(req.lidoEm))[0]?.let { recusa ->
            log.warn("Leitura da tag {} do coletor {} recusada: lida em {}, fora da sessão do operador ({})",
                req.tag, auth.name, req.lidoEm, recusa.message)
            return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf(
                "status" to 409, "mensagem" to recusa.message, "motivo" to recusa.motivo.name, "caminho" to request.requestURI))
        }
        leituraService.salvarLeitura(req.tag, req.lidoEm, auth.nomeOperador())
        return ResponseEntity.ok().build()
    }
}
