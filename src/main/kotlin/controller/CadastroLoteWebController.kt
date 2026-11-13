package org.example.controller

import org.example.model.SituacaoBem
import org.example.request.CadastroRequest
import org.example.service.CadastroLoteService
import org.example.service.CatalogoService
import org.example.service.PatrimonioCadastrosService
import org.example.service.Sequencia
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/** Cadastro em lote das etiquetas pendentes (1.29.0). Quem pode cadastrar etiquetas (Etiquetas · Criar). */
@Controller
@RequestMapping("/admin/tags-pendentes/lote")
class CadastroLoteWebController(
    private val lote: CadastroLoteService,
    private val catalogo: CatalogoService,
    private val cadastros: PatrimonioCadastrosService
) {

    /** As marcadas na lista de pendentes ([etiquetas]) ou todas ([todas]). */
    @PreAuthorize("@authService.temPermissao('RFID','CREATE')")
    @GetMapping
    fun formulario(
        @RequestParam(required = false) etiquetas: List<String>?, @RequestParam(defaultValue = "false") todas: Boolean,
        model: Model, redirect: RedirectAttributes
    ): String {
        val escolhidas = if (todas) lote.todasPendentes() else lote.pendentesDentre(etiquetas.orEmpty())
        if (escolhidas.isEmpty()) {
            redirect.addFlashAttribute("msg", "Marque as etiquetas pendentes que quer cadastrar em lote.")
            return "redirect:/admin/tags-pendentes"
        }
        preencher(model, escolhidas, CadastroRequest(), null, "1", null, null)
        return "cadastro-lote"
    }

    @PreAuthorize("@authService.temPermissao('RFID','CREATE')")
    @PostMapping
    fun cadastrar(
        @RequestParam etiquetas: List<String>,
        @ModelAttribute("cadastro") cadastro: CadastroRequest,
        @RequestParam(required = false) codigoPrefixo: String?, @RequestParam(required = false) codigoInicio: String?,
        @RequestParam(required = false) patrimonioPrefixo: String?, @RequestParam(required = false) patrimonioInicio: String?,
        model: Model, redirect: RedirectAttributes
    ): String {
        val escolhidas = lote.pendentesDentre(etiquetas)
        return try {
            val n = lote.cadastrar(escolhidas, cadastro, Sequencia.de(codigoPrefixo, codigoInicio), Sequencia.de(patrimonioPrefixo, patrimonioInicio))
            redirect.addFlashAttribute("msg", "$n etiqueta(s) cadastrada(s) em lote.")
            "redirect:/admin/tags-pendentes"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("erro", e.message)
            preencher(model, escolhidas.ifEmpty { etiquetas }, cadastro, codigoPrefixo, codigoInicio, patrimonioPrefixo, patrimonioInicio)
            "cadastro-lote"
        }
    }

    private fun preencher(model: Model, etiquetas: List<String>, cadastro: CadastroRequest, codigoPrefixo: String?, codigoInicio: String?,
                          patrimonioPrefixo: String?, patrimonioInicio: String?) {
        model.addAttribute("etiquetas", etiquetas)
        model.addAttribute("cadastro", cadastro)
        model.addAttribute("codigoPrefixo", codigoPrefixo.orEmpty())
        model.addAttribute("codigoInicio", codigoInicio.orEmpty())
        model.addAttribute("patrimonioPrefixo", patrimonioPrefixo.orEmpty())
        model.addAttribute("patrimonioInicio", patrimonioInicio.orEmpty())
        model.addAttribute("maximo", CadastroLoteService.MAXIMO)
        model.addAttribute("tipos", catalogo.listarTipos())
        model.addAttribute("opcoesLocal", cadastros.opcoesDeLocal())
        model.addAttribute("situacoesEditaveis", SituacaoBem.EDITAVEIS)
    }
}
