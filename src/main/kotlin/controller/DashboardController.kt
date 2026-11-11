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
    private val painel: PainelService? = null
) {

    @GetMapping("/dashboard")
    fun dashboard(model: Model): String {

        model.addAttribute("isAdmin", authService.temRole("ADMIN"))
        if (authService.temPermissao("LOCACAO", "READ")) model.addAttribute("painel", painel?.painel())

        return "dashboard"
    }
}
