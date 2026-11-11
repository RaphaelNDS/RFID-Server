package org.example.controller

import org.example.service.PatrimonioCadastrosService
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
 * Cadastros da gestão de patrimônio (1.6.0): unidades e locais. Mesma permissão do Catálogo (cadastros de apoio das
 * etiquetas): Ver para abrir, Criar/Editar para mudar. A tela de responsáveis e o termo saíram na 1.18.1.
 */
@Controller
@RequestMapping("/admin")
class PatrimonioWebController(private val cadastros: PatrimonioCadastrosService) {

    // ================= LOCAIS =================

    @PreAuthorize("@authService.temPermissao('CATALOGO','READ')")
    @GetMapping("/locais")
    fun locais(model: Model): String {
        model.addAttribute("grupos", cadastros.locaisPorUnidade())
        model.addAttribute("unidades", cadastros.listarUnidades())
        return "locais"
    }

    @PreAuthorize("@authService.temPermissao('CATALOGO','CREATE')")
    @PostMapping("/locais/unidades")
    fun novaUnidade(@RequestParam nome: String, redirect: RedirectAttributes): String =
        executar(redirect, "/admin/locais") { "Unidade ${cadastros.salvarUnidade(null, nome).nome} cadastrada." }

    @PreAuthorize("@authService.temPermissao('CATALOGO','UPDATE')")
    @PostMapping("/locais/unidades/{id}")
    fun editarUnidade(@PathVariable id: Long, @RequestParam nome: String, @RequestParam(defaultValue = "false") ativa: Boolean,
                      redirect: RedirectAttributes): String =
        executar(redirect, "/admin/locais") { "Unidade ${cadastros.salvarUnidade(id, nome, ativa).nome} alterada." }

    @PreAuthorize("@authService.temPermissao('CATALOGO','CREATE')")
    @PostMapping("/locais")
    fun novoLocal(@RequestParam nome: String, @RequestParam(required = false) unidade: Long?, redirect: RedirectAttributes): String =
        executar(redirect, "/admin/locais") { "Local ${cadastros.salvarLocal(null, nome, unidade?.takeIf { it > 0 }).nome} cadastrado." }

    @PreAuthorize("@authService.temPermissao('CATALOGO','UPDATE')")
    @PostMapping("/locais/{id}")
    fun editarLocal(@PathVariable id: Long, @RequestParam nome: String, @RequestParam(required = false) unidade: Long?,
                    @RequestParam(defaultValue = "false") ativo: Boolean, redirect: RedirectAttributes): String =
        executar(redirect, "/admin/locais") { "Local ${cadastros.salvarLocal(id, nome, unidade?.takeIf { it > 0 }, ativo).nome} alterado." }

    /** Erro de validação volta para a tela com a mensagem; deu certo, com a mensagem de sucesso. */
    private fun executar(redirect: RedirectAttributes, destino: String, acao: () -> String): String {
        try {
            redirect.addFlashAttribute("msg", acao())
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        } catch (e: NoSuchElementException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:$destino"
    }
}
