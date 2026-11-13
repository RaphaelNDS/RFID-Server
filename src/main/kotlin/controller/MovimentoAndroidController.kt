package org.example.controller

import jakarta.servlet.http.HttpServletRequest
import org.example.config.OperadorFilter
import org.example.model.OrigemMovimento
import org.example.model.TipoMovimento
import org.example.security.nomeOperador
import org.example.service.MovimentoService
import org.example.service.NovoMovimento
import org.example.util.HoraColetor
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * Saída e entrada pelo coletor (2.0.0): o operador lê as etiquetas e manda de uma vez. Exige o operador com
 * Movimentações · Criar (o login devolve podeMovimentar). Os aceitos são movimentados numa movimentação só; os
 * recusados voltam com o motivo, um a um (etiqueta sem cadastro, já fora do estoque na saída, já no estoque na
 * entrada, baixada ou extraviada).
 *
 * Respostas: 200 com o resultado de cada EPC (movimento = null quando nenhum foi aceito); 400 pedido inválido (tipo
 * desconhecido, saída sem destino, lista vazia ou grande demais), com {status, mensagem, caminho}; 401/403 como as
 * outras rotas do coletor.
 */
@RestController
@RequestMapping("/api/android")
class MovimentoAndroidController(
    private val service: MovimentoService,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class MovimentoColetorRequest(
        val tipo: String? = null,
        val destino: String? = null,
        val observacao: String? = null,
        val epcs: List<String>? = null
    )

    data class ResultadoEpc(
        val epc: String,
        val aceito: Boolean,
        val motivo: String?,
        val patrimonio: String?,
        val descricao: String?
    )

    data class MovimentoColetorResponse(
        /** Número da movimentação gravada; null quando nenhum EPC foi aceito. */
        val movimento: Long?,
        val tipo: String,
        val aceitos: Int,
        val recusados: Int,
        val resultados: List<ResultadoEpc>
    )

    @PreAuthorize("@authService.temPermissao('MOVIMENTO','CREATE')")
    @PostMapping("/movimentos")
    fun registrar(@RequestBody req: MovimentoColetorRequest, auth: Authentication, request: HttpServletRequest): ResponseEntity<Any> {
        val tipo = TipoMovimento.de(req.tipo)
            ?: return recusa("Tipo de movimentação inválido: '${req.tipo.orEmpty()}' (use SAIDA ou ENTRADA)", auth, request)
        val epcs = req.epcs.orEmpty()
        val operador = auth.nomeOperador()
        val agora = LocalDateTime.now(relogio)
        // A hora em que o operador fez no coletor (X-Momento), se veio e não está no futuro
        val momento = request.getHeader(OperadorFilter.HEADER_MOMENTO)
            ?.let { runCatching { OffsetDateTime.parse(it.trim()) }.getOrNull() }
            ?.let { HoraColetor.aceita(it, agora) }
        val novo = NovoMovimento(
            tipo = tipo, identificadores = epcs, destino = req.destino, observacao = req.observacao,
            origem = OrigemMovimento.COLETOR, momento = momento,
            registradoPor = operador?.let { "$it (${auth.name})" } ?: auth.name
        )

        val resultado = try {
            try {
                service.registrar(novo, tudoOuNada = false)
            } catch (e: OptimisticLockingFailureException) {
                // Reenvio chegando com o primeiro ainda gravando (ou outra pessoa movimentando o mesmo equipamento):
                // de novo, numa transação nova, o equipamento já aparece movimentado e vem recusado com o motivo
                log.info("{} pelo coletor {}: equipamento movimentado ao mesmo tempo por outro envio; conferindo de novo", tipo.rotulo, auth.name)
                service.registrar(novo, tudoOuNada = false)
            }
        } catch (e: IllegalArgumentException) {
            return recusa(e.message ?: "Pedido inválido", auth, request)
        }

        return ResponseEntity.ok(MovimentoColetorResponse(
            movimento = resultado.movimento?.id,
            tipo = tipo.name,
            aceitos = resultado.aceitos.size,
            recusados = resultado.recusados.size,
            resultados = resultado.itens.map { ResultadoEpc(it.identificador, it.aceito, it.motivo, it.patrimonio, it.descricao) }
        ))
    }

    private fun recusa(mensagem: String, auth: Authentication, request: HttpServletRequest): ResponseEntity<Any> {
        log.warn("Movimentação pelo coletor {} recusada (400): {}", auth.name, mensagem)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            mapOf("status" to 400, "mensagem" to mensagem, "caminho" to request.requestURI)
        )
    }
}
