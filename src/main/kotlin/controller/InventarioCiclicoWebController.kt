package org.example.controller

import org.example.service.InventarioCiclicoService
import org.example.util.FormatoBr
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/** Inventário cíclico (1.27.0). Ver o plano: Leituras · Ver (como os inventários); ligar e mudar o ciclo: Leituras · Editar. */
@Controller
@RequestMapping("/admin/inventario-ciclico")
class InventarioCiclicoWebController(private val service: InventarioCiclicoService) {

    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @GetMapping
    fun plano(model: Model): String {
        model.addAttribute("p", service.plano())
        model.addAttribute("fmt", FormatoBr)
        return "inventario-ciclico"
    }

    @PreAuthorize("@authService.temPermissao('LEITURA','UPDATE')")
    @PostMapping
    fun configurar(
        @RequestParam(defaultValue = "false") ativo: Boolean, @RequestParam(required = false) semanas: Int?,
        redirect: RedirectAttributes
    ): String {
        try {
            val c = service.configurar(ativo, semanas)
            redirect.addFlashAttribute("msg", if (c.ativo) "Inventário cíclico ligado: cada local a cada ${c.semanas} semana(s)." else "Inventário cíclico desligado.")
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:/admin/inventario-ciclico"
    }
}
