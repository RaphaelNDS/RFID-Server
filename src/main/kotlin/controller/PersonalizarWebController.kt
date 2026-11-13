package org.example.controller

import org.example.service.ConfiguracaoSistemaService
import org.example.service.ModuloOpcional
import org.example.service.PrimeirosPassosService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Administração · Personalizar (1.30.0): quais telas aparecem no menu e o quadro Primeiros passos do painel inicial.
 * Só o administrador: o menu muda para todo mundo.
 */
@Controller
@RequestMapping("/admin/personalizar")
@PreAuthorize("hasRole('ADMIN')")
class PersonalizarWebController(
    private val config: ConfiguracaoSistemaService,
    private val primeirosPassos: PrimeirosPassosService
) {

    @GetMapping
    fun tela(model: Model): String {
        model.addAttribute("modulos", ModuloOpcional.entries)
        model.addAttribute("desligados", config.desligados())
        model.addAttribute("passos", primeirosPassos.passos())
        model.addAttribute("passosOcultos", config.primeirosPassosOcultos())
        return "personalizar"
    }

    /** As caixas marcadas ficam no menu; as desmarcadas saem (a tela continua existindo). */
    @PostMapping("/menu")
    fun menu(@RequestParam(required = false) ligados: List<String>?, redirect: RedirectAttributes): String {
        val escolhidos = ligados.orEmpty().mapNotNull { n -> ModuloOpcional.entries.firstOrNull { it.name == n } }.toSet()
        config.definirMenu(escolhidos)
        val fora = ModuloOpcional.entries.size - escolhidos.size
        redirect.addFlashAttribute("msg", if (fora == 0) "Menu salvo: todas as telas aparecem." else "Menu salvo: $fora tela(s) fora do menu.")
        return "redirect:/admin/personalizar"
    }

    @PostMapping("/primeiros-passos")
    fun primeirosPassos(@RequestParam ocultar: Boolean, @RequestParam(required = false) voltar: String?, redirect: RedirectAttributes): String {
        config.ocultarPrimeirosPassos(ocultar)
        redirect.addFlashAttribute("msg", if (ocultar) "Primeiros passos ocultado do painel inicial." else "Primeiros passos de volta ao painel inicial.")
        return if (voltar == "painel") "redirect:/dashboard" else "redirect:/admin/personalizar"
    }
}
