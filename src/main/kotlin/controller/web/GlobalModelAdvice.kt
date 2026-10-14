package org.example.controller.web

import org.example.service.PoliticaSenha
import org.example.service.RecuperacaoSenhaService
import org.example.service.StatusService
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

@ControllerAdvice
class GlobalModelAdvice(
    private val statusService: StatusService,
    private val politicaSenha: PoliticaSenha,
    private val recuperacaoSenha: RecuperacaoSenhaService
) {

    @ModelAttribute("info")
    fun addStatusGlobal() = statusService.getStatus()

    // Texto das regras de senha, mostrado nos formulários que pedem senha nova
    @ModelAttribute("regraSenha")
    fun regraSenha() = politicaSenha.descricao

    // Pedidos de "Esqueceu a senha?" esperando o ADMIN: contador ao lado de "Usuários" no menu
    @ModelAttribute("pedidosSenha")
    fun pedidosSenha(): Long {
        val admin = SecurityContextHolder.getContext().authentication?.authorities?.any { it.authority == "ROLE_ADMIN" } == true
        return if (admin) recuperacaoSenha.quantidadePendentes() else 0
    }
}
