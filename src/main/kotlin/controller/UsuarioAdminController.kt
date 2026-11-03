package org.example.controller

import org.example.model.Role
import org.example.request.CadastroUsuarioRequest
import org.example.security.UserPrincipal
import org.example.service.RecuperacaoSenhaService
import org.example.service.UsuarioService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes


@PreAuthorize("hasRole('ADMIN')")
@Controller
@RequestMapping("/admin/usuarios")
class UsuarioAdminController(
    private val service: UsuarioService,
    private val recuperacao: RecuperacaoSenhaService
) {

    @GetMapping
    fun lista(model: Model): String {
        model.addAttribute("usuarios", service.listarTodos())
        model.addAttribute("pedidos", recuperacao.pendentes())
        return "admin-usuarios"
    }

    @GetMapping("/novo")
    fun novo(): String = "admin-usuarios-cadastro"

    @PostMapping("/cadastrar")
    fun criar(req: CadastroUsuarioRequest, model: Model, redirect: RedirectAttributes): String {

        return try {
            service.cadastrar(req)

            // Flash attribute sobrevive ao redirect (model.addAttribute se perdia)
            redirect.addFlashAttribute("msg", "Usuário ${req.email} criado com sucesso!")
            "redirect:/admin/usuarios/novo"

        } catch (e: IllegalArgumentException) {
            // Volta ao formulário com o que foi digitado (menos a senha)
            model.addAttribute("erro", e.message)
            model.addAttribute("form", req.copy(senha = ""))
            "admin-usuarios-cadastro"
        }
    }

    /** Gera uma senha temporária (com ou sem pedido de "Esqueceu a senha?"). A senha aparece uma única vez. */
    @PostMapping("/{id}/senha-temporaria")
    fun senhaTemporaria(@PathVariable id: Long, redirect: RedirectAttributes): String {
        try {
            redirect.addFlashAttribute("senhaTemporaria", recuperacao.gerarSenhaTemporaria(id))
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:/admin/usuarios"
    }

    @GetMapping("/{id}/editar")
    fun editar(@PathVariable id: Long, model: Model): String {
        val usuario = service.porId(id)
        model.addAttribute("usuario", usuario)
        // Pré-marca as caixas com o que o usuário tem hoje (no perfil Personalizado é o que aparece)
        model.addAttribute("telas", usuario.permissoes.map { it.modulo.name }.distinct())
        model.addAttribute("acoes", usuario.permissoes.map { it.acao.name }.distinct())
        return "admin-usuarios-editar"
    }

    @PostMapping("/{id}/editar")
    fun salvarEdicao(
        @PathVariable id: Long,
        @RequestParam nome: String,
        @RequestParam role: Role,
        @RequestParam(required = false) telas: List<String>?,
        @RequestParam(required = false) permissoes: List<String>?,
        @AuthenticationPrincipal quem: UserPrincipal,
        redirect: RedirectAttributes
    ): String {
        return try {
            service.editar(id, nome, role, telas, permissoes, quem.id)
            redirect.addFlashAttribute("msg", "Usuário alterado. As permissões novas valem no próximo login dele na web e já na próxima ação no coletor.")
            "redirect:/admin/usuarios"
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
            "redirect:/admin/usuarios/{id}/editar"
        } catch (e: IllegalStateException) {
            redirect.addFlashAttribute("erro", e.message)
            "redirect:/admin/usuarios/{id}/editar"
        }
    }

    /** Desativa: o usuário não entra mais e as sessões abertas dele (web e coletores) caem na hora. */
    @PostMapping("/{id}/desativar")
    fun desativar(@PathVariable id: Long, @AuthenticationPrincipal quem: UserPrincipal, redirect: RedirectAttributes): String {
        try {
            service.desativar(id, quem.id)
            redirect.addFlashAttribute("msg", "Usuário desativado: ele não entra mais e as sessões abertas dele foram encerradas.")
        } catch (e: IllegalStateException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:/admin/usuarios"
    }

    @PostMapping("/{id}/reativar")
    fun reativar(@PathVariable id: Long, redirect: RedirectAttributes): String {
        service.reativar(id)
        redirect.addFlashAttribute("msg", "Usuário reativado: ele volta a entrar com a mesma senha.")
        return "redirect:/admin/usuarios"
    }

    @PostMapping("/{id}/descartar-pedido")
    fun descartarPedido(@PathVariable id: Long, redirect: RedirectAttributes): String {
        try {
            recuperacao.descartarPedido(id)
            redirect.addFlashAttribute("msg", "Pedido de nova senha descartado. A senha da pessoa não mudou.")
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:/admin/usuarios"
    }
}
