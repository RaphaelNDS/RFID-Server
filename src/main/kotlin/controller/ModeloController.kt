package org.example.controller

import org.example.request.ModeloRequest
import org.example.service.CatalogoService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping


@PreAuthorize("@authService.temPermissao('CATALOGO','CREATE')")
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

