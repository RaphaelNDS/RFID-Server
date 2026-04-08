package org.example.controller

import org.example.request.MarcaRequest
import org.example.service.CatalogoService
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping


@Controller
@RequestMapping("/admin/marca")
class MarcaController(
    private val catalogoService: CatalogoService
) {

    @PostMapping("/salvar")
    fun salvar(req: MarcaRequest): String {
        catalogoService.salvarMarca(req)
        return "redirect:/admin/catalogo"
    }
}