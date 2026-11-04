package org.example.controller.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.example.service.PoliticaSenha
import org.example.service.RecuperacaoSenhaService
import org.example.service.StatusService
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Dados que todas as telas usam (rodapé com a versão, regras de senha, contador de pedidos de senha no menu).
 *
 * Era um @ControllerAdvice com @ModelAttribute, que roda em TODA requisição, inclusive na API dos coletores e no
 * SSE (o status lia a pasta de backups a cada leitura enviada). Como interceptador, só roda quando a resposta
 * é uma tela (há ModelAndView com uma view que não é redirect).
 */
class ModeloGlobalInterceptor(
    private val statusService: StatusService,
    private val politicaSenha: PoliticaSenha,
    private val recuperacaoSenha: RecuperacaoSenhaService
) : HandlerInterceptor {

    override fun postHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any, modelAndView: ModelAndView?) {
        val tela = modelAndView ?: return
        if (tela.viewName?.startsWith("redirect:") == true) return

        // Quem já colocou o próprio valor no modelo (ex.: tela de status) não é sobrescrito
        if (!tela.modelMap.containsAttribute("info")) tela.addObject("info", statusService.getStatus())
        // Texto das regras de senha, mostrado nos formulários que pedem senha nova
        if (!tela.modelMap.containsAttribute("regraSenha")) tela.addObject("regraSenha", politicaSenha.descricao)
        // Pedidos de "Esqueceu a senha?" esperando o ADMIN: contador ao lado de "Usuários" no menu
        if (!tela.modelMap.containsAttribute("pedidosSenha")) tela.addObject("pedidosSenha", pedidosSenha())
    }

    private fun pedidosSenha(): Long {
        val admin = SecurityContextHolder.getContext().authentication?.authorities?.any { it.authority == "ROLE_ADMIN" } == true
        return if (admin) recuperacaoSenha.quantidadePendentes() else 0
    }
}

@Configuration
class ModeloGlobalConfig(
    private val statusService: StatusService,
    private val politicaSenha: PoliticaSenha,
    private val recuperacaoSenha: RecuperacaoSenhaService
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(ModeloGlobalInterceptor(statusService, politicaSenha, recuperacaoSenha))
    }
}
