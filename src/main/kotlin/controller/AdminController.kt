package org.example.controller

import org.example.service.RfidService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

@Controller
@RequestMapping("/admin")
class AdminController(private val service: RfidService) {

    @GetMapping("/cadastro")
    fun telaCadastro(model: Model): String {

        model.addAttribute(
            "pendentes",
            service.listarNaoCadastradas()
        )

        return "cadastro"
    }
}
