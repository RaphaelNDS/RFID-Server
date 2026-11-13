package org.example.controller

import org.example.service.TransferenciaService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Transferência pelo coletor (1.7.0): no inventário, o operador leva para o local do inventário um bem lido que
 * estava em outro. Exige o operador com RFID · Editar, a mesma permissão de transferir na web (o login devolve
 * podeTransferir). Respostas: 200 com o local e a situação atualizados; 404 etiqueta sem cadastro; 409 bem baixado;
 * 400 local desconhecido ou desativado; 403 sem permissão.
 */
@RestController
@RequestMapping("/api/android")
class TransferenciaAndroidController(
    private val service: TransferenciaService,
    // 1.31.0: coletor em treinamento não transfere (409 com o aviso)
    private val treinamento: org.example.service.TreinamentoColetorService? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class PedidoTransferenciaColetor(val local: String? = null, val observacao: String? = null)

    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @PostMapping("/tags/{epc}/transferencia")
    fun transferir(@PathVariable epc: String, @RequestBody pedido: PedidoTransferenciaColetor, auth: Authentication): ResponseEntity<Any> =
        if (treinamento?.emTreinamento(auth.name) == true)
            recusa(HttpStatus.CONFLICT, IllegalStateException(org.example.service.TreinamentoColetorService.AVISO_TRANSFERENCIA), epc, auth)
        else try {
            ResponseEntity.ok<Any>(
                try {
                    service.transferirPeloColetor(epc, pedido.local, pedido.observacao, auth.name)
                } catch (e: org.springframework.dao.OptimisticLockingFailureException) {
                    // Reenvio chegando com o primeiro envio ainda gravando: os dois leram a mesma versão da etiqueta e o
                    // segundo perdeu. De novo, numa transação nova, ele vê o bem já no local e responde jaEstava
                    log.info("Transferência da etiqueta {} pelo coletor {}: envio simultâneo com outro igual; repetindo", epc.trim(), auth.name)
                    service.transferirPeloColetor(epc, pedido.local, pedido.observacao, auth.name)
                }
            )
        } catch (e: NoSuchElementException) {
            recusa(HttpStatus.NOT_FOUND, e, epc, auth)
        } catch (e: IllegalStateException) {
            recusa(HttpStatus.CONFLICT, e, epc, auth)
        } catch (e: IllegalArgumentException) {
            recusa(HttpStatus.BAD_REQUEST, e, epc, auth)
        }

    private fun recusa(status: HttpStatus, e: Exception, epc: String, auth: Authentication): ResponseEntity<Any> {
        log.info("Transferência da etiqueta {} pelo coletor {} recusada ({}): {}", epc.trim(), auth.name, status.value(), e.message)
        return ResponseEntity.status(status).body(mapOf("status" to status.value(), "mensagem" to e.message))
    }
}
