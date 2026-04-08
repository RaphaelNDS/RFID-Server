package org.example.controller.web

import org.example.request.CadastroRequest
import org.example.service.CatalogoService
import org.example.service.RfidService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping

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
    fun salvar(req: CadastroRequest, model: Model): String {

        service.cadastrarTag(req)

        model.addAttribute("msg", "Etiqueta cadastrada com sucesso!")

        model.addAttribute("tipos", catalogoService.listarTipos())
        model.addAttribute("marcas", catalogoService.listarMarcas())
        model.addAttribute("modelos", catalogoService.listarModelos())

        model.addAttribute("lista", service.listarParaAdmin())

        return "cadastro"
    }
}