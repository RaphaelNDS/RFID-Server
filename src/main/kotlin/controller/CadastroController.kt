package org.example.controller

import jakarta.transaction.Transactional
import org.example.request.CadastroRequest
import org.example.service.RfidService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
@RequestMapping("/admin/cadastro")
class CadastroController(private val service: RfidService) {

    @GetMapping("/form")
    fun form(@RequestParam tag: String, model: Model): String {

        model.addAttribute(
            "cadastro",
            CadastroRequest(
                tag = tag,
                modelo = "",
                patrimonio = "",
                numeroSerie = ""
            )
        )

        return "cadastro-form"
    }

    @PostMapping("/salvar")
    @Transactional
    fun salvar(req: CadastroRequest): String {
        service.cadastrarTag(req)
        return "redirect:/admin/cadastro"
    }

}
