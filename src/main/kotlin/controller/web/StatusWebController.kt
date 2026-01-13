package org.example.controller.web

import org.example.service.StatusService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class StatusWebController(
    private val service: StatusService
) {

    @GetMapping("/status")
    fun status(model: Model): String {
        model.addAttribute("info", service.getStatus())
        return "status"
    }
}
