package org.example.controller

import org.example.service.AcessorioService
import org.example.service.CatalogoService
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
 * Acessórios e kits da locação (1.18.0). Módulo Locação: Ver; cadastrar acessório e pôr no kit: Criar; alterar
 * acessório e tirar do kit: Editar.
 */
@Controller
@RequestMapping("/admin/acessorios")
class AcessorioWebController(private val service: AcessorioService, private val catalogo: CatalogoService) {

    @PreAuthorize("@authService.temPermissao('LOCACAO','READ')")
    @GetMapping
    fun tela(model: Model): String {
        model.addAttribute("acessorios", service.listar())
        model.addAttribute("ativos", service.ativos())
        model.addAttribute("kits", service.kits())
        model.addAttribute("tipos", catalogo.listarTipos())
        return "acessorios"
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','CREATE')")
    @PostMapping
    fun cadastrar(@RequestParam(required = false) nome: String?, redirect: RedirectAttributes): String = acao(redirect) {
        "Acessório ${service.salvar(null, nome).nome} cadastrado."
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @PostMapping("/{id}")
    fun alterar(
        @PathVariable id: Long, @RequestParam(required = false) nome: String?, @RequestParam(defaultValue = "false") ativo: Boolean,
        redirect: RedirectAttributes
    ): String = acao(redirect) {
        val a = service.salvar(id, nome, ativo)
        "Acessório ${a.nome} alterado" + if (a.ativo) "." else " (desativado: não entra mais em kits nem pedidos novos)."
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','CREATE')")
    @PostMapping("/kits")
    fun adicionarAoKit(
        @RequestParam(required = false) tipoEquipamentoId: Long?, @RequestParam(required = false) modeloId: Long?,
        @RequestParam(required = false) acessorioId: Long?, @RequestParam(required = false) quantidade: Int?,
        redirect: RedirectAttributes
    ): String = acao(redirect) {
        val kit = service.adicionarAoKit(tipoEquipamentoId, modeloId, acessorioId, quantidade)
        "Kit ${kit.descricao}: " + kit.itens.joinToString(", ") { "${it.quantidade} × ${it.acessorio}" } + "."
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @PostMapping("/kits/{itemId}/excluir")
    fun removerDoKit(@PathVariable itemId: Long, redirect: RedirectAttributes): String = acao(redirect) {
        service.removerDoKit(itemId)
        "Acessório tirado do kit. Os pedidos que já têm o kit não mudam."
    }

    private fun acao(redirect: RedirectAttributes, bloco: () -> String): String {
        try {
            redirect.addFlashAttribute("msg", bloco())
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        } catch (e: NoSuchElementException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:/admin/acessorios"
    }
}
