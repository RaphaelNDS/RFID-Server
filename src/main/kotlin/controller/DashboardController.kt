package org.example.controller

import org.example.service.AuthService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class DashboardController(
    private val authService: AuthService
) {

    @GetMapping("/dashboard")
    fun dashboard(model: Model): String {

        model.addAttribute("isAdmin", authService.temRole("ADMIN"))
        model.addAttribute("isExpedicao", authService.temRole("USER"))

        return "dashboard"
    }
}