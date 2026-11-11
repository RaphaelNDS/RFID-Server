package org.example.controller

import org.example.service.CatalogoService
import org.example.service.PainelService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDate

/**
 * Disponibilidade dos equipamentos (1.13.0): por tipo e modelo, quantos estão disponíveis, alugados, em devolução ou
 * em manutenção, quantos voltam até uma data e quantos já estão comprometidos em pedidos a separar. RFID · Ver.
 */
@Controller
@RequestMapping("/admin/disponibilidade")
class DisponibilidadeWebController(private val painel: PainelService, private val catalogo: CatalogoService) {

    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping
    fun disponibilidade(@RequestParam(required = false) ate: String?, @RequestParam(required = false) tipo: Long?, model: Model): String {
        val hoje = painel.hoje()
        val data = ate?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (ate != null && ate.isNotBlank() && data == null) model.addAttribute("erro", "Data inválida: mostrando até hoje")
        model.addAttribute("d", painel.disponibilidade(data?.takeIf { !it.isBefore(hoje) } ?: hoje, tipo))
        model.addAttribute("hoje", hoje)
        model.addAttribute("tipos", catalogo.listarTipos())
        model.addAttribute("tipoEscolhido", tipo)
        return "disponibilidade"
    }
}
