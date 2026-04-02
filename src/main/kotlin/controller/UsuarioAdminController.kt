package org.example.controller

import org.example.request.CadastroUsuarioRequest
import org.example.service.UsuarioService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping


@PreAuthorize("hasRole('ADMIN')")
@Controller
@RequestMapping("/admin/usuarios")
class UsuarioAdminController(
    private val service: UsuarioService
) {

    @GetMapping
    fun lista(model: Model): String {
        model.addAttribute("usuarios", service.listarTodos())
        return "admin-usuarios"
    }

    @GetMapping("/novo")
    fun novo(): String = "admin-usuarios-cadastro"

    @PostMapping("/cadastrar")
    fun criar(req: CadastroUsuarioRequest, model: Model): String {

        return try {

            service.cadastrar(req)
            model.addAttribute("msg", "Usuário criado com sucesso!")
            "redirect:/admin/usuarios/novo"

        } catch (e: Exception) {

            model.addAttribute("erro", e.message)
            "admin-usuarios-cadastro"
        }
    }
}