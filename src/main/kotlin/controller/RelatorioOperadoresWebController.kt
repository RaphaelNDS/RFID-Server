package org.example.controller

import org.example.service.AtividadeOperador
import org.example.service.RelatorioOperadoresService
import org.example.util.FormatoBr
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Clock
import java.time.LocalDate

/** Relatório por operador (1.32.0). Quem vê os Relatórios (Relatórios · Ver). Padrão: os últimos 7 dias. */
@Controller
@RequestMapping("/admin/relatorio-operadores")
class RelatorioOperadoresWebController(
    private val service: RelatorioOperadoresService,
    private val relogio: Clock
) {

    @PreAuthorize("@authService.temPermissao('DASHBOARD','READ')")
    @GetMapping
    fun relatorio(@RequestParam(required = false) inicio: String?, @RequestParam(required = false) fim: String?, model: Model): String {
        val hoje = LocalDate.now(relogio)
        val ate = data(fim) ?: hoje
        val de = data(inicio) ?: ate.minusDays(6)
        try {
            model.addAttribute("r", service.relatorio(de, ate))
        } catch (e: IllegalArgumentException) {
            model.addAttribute("erro", e.message)
            model.addAttribute("r", service.relatorio(hoje.minusDays(6), hoje))
        }
        model.addAttribute("inicio", de)
        model.addAttribute("fim", ate)
        model.addAttribute("atividades", AtividadeOperador.entries)
        model.addAttribute("fmt", FormatoBr)
        return "relatorio-operadores"
    }

    private fun data(texto: String?): LocalDate? = texto?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
