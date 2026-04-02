package org.example.controller

import org.example.service.UsuarioService
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class LoginController(
    private val usuarioService: UsuarioService
) {

    @GetMapping("/login")
    fun login(): String {
        return if (!usuarioService.existeUsuario()) {
            "redirect:/setup"
        } else {
            "login"
        }
    }
}