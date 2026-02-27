package org.example.controller

import org.example.service.UsuarioService
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
@RequestMapping("/perfil")
class PerfilController(
    private val usuarioService: UsuarioService
) {

    @GetMapping
    fun perfil(): String = "perfil"

    @GetMapping("/senha")
    fun senha(): String = "perfil-senha"

    @GetMapping("/email")
    fun email(): String = "perfil-email"

    @PostMapping("/senha")
    fun alterarSenha(
        @RequestParam atual: String,
        @RequestParam nova: String
    ): String {
        usuarioService.alterarSenha(atual, nova)
        return "redirect:/perfil?ok"
    }

    @PostMapping("/email")
    fun alterarEmail(
        @RequestParam novoEmail: String
    ): String {
        usuarioService.alterarEmail(novoEmail)
        return "redirect:/perfil?ok"
    }
}