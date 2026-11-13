package org.example.controller

import org.example.model.FormatoCampo
import org.example.service.CampoPersonalizadoService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Campos personalizados por tipo de equipamento (1.22.0). Módulo Catálogo: Ver para a lista; Criar para campo novo;
 * Editar para alterar, desativar e reativar.
 */
@Controller
@RequestMapping("/admin/catalogo/campos")
class CampoPersonalizadoWebController(private val service: CampoPersonalizadoService) {

    @PreAuthorize("@authService.temPermissao('CATALOGO','READ')")
    @GetMapping
    fun listar(model: Model): String {
        model.addAttribute("tipos", service.tiposComCampos())
        model.addAttribute("formatos", FormatoCampo.entries)
        return "campos-personalizados"
    }

    @PreAuthorize("@authService.temPermissao('CATALOGO','CREATE')")
    @PostMapping
    fun criar(
        @RequestParam(required = false) tipoId: Long?, @RequestParam(required = false) nome: String?,
        @RequestParam(required = false) formato: String?, @RequestParam(required = false) opcoes: String?,
        @RequestParam(defaultValue = "false") obrigatorio: Boolean, redirect: RedirectAttributes
    ): String = acao(redirect) {
        val c = service.salvarCampo(null, tipoId, nome, formato(formato), opcoes, obrigatorio, null)
        "Campo ${c.nome} criado."
    }

    @PreAuthorize("@authService.temPermissao('CATALOGO','UPDATE')")
    @PostMapping("/{id}")
    fun alterar(
        @PathVariable id: Long, @RequestParam(required = false) nome: String?,
        @RequestParam(required = false) formato: String?, @RequestParam(required = false) opcoes: String?,
        @RequestParam(defaultValue = "false") obrigatorio: Boolean, @RequestParam(required = false) ordem: Int?,
        redirect: RedirectAttributes
    ): String = acao(redirect) {
        val c = service.salvarCampo(id, null, nome, formato(formato), opcoes, obrigatorio, ordem)
        "Campo ${c.nome} alterado."
    }

    @PreAuthorize("@authService.temPermissao('CATALOGO','UPDATE')")
    @PostMapping("/{id}/ativo")
    fun ativar(@PathVariable id: Long, @RequestParam(defaultValue = "false") ativo: Boolean, redirect: RedirectAttributes): String = acao(redirect) {
        val c = service.ativarCampo(id, ativo)
        "Campo ${c.nome} ${if (ativo) "reativado" else "desativado (os valores ficam guardados)"}."
    }

    private fun formato(valor: String?): FormatoCampo? = FormatoCampo.entries.firstOrNull { it.name == valor }

    private fun acao(redirect: RedirectAttributes, bloco: () -> String): String {
        try {
            redirect.addFlashAttribute("msg", bloco())
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:/admin/catalogo/campos"
    }
}
