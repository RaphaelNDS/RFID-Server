package org.example.controller

import org.example.service.UsuarioService
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.*

@Controller
@RequestMapping("/perfil")
class PerfilController(
    private val usuarioService: UsuarioService
) {

    @GetMapping
    fun perfil(): String = "perfil"

    @GetMapping("/senha")
    fun senha(): String = "perfil-senha"

    @PostMapping("/senha")
    fun alterarSenha(
        @RequestParam senhaAtual: String,
        @RequestParam novaSenha: String,
        @RequestParam confirmarSenha: String
    ): String {

        if (novaSenha != confirmarSenha) {
            throw RuntimeException("Nova senha e confirmação não conferem")
        }

        usuarioService.alterarSenha(senhaAtual, novaSenha)

        return "redirect:/perfil?senhaAlterada"
    }

    @GetMapping("/email")
    fun email(): String = "perfil-email"

    @PostMapping("/email")
    fun alterarEmail(
        @RequestParam novoEmail: String
    ): String {

        usuarioService.alterarEmail(novoEmail)

        return "redirect:/perfil?emailAlterado"
    }
}