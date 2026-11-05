package org.example.controller.web

import org.example.service.StatusService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class StatusWebController(
    private val service: StatusService
) {

    // Só ADMIN, como /api/status: mostra o endereço do banco, a situação dos backups e a instrução docker exec
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/status")
    fun status(model: Model): String {
        model.addAttribute("info", service.getStatus())
        return "status"
    }
}
