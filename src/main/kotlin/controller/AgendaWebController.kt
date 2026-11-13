package org.example.controller

import org.example.service.AgendaService
import org.example.service.PdfService
import org.example.service.TipoAgenda
import org.example.util.FormatoBr
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.LocalDate

/** Agenda de entregas e retiradas (1.24.0). Ver: Locação · Ver; definir quem leva e a hora: Locação · Editar. */
@Controller
@RequestMapping("/admin/agenda")
class AgendaWebController(
    private val service: AgendaService,
    private val pdf: PdfService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PreAuthorize("@authService.temPermissao('LOCACAO','READ')")
    @GetMapping
    fun agenda(@RequestParam(required = false) dia: String?, model: Model): String {
        val a = service.agenda(lerDia(dia))
        model.addAttribute("a", a)
        model.addAttribute("grupos", listOf(
            "Atrasados (saída ou devolução prevista já passou)" to a.atrasados,
            "Entregas" to a.entregas,
            "Retiradas (buscar no cliente)" to a.retiradas
        ))
        model.addAttribute("fmt", FormatoBr)
        return "agenda"
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @PostMapping("/{pedidoId}/{tipo}")
    fun definir(
        @PathVariable pedidoId: Long, @PathVariable tipo: String,
        @RequestParam(required = false) responsavel: String?, @RequestParam(required = false) hora: String?,
        @RequestParam(required = false) dia: String?, redirect: RedirectAttributes
    ): String {
        try {
            val t = TipoAgenda.entries.firstOrNull { it.name == tipo.uppercase() } ?: throw IllegalArgumentException("Tipo inválido: $tipo")
            service.definir(pedidoId, t, responsavel, hora)
            redirect.addFlashAttribute("msg", "Pedido nº $pedidoId: ${t.rotulo.lowercase()} atualizada.")
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:/admin/agenda" + (lerDia(dia)?.let { "?dia=$it" } ?: "")
    }

    /** Roteiro do dia em PDF, separado por quem leva/busca: para o motorista. */
    @PreAuthorize("@authService.temPermissao('LOCACAO','READ')")
    @GetMapping("/roteiro.pdf")
    @ResponseBody
    fun roteiro(@RequestParam(required = false) dia: String?, auth: Authentication): ResponseEntity<ByteArray> {
        val a = service.agenda(lerDia(dia))
        val bytes = pdf.gerar("pdf/roteiro-dia", mapOf("a" to a, "detalhes" to service.detalhes(a.atrasados + a.entregas + a.retiradas), "fmt" to FormatoBr))
        log.info("{} gerou o roteiro de entregas e retiradas de {} em PDF ({} entrega(s), {} retirada(s), {} atrasado(s))",
            auth.name, a.dia, a.entregas.size, a.retiradas.size, a.atrasados.size)
        return pdf.resposta(bytes, "roteiro-${a.dia}.pdf")
    }

    private fun lerDia(texto: String?): LocalDate? = texto?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
