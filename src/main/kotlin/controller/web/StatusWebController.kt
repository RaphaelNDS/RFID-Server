package org.example.controller.web

import org.example.service.AlertaService
import org.example.service.StatusService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class StatusWebController(
    private val service: StatusService,
    private val alertas: AlertaService
) {

    // Só ADMIN, como /api/status: mostra o endereço do banco, a situação dos backups e a instrução docker exec
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/status")
    fun status(model: Model): String {
        model.addAttribute("info", service.getStatus())
        return "status"
    }

    /** Email de teste dos avisos (1.5.1): manda na hora e mostra o erro do SMTP, se houver. */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/status/email-teste")
    fun emailTeste(auth: Authentication, redirect: RedirectAttributes): String {
        alertas.enviarTeste(auth.name)
            .onSuccess { redirect.addFlashAttribute("msg", "Email de teste enviado para ${it.joinToString()}. Confira a caixa de entrada (e o lixo eletrônico).") }
            .onFailure { redirect.addFlashAttribute("erro", "O email de teste não foi enviado: ${it.message}") }
        return "redirect:/status"
    }
}
