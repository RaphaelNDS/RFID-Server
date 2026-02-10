package org.example.controller

import org.example.service.CatalogoService
import org.example.service.RfidService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.example.request.CadastroRequest
import org.example.service.StatusService

@Controller
@RequestMapping("/admin")
class AdminController(
    private val rfidService: RfidService,
    private val statusService: StatusService
) {

    @GetMapping
//    fun dashboard() = "admin"
    fun dashboard(model: Model): String {

        model.addAttribute("info", statusService.getStatus())  // 👈 ADICIONE ISSO

        return "admin"
    }

    @GetMapping("/tags-pendentes")
    fun pendentes(model: Model): String {
        model.addAttribute("pendentes", rfidService.listarNaoCadastradas())
        return "admin-tags-pendentes"
    }

    @GetMapping("/tags-cadastradas")
    fun cadastradas(model: Model): String {
        model.addAttribute("tags", rfidService.listarParaAdmin())
        return "admin-tags"
    }
}
