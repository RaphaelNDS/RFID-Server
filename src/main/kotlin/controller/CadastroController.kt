package org.example.controller

import jakarta.transaction.Transactional
import org.example.request.CadastroRequest
import org.example.service.CatalogoService

import org.example.service.RfidService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*

@Controller
@RequestMapping("/admin/cadastro")
class CadastroController(
    private val rfidService: RfidService,
    private val catalogoService: CatalogoService
) {

    @GetMapping
    fun form(
        @RequestParam(required = false) tag: String?,
        model: Model
    ): String {

        val cadastro = CadastroRequest()

        if (tag != null) {
            cadastro.tag = tag
        }

        model.addAttribute("cadastro", cadastro)
        carregarCombos(model)

        return "cadastro"
    }

    @PostMapping("/salvar")
    fun salvar(@ModelAttribute cadastro: CadastroRequest): String {
        rfidService.cadastrarTag(cadastro)
        return "redirect:/admin/tags-cadastradas"
    }

    private fun carregarCombos(model: Model) {
        model.addAttribute("tipos", catalogoService.listarTipos())
        model.addAttribute("marcas", catalogoService.listarMarcas())
        model.addAttribute("modelos", catalogoService.listarModelos())
    }
}
