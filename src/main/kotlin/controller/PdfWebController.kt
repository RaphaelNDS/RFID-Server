package org.example.controller

import org.example.model.AreaAuditoria
import org.example.service.AuditoriaService
import org.example.service.EsperadoInventarioService
import org.example.service.InventarioService
import org.example.service.MovimentoService
import org.example.service.PdfService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.text.Normalizer
import java.time.format.DateTimeFormatter

/**
 * Os PDFs, com as mesmas permissões das telas de onde saem: laudo do inventário (Leituras) e comprovante da
 * movimentação (Movimentações · Ver). Abrem no navegador (inline); o laudo vai para a auditoria, como as exportações.
 */
@RestController
@RequestMapping("/admin")
class PdfWebController(
    private val pdf: PdfService,
    private val inventarios: InventarioService,
    private val esperados: EsperadoInventarioService,
    private val auditoria: AuditoriaService,
    private val movimentos: MovimentoService
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

    /** Comprovante da movimentação (2.0.0): os equipamentos que saíram ou entraram, o destino e as assinaturas. */
    @PreAuthorize("@authService.temPermissao('MOVIMENTO','READ')")
    @GetMapping("/movimentos/{id}/comprovante.pdf")
    fun comprovante(@PathVariable id: Long, auth: Authentication): ResponseEntity<ByteArray> {
        val detalhe = movimentos.detalhar(id)
        val bytes = pdf.gerar("pdf/comprovante-movimento", mapOf("movimento" to detalhe.movimento, "itens" to detalhe.itens))
        log.info("{} gerou o comprovante da {} nº {} em PDF ({} equipamento(s))", auth.name,
            detalhe.movimento.tipo.rotulo.lowercase(), id, detalhe.itens.size)
        return pdf.resposta(bytes, "comprovante-${nomeArquivo(detalhe.movimento.tipo.rotulo)}-$id.pdf")
    }

    companion object {
        private val DIA = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** "Sala 101 / Térreo" → "sala-101-terreo" (o nome do arquivo sem acento nem símbolo). */
        fun nomeArquivo(texto: String): String = Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifEmpty { "sem-nome" }
    }
}
