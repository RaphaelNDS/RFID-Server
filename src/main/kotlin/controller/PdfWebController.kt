package org.example.controller

import org.example.model.AreaAuditoria
import org.example.service.AuditoriaService
import org.example.service.EsperadoInventarioService
import org.example.service.InventarioService
import org.example.service.PdfService
import org.example.service.TransferenciaService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.format.DateTimeFormatter

/**
 * Os PDFs (1.8.0), com as mesmas permissões das telas de onde saem: laudo do inventário (Leituras), guia de
 * transferência (RFID Ver). Abrem no navegador (inline); o laudo vai para a auditoria, como as exportações. O relatório
 * de valores saiu na 1.12.0 e o termo de responsabilidade na 1.18.1.
 */
@RestController
@RequestMapping("/admin")
class PdfWebController(
    private val pdf: PdfService,
    private val inventarios: InventarioService,
    private val esperados: EsperadoInventarioService,
    private val transferencias: TransferenciaService,
    private val auditoria: AuditoriaService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @GetMapping("/inventarios/{id}/laudo.pdf")
    fun laudo(@PathVariable id: String, auth: Authentication): ResponseEntity<ByteArray> {
        val detalhe = inventarios.detalhar(id)
        val comparacao = esperados.faltandoNoInventario(id)
        val bytes = pdf.gerar("pdf/laudo-inventario", mapOf(
            "inventario" to detalhe.resumo,
            "itens" to detalhe.itens,
            "comparacao" to comparacao,
            "justificativas" to inventarios.justificativasDo(id)
        ))
        val local = detalhe.resumo.local
        log.info("{} gerou o laudo do inventário {} de '{}' em PDF: {} lida(s), {} faltando", auth.name, id, local,
            detalhe.itens.size, comparacao.faltando.size)
        auditoria.registrar(AreaAuditoria.EXPORTACAO, "Laudo do inventário em PDF", "$local ($id)",
            "${detalhe.itens.size} lida(s), ${comparacao.faltando.size} faltando")
        return pdf.resposta(bytes, "laudo-inventario-${nomeArquivo(local)}-${detalhe.resumo.iniciadoEm.format(DIA)}.pdf")
    }

    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping("/transferencias/{id}/guia.pdf")
    fun guia(@PathVariable id: Long, auth: Authentication): ResponseEntity<ByteArray> {
        val detalhe = transferencias.detalhar(id)
        val bytes = pdf.gerar("pdf/guia-transferencia", mapOf("guia" to detalhe.transferencia, "itens" to detalhe.itens))
        log.info("{} gerou a guia de transferência nº {} em PDF ({} bem(ns))", auth.name, id, detalhe.itens.size)
        return pdf.resposta(bytes, "guia-transferencia-$id.pdf")
    }

    companion object {
        private val DIA = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** "Sala 101 / Térreo" → "sala-101-terreo" (o nome do arquivo sem acento nem símbolo). */
        fun nomeArquivo(texto: String): String = java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifEmpty { "sem-nome" }
    }
}
