package org.example.controller

import org.example.request.CadastroRequest
import org.example.service.CatalogoService
import org.springframework.security.access.prepost.PreAuthorize
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

    @PreAuthorize("@authService.temPermissao('RFID','CREATE')")
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

    @PreAuthorize("@authService.temPermissao('RFID','CREATE')")
    @PostMapping("/salvar")
    fun salvar(@ModelAttribute("cadastro") cadastro: CadastroRequest, model: Model): String {
        return try {
            rfidService.cadastrarTag(cadastro)
            "redirect:/admin/tags-cadastradas"
        } catch (e: IllegalArgumentException) {
            // Volta para o formulário com o que foi digitado e a mensagem de erro
            model.addAttribute("erro", e.message)
            carregarCombos(model)
            "cadastro"
        }
    }

    private fun carregarCombos(model: Model) {
        model.addAttribute("tipos", catalogoService.listarTipos())
        model.addAttribute("marcas", catalogoService.listarMarcas())
        model.addAttribute("modelos", catalogoService.listarModelos())
    }
}
