package org.example.controller

import org.example.service.DadosCliente
import org.example.service.LocacaoService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/** Clientes da locadora (1.12.0). Ver: RFID · Ver; cadastrar e alterar: RFID · Editar (os mesmos dos pedidos). */
@Controller
@RequestMapping("/admin/clientes")
class ClienteWebController(private val service: LocacaoService) {

    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping
    fun listar(@RequestParam(required = false) busca: String?, model: Model): String {
        val texto = busca?.trim().orEmpty()
        val todos = service.listarClientes()
        model.addAttribute("clientes", if (texto.isEmpty()) todos else todos.filter {
            it.nome.contains(texto, ignoreCase = true) || (it.documento != null && texto.filter(Char::isDigit).let { d -> d.isNotEmpty() && it.documento!!.contains(d) })
        })
        model.addAttribute("busca", texto)
        model.addAttribute("total", todos.size)
        return "clientes"
    }

    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @PostMapping
    fun cadastrar(
        @RequestParam(required = false) nome: String?, @RequestParam(required = false) documento: String?,
        @RequestParam(required = false) contato: String?, @RequestParam(required = false) telefone: String?,
        @RequestParam(required = false) email: String?, @RequestParam(required = false) endereco: String?,
        @RequestParam(required = false) observacao: String?, redirect: RedirectAttributes
    ): String {
        try {
            val c = service.salvarCliente(null, DadosCliente(nome, documento, contato, telefone, email, endereco, observacao))
            redirect.addFlashAttribute("msg", "Cliente ${c.nome} cadastrado.")
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:/admin/clientes"
    }

    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @PostMapping("/{id}")
    fun alterar(
        @PathVariable id: Long,
        @RequestParam(required = false) nome: String?, @RequestParam(required = false) documento: String?,
        @RequestParam(required = false) contato: String?, @RequestParam(required = false) telefone: String?,
        @RequestParam(required = false) email: String?, @RequestParam(required = false) endereco: String?,
        @RequestParam(required = false) observacao: String?, @RequestParam(defaultValue = "false") ativo: Boolean,
        redirect: RedirectAttributes
    ): String {
        try {
            val c = service.salvarCliente(id, DadosCliente(nome, documento, contato, telefone, email, endereco, observacao))
            if (c.ativo != ativo) service.ativarCliente(id, ativo)
            redirect.addFlashAttribute("msg", "Cliente ${c.nome} alterado.")
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:/admin/clientes"
    }
}
