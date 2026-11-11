package org.example.controller

import org.example.model.PedidoLocacaoEntity
import org.example.model.SituacaoPedido
import org.example.service.ItemConferido
import org.example.service.LinhaConferida
import org.example.service.LocacaoService
import org.example.service.PedidoResumo
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Locação pelo coletor (1.12.0): lista e detalhe dos pedidos, conferência das leituras (sem gravar), saída e devolução.
 * Exige o operador com Locação · Editar (1.15.0; o login devolve podeMovimentarLocacao). Erros: {"status","mensagem"}; 404 pedido
 * inexistente, 409 saída em pedido cancelado ou encerrado, 400 corpo inválido (sem etiquetas, mais de 500).
 */
@RestController
@RequestMapping("/api/android")
class LocacaoAndroidController(private val service: LocacaoService) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class Leituras(val etiquetas: List<String>? = null, val observacao: String? = null, val idEnvio: String? = null)

    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @GetMapping("/pedidos")
    fun listar(@RequestParam(required = false) situacao: String?): ResponseEntity<Any> = tratar("lista de pedidos", null) {
        val situacoes = situacao.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { s ->
            SituacaoPedido.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: throw IllegalArgumentException("Situação de pedido inválida: $s")
        }
        service.paraColetor(situacoes).map(::resumo)
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @GetMapping("/pedidos/{id}")
    fun detalhe(@PathVariable id: Long): ResponseEntity<Any> = tratar("pedido", id) {
        val d = service.detalhar(id)
        cabecalho(d.pedido) + mapOf(
            "linhas" to d.linhas.map(::linha),
            "itens" to d.itens.map { mapOf("epc" to it.tagReal, "patrimonio" to it.patrimonio, "linhaId" to it.linhaId, "devolvido" to it.devolvido) }
        )
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @PostMapping("/pedidos/{id}/conferir")
    fun conferir(@PathVariable id: Long, @RequestBody corpo: Leituras): ResponseEntity<Any> = tratar("conferência do pedido", id) {
        val c = service.conferir(id, corpo.etiquetas.orEmpty())
        mapOf("itens" to c.itens.map(::item), "linhas" to c.linhas.map(::linha))
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @PostMapping("/pedidos/{id}/saida")
    fun saida(@PathVariable id: Long, @RequestBody corpo: Leituras, auth: Authentication): ResponseEntity<Any> = tratar("saída do pedido", id) {
        if (corpo.idEnvio != null) log.info("Saída do pedido {} pelo coletor {} (envio {})", id, auth.name, corpo.idEnvio.take(60))
        val r = try {
            service.registrarSaida(id, corpo.etiquetas.orEmpty(), corpo.observacao, "Coletor ${auth.name}")
        } catch (e: DataIntegrityViolationException) {
            // Outra leitura pôs um destes equipamentos em outro pedido no mesmo instante: de novo, ele vem NAO_DISPONIVEL
            log.info("Saída do pedido {} pelo coletor {}: envio simultâneo com outro; repetindo", id, auth.name)
            service.registrarSaida(id, corpo.etiquetas.orEmpty(), corpo.observacao, "Coletor ${auth.name}")
        }
        mapOf(
            "aceitas" to r.saidos.map { mapOf("epc" to (it.tag?.tagReal ?: it.codigo), "patrimonio" to it.tag?.patrimonio?.ifBlank { null }, "linhaId" to it.linhaId) },
            "recusadas" to r.recusados.map { mapOf("epc" to (it.tag?.tagReal ?: it.codigo), "motivo" to it.problema!!.name, "mensagem" to it.problema.rotulo) },
            "faltando" to r.conferencia.linhas.map { it.linha.id to (it.faltam - it.conferidos).coerceAtLeast(0) }
                .filter { it.second > 0 }.map { mapOf("linhaId" to it.first, "quantidade" to it.second) },
            "situacao" to r.pedido.situacao.name,
            "situacaoRotulo" to r.pedido.situacao.rotulo
        )
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @PostMapping("/devolucoes")
    fun devolucoes(@RequestBody corpo: Leituras, auth: Authentication): ResponseEntity<Any> = tratar("devolução", null) {
        if (corpo.idEnvio != null) log.info("Devolução pelo coletor {} (envio {})", auth.name, corpo.idEnvio.take(60))
        val r = service.registrarDevolucao(corpo.etiquetas.orEmpty(), corpo.observacao, "Coletor ${auth.name}")
        mapOf(
            "recebidas" to r.recebidos.map { (i, p) -> mapOf("epc" to i.tagReal, "patrimonio" to i.patrimonio, "pedido" to p.id, "cliente" to p.cliente) },
            "semLocacao" to r.semLocacao.map { (codigo, tag) ->
                mapOf("epc" to (tag?.tagReal ?: codigo), "patrimonio" to tag?.patrimonio?.ifBlank { null },
                    "situacao" to tag?.situacao?.name, "situacaoRotulo" to tag?.situacao?.rotulo)
            },
            "pedidos" to r.pedidos.map { p ->
                val naRua = service.detalhar(p.id).naRua
                mapOf("pedido" to p.id, "cliente" to p.cliente, "situacao" to p.situacao.name, "situacaoRotulo" to p.situacao.rotulo,
                    "faltando" to naRua.map { i ->
                        val tag = i.tagId?.let { service.tagPorId(it) }
                        val (tipo, marca, modelo) = tag?.let { service.nomes(it) } ?: Triple(null, null, null)
                        mapOf("epc" to i.tagReal, "patrimonio" to i.patrimonio, "tipo" to tipo, "marca" to marca, "modelo" to modelo)
                    })
            }
        )
    }

    private fun resumo(r: PedidoResumo) = cabecalho(r.pedido) + mapOf("totalItens" to r.quantidade, "saidos" to r.saidos)

    private fun cabecalho(p: PedidoLocacaoEntity) = mapOf(
        "id" to p.id, "numero" to p.id, "cliente" to p.cliente, "saidaPrevista" to p.saidaPrevista, "previsaoDevolucao" to p.previsaoDevolucao,
        "situacao" to p.situacao.name, "situacaoRotulo" to p.situacao.rotulo, "localEntrega" to p.localEntrega, "observacao" to p.observacao
    )

    private fun linha(l: LinhaConferida): Map<String, Any?> {
        val linha = l.linha
        val tag = linha.tagId?.let { service.tagPorId(it) }
        val (tipo, marca, modelo) = service.nomesDaLinha(linha)
        return mapOf(
            "linhaId" to linha.id, "tipo" to tipo, "marca" to marca, "modelo" to modelo,
            "patrimonio" to if (linha.especifico) (tag?.patrimonio?.ifBlank { null } ?: linha.descricao.substringBefore(" · ")) else null,
            "etiqueta" to if (linha.especifico) tag?.tagReal else null,
            "quantidade" to linha.quantidade, "saidos" to l.saidos, "conferidos" to l.conferidos
        )
    }

    private fun item(c: ItemConferido): Map<String, Any?> {
        val tag = c.tag
        val (tipo, marca, modelo) = tag?.let { service.nomes(it) } ?: Triple(null, null, null)
        return mapOf(
            "epc" to (tag?.tagReal ?: c.codigo), "patrimonio" to tag?.patrimonio?.ifBlank { null }, "tipo" to tipo, "marca" to marca, "modelo" to modelo,
            "situacao" to tag?.situacao?.name, "situacaoRotulo" to tag?.situacao?.rotulo, "linhaId" to c.linhaId, "jaSaiu" to c.jaSaiu,
            "problema" to c.problema?.name
        )
    }

    private fun tratar(oque: String, id: Long?, bloco: () -> Any): ResponseEntity<Any> = try {
        ResponseEntity.ok(bloco())
    } catch (e: NoSuchElementException) {
        recusa(HttpStatus.NOT_FOUND, e, oque, id)
    } catch (e: IllegalStateException) {
        recusa(HttpStatus.CONFLICT, e, oque, id)
    } catch (e: IllegalArgumentException) {
        recusa(HttpStatus.BAD_REQUEST, e, oque, id)
    }

    private fun recusa(status: HttpStatus, e: Exception, oque: String, id: Long?): ResponseEntity<Any> {
        log.info("Locação pelo coletor: {}{} recusada ({}): {}", oque, id?.let { " nº $it" } ?: "", status.value(), e.message)
        return ResponseEntity.status(status).body(mapOf("status" to status.value(), "mensagem" to e.message))
    }
}
