package org.example.controller

import org.example.request.CadastroUsuarioRequest
import org.example.request.SetupAdminRequest
import org.example.service.UsuarioService
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping

@Controller
class SetupController(
    private val usuarioService: UsuarioService
) {

    @GetMapping("/setup")
    fun setup(): String = "setup"

    @PostMapping("/setup")
    fun criarAdmin(req: SetupAdminRequest): String {
        usuarioService.criarAdminInicial(req)
        return "redirect:/login"
    }
}