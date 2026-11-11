package org.example.controller

import org.example.model.AreaAuditoria
import org.example.response.TagViewAdmin
import org.example.service.AuditoriaService
import org.example.service.EsperadoInventarioService
import org.example.service.FiltroEtiquetas
import org.example.service.InventarioService
import org.example.service.PatrimonioCadastrosService
import org.example.service.PdfService
import org.example.service.RfidService
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
 * transferência (RFID Ver), termo de responsabilidade (Catálogo Ver e RFID Ver) e bens com valor contábil (RFID Ver).
 * Abrem no navegador (inline); o laudo vai para a auditoria, como as exportações. O relatório de valores saiu na 1.12.0.
 */
@RestController
@RequestMapping("/admin")
class PdfWebController(
    private val pdf: PdfService,
    private val inventarios: InventarioService,
    private val esperados: EsperadoInventarioService,
    private val transferencias: TransferenciaService,
    private val cadastros: PatrimonioCadastrosService,
    private val rfid: RfidService,
    private val auditoria: AuditoriaService,
    private val relogio: java.time.Clock
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

    @PreAuthorize("@authService.temPermissao('CATALOGO','READ') and @authService.temPermissao('RFID','READ')")
    @GetMapping("/responsaveis/{id}/termo.pdf")
    fun termo(@PathVariable id: Long, auth: Authentication): ResponseEntity<ByteArray> {
        val responsavel = cadastros.responsavel(id)
        // Mesma lista da tela do termo: sem os baixados, pelo patrimônio (sem patrimônio por último)
        val bens = rfid.listarTodasParaAdmin(null, FiltroEtiquetas(responsavelId = id))
            .sortedWith(compareBy<TagViewAdmin> { it.patrimonio.ifBlank { "￿" } }.thenBy { it.tag })
        val bytes = pdf.gerar("pdf/termo-responsabilidade", mapOf("responsavel" to responsavel, "bens" to bens, "hoje" to java.time.LocalDate.now(relogio)))
        log.info("{} gerou o termo de responsabilidade de {} em PDF ({} bem(ns))", auth.name, responsavel.nome, bens.size)
        return pdf.resposta(bytes, "termo-responsabilidade-${nomeArquivo(responsavel.nome)}.pdf")
    }

    companion object {
        private val DIA = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** "Sala 101 / Térreo" → "sala-101-terreo" (o nome do arquivo sem acento nem símbolo). */
        fun nomeArquivo(texto: String): String = java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifEmpty { "sem-nome" }
    }
}

