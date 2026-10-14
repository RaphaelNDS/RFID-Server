package org.example.controller

import jakarta.servlet.http.HttpServletRequest
import org.example.config.OperadorFilter
import org.example.security.nomeOperador
import org.example.service.ItemLote
import org.example.service.LeituraLoteService
import org.example.service.ResultadoItemLote
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Várias leituras do coletor numa requisição só, com o resultado de cada uma (LeituraLoteService).
 * Todas as leituras do lote são do mesmo operador (header X-Sessao-Operador); o X-Momento é o da leitura
 * mais recente, e a hora de cada leitura é conferida item a item.
 */
@RestController
@RequestMapping("/api/android")
class LeituraLoteAndroidController(
    private val lotes: LeituraLoteService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class LoteRequest(val itens: List<ItemLote> = emptyList())

    data class LoteResponse(val resultados: List<ResultadoItemLote>)

    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @PostMapping("/leituras/lote")
    fun receber(@RequestBody req: LoteRequest, auth: Authentication, request: HttpServletRequest): ResponseEntity<Any> {
        if (req.itens.size > LeituraLoteService.TAMANHO_MAXIMO_LOTE) {
            log.warn("Lote de {} leituras do coletor {} recusado: o máximo é {}", req.itens.size, auth.name, LeituraLoteService.TAMANHO_MAXIMO_LOTE)
            return ResponseEntity.badRequest().body(
                mapOf(
                    "status" to 400,
                    "mensagem" to "Lote com ${req.itens.size} leituras: o máximo é ${LeituraLoteService.TAMANHO_MAXIMO_LOTE}",
                    "caminho" to request.requestURI
                )
            )
        }

        // O OperadorFilter já validou a sessão (só chega aqui com token válido)
        val token = request.getHeader(OperadorFilter.HEADER_SESSAO).orEmpty().trim()
        return ResponseEntity.ok(LoteResponse(lotes.processar(req.itens, token, auth.name, auth.nomeOperador())))
    }
}
