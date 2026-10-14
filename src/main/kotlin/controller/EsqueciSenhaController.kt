package org.example.controller

import jakarta.servlet.http.HttpServletRequest
import org.example.service.RecuperacaoSenhaService
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/** "Esqueceu a senha?" da tela de login (público). O pedido vai para o ADMIN, em Usuários. */
@Controller
@RequestMapping("/esqueci-senha")
class EsqueciSenhaController(private val recuperacao: RecuperacaoSenhaService) {

    @GetMapping
    fun formulario(): String = "esqueci-senha"

    // A resposta é a mesma exista ou não o email, para a tela não revelar quem tem conta
    @PostMapping
    fun pedir(@RequestParam(defaultValue = "") email: String, request: HttpServletRequest): String {
        recuperacao.pedir(email, request.remoteAddr)
        return "redirect:/login?pedidoSenha"
    }
}
