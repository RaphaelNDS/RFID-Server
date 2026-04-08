package org.example.controller

import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

@PreAuthorize("@authService.temPermissao('CATALOGO','CREATE')")
@Controller
@RequestMapping("/admin/tipo-equipamento")
class TipoEquipamentoController(
    private val catalogoService: CatalogoService
) {

    @GetMapping("/form")
    fun form(model: Model): String {

        model.addAttribute("tipo", TipoEquipamentoRequest())
        model.addAttribute("marcas", catalogoService.listarMarcas())
        model.addAttribute("modelos", catalogoService.listarModelos())

        return "tipo-equipamento-form"
    }

    @PostMapping("/salvar")
    fun salvar(req: TipoEquipamentoRequest): String {
        catalogoService.salvarTipo(req)
        return "redirect:/admin/tipo-equipamento/form"
    }
}