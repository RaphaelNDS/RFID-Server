package org.example.controller.web

import org.example.service.PoliticaSenha
import org.example.service.StatusService
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

@ControllerAdvice
class GlobalModelAdvice(
    private val statusService: StatusService,
    private val politicaSenha: PoliticaSenha
) {

    @ModelAttribute("info")
    fun addStatusGlobal() = statusService.getStatus()

    // Texto das regras de senha, mostrado nos formulários que pedem senha nova
    @ModelAttribute("regraSenha")
    fun regraSenha() = politicaSenha.descricao
}
