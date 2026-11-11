package org.example.controller

import org.example.service.RelatorioLocacaoService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDate

/** Relatórios internos da locação (1.17.0): uso, parados, avarias e pontualidade dos clientes. Locação · Ver. */
@Controller
class RelatorioLocacaoWebController(
    private val service: RelatorioLocacaoService,
    // 1.18.0: acessórios que não voltaram
    private val acessorios: org.example.service.AcessorioService
) {

    @PreAuthorize("@authService.temPermissao('LOCACAO','READ')")
    @GetMapping("/admin/relatorios-locacao")
    fun relatorios(
        @RequestParam(required = false) de: String?,
        @RequestParam(required = false) ate: String?,
        @RequestParam(required = false) parado: Long?,
        model: Model
    ): String {
        fun dia(t: String?) = t?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val r = try {
            service.relatorio(dia(de), dia(ate), (parado ?: 60).coerceIn(0, 3650))
        } catch (e: IllegalArgumentException) {
            model.addAttribute("erro", e.message)
            service.relatorio(null, null)
        }
        model.addAttribute("r", r)
        model.addAttribute("acessoriosACobrar", acessorios.aCobrar())
        model.addAttribute("hoje", service.hoje())
        model.addAttribute("fmt", org.example.util.FormatoBr)
        return "relatorios-locacao"
    }
}
