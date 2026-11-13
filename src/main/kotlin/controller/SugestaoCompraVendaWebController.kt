package org.example.controller

import org.example.service.RelatorioLocacaoService
import org.example.service.VendaService
import org.example.util.FormatoBr
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.LocalDate

/**
 * Sugestão de compra e venda (1.25.0): o que comprar (esgotou, faltou ou ocupação alta) e o que vender (sobra e
 * parados). Ver: Locação · Ver. Pôr à venda: Etiquetas · Editar (muda a situação do equipamento).
 */
@Controller
@RequestMapping("/admin/compra-e-venda")
class SugestaoCompraVendaWebController(
    private val relatorios: RelatorioLocacaoService,
    private val vendas: VendaService
) {

    @PreAuthorize("@authService.temPermissao('LOCACAO','READ')")
    @GetMapping
    fun sugestoes(
        @RequestParam(required = false) de: String?, @RequestParam(required = false) ate: String?,
        @RequestParam(required = false) compra: Int?, @RequestParam(required = false) venda: Int?, model: Model
    ): String {
        fun dia(t: String?) = t?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val ocupacaoCompra = (compra ?: 80).coerceIn(1, 100)
        val ocupacaoVenda = (venda ?: 30).coerceIn(0, 100)
        val s = try {
            relatorios.sugestoes(dia(de), dia(ate), ocupacaoCompra.toDouble(), ocupacaoVenda.toDouble())
        } catch (e: IllegalArgumentException) {
            model.addAttribute("erro", e.message)
            relatorios.sugestoes(null, null)
        }
        model.addAttribute("s", s)
        model.addAttribute("ocupacaoCompra", ocupacaoCompra)
        model.addAttribute("ocupacaoVenda", ocupacaoVenda)
        model.addAttribute("fmt", FormatoBr)
        return "compra-e-venda"
    }

    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @PostMapping("/a-venda")
    fun porAVenda(@RequestParam(required = false) tags: List<Long>?, redirect: RedirectAttributes): String {
        try {
            val mudaram = vendas.porAVenda(tags.orEmpty())
            redirect.addFlashAttribute("msg", if (mudaram.isEmpty()) "Nenhum equipamento mudou: só os Disponíveis vão para À venda."
                else "${mudaram.size} equipamento(s) À venda. Eles saem da disponibilidade para locação.")
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:/admin/compra-e-venda"
    }
}
