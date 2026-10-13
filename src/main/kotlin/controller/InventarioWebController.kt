package org.example.controller

import org.example.service.InventarioService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/** Telas de acompanhamento dos inventários feitos pelos coletores. Mesma permissão da tela de Leituras. */
@Controller
@RequestMapping("/admin/inventarios")
class InventarioWebController(
    private val service: InventarioService
) {

    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @GetMapping
    fun listar(@RequestParam(required = false) local: Long?, model: Model): String {
        model.addAttribute("inventarios", service.listar(local))
        model.addAttribute("locais", service.locaisCadastrados())
        model.addAttribute("localSelecionado", local)
        return "inventarios"
    }

    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @GetMapping("/{id}")
    fun detalhe(@PathVariable id: String, model: Model): String {
        val detalhe = service.detalhar(id)
        model.addAttribute("inventario", detalhe.resumo)
        model.addAttribute("itens", detalhe.itens)
        return "inventario-detalhe"
    }
}
