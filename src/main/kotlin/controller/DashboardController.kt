package org.example.controller

import org.example.service.AuthService
import org.example.service.PainelService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class DashboardController(
    private val authService: AuthService,
    // 1.13.0: painel do dia na tela inicial; desde a 1.15.0 para quem vê a locação (Locação · Ver)
    private val painel: PainelService? = null,
    // 1.27.0: inventário cíclico (quantos locais contar nesta semana)
    private val ciclico: org.example.service.InventarioCiclicoService? = null,
    // 1.30.0: o que falta para começar a usar o sistema (só o administrador vê)
    private val primeirosPassos: org.example.service.PrimeirosPassosService? = null
) {

    @GetMapping("/dashboard")
    fun dashboard(model: Model): String {

        model.addAttribute("isAdmin", authService.temRole("ADMIN"))
        if (authService.temPermissao("LOCACAO", "READ")) model.addAttribute("painel", painel?.painel())
        if (authService.temPermissao("LEITURA", "READ")) model.addAttribute("cicloSemana", ciclico?.locaisDaSemana())
        if (authService.temRole("ADMIN")) model.addAttribute("primeirosPassos", primeirosPassos?.paraOPainel())

        return "dashboard"
    }
}
