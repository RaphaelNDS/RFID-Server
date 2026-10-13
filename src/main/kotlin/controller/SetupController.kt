package org.example.controller

import jakarta.servlet.http.HttpServletRequest
import org.example.request.SetupAdminRequest
import org.example.service.UsuarioService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping

@Controller
class SetupController(
    private val usuarioService: UsuarioService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/setup")
    fun setup(): String {
        if (usuarioService.existeUsuario()) {
            return "redirect:/login"
        }
        return "setup"
    }

    @PostMapping("/setup")
    fun criarAdmin(req: SetupAdminRequest, request: HttpServletRequest, model: Model): String {

        if (usuarioService.existeUsuario()) {
            log.warn(
                "Tentativa bloqueada de criar administrador via /setup com o sistema já configurado (IP: {}, email informado: {})",
                request.remoteAddr, req.email
            )
            return "redirect:/login"
        }

        return try {
            usuarioService.criarAdminInicial(req)
            "redirect:/login"
        } catch (e: IllegalArgumentException) {
            // Ex.: senha fraca - volta ao formulário com o motivo e o que foi digitado (menos a senha)
            model.addAttribute("erro", e.message)
            model.addAttribute("nome", req.nome)
            model.addAttribute("email", req.email)
            "setup"
        }
    }
}
