package org.example.controller

import org.example.request.ModeloRequest
import org.example.service.CatalogoService
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping


@Controller
@RequestMapping("/admin/modelo")
class ModeloController(
    private val catalogoService: CatalogoService
) {

    @PostMapping("/salvar")
    fun salvar(req: ModeloRequest): String {
        catalogoService.salvarModelo(req)
        return "redirect:/admin/catalogo"
    }
}

