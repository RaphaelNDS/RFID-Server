package org.example.controller.web

import org.example.request.CadastroRequest
import org.example.service.CatalogoService
import org.example.service.RfidService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping

// Cadastro de etiquetas exige a permissão RFID_CREATE (mesma regra do CadastroController)
@PreAuthorize("@authService.temPermissao('RFID','CREATE')")
@Controller
class RfidWebController(
    private val service: RfidService,
    private val catalogoService: CatalogoService
) {

    @GetMapping("/cadastro/form")
    fun telaCadastro(model: Model): String {

        model.addAttribute(
            "cadastro",
            CadastroRequest("", "", "", "")
        )

        model.addAttribute("tipos", catalogoService.listarTipos())
        model.addAttribute("marcas", catalogoService.listarMarcas())
        model.addAttribute("modelos", catalogoService.listarModelos())

        model.addAttribute("lista", service.listarParaAdmin())

        return "cadastro"
    }



    @PostMapping("/cadastro")
    fun salvar(@ModelAttribute("cadastro") req: CadastroRequest, model: Model): String {

        try {
            service.cadastrarTag(req)
            model.addAttribute("msg", "Etiqueta cadastrada com sucesso!")
            model.addAttribute("cadastro", CadastroRequest())
        } catch (e: IllegalArgumentException) {
            model.addAttribute("erro", e.message)
        }

        model.addAttribute("tipos", catalogoService.listarTipos())
        model.addAttribute("marcas", catalogoService.listarMarcas())
        model.addAttribute("modelos", catalogoService.listarModelos())

        model.addAttribute("lista", service.listarParaAdmin())

        return "cadastro"
    }
}


