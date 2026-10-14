package org.example.controller

import org.example.service.CatalogoService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

/**
 * Tela do catálogo. Criar, editar e excluir tipos, marcas e modelos é feito pela própria tela,
 * chamando a API /api/catalogo (CatalogoApiController).
 */
@Controller
@RequestMapping("/admin/catalogo")
class CatalogoViewController(
    private val catalogoService: CatalogoService
) {

    @PreAuthorize("@authService.temPermissao('CATALOGO','READ')")
    @GetMapping
    fun catalogo(model: Model): String {
        model.addAttribute("tipos", catalogoService.listarTipos())
        model.addAttribute("marcas", catalogoService.listarMarcas())
        model.addAttribute("modelos", catalogoService.listarModelos())
        return "catalogo"
    }
}
