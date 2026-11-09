package org.example.controller

import org.example.request.CadastroRequest
import org.example.service.CatalogoService
import org.springframework.security.access.prepost.PreAuthorize
import org.example.service.RfidService
import org.example.util.normalizarEpc
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin/cadastro")
class CadastroController(
    private val rfidService: RfidService,
    private val catalogoService: CatalogoService,
    private val cadastros: org.example.service.PatrimonioCadastrosService? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Único valor aceito em "origem": a lista de pendentes (qualquer outro volta para as cadastradas). */
        const val ORIGEM_PENDENTES = "pendentes"
    }

    @PreAuthorize("@authService.temPermissao('RFID','CREATE')")
    @GetMapping
    fun form(
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) origem: String?,
        @RequestParam(required = false) busca: String?,
        @RequestParam(required = false) pagina: String?,
        model: Model
    ): String {

        val cadastro = CadastroRequest()

        if (tag != null) {
            cadastro.tag = tag
        }

        model.addAttribute("cadastro", cadastro)
        carregarCombos(model)
        retorno(model, origem, busca, pagina)

        return "cadastro"
    }

    /**
     * Depois de cadastrar, avisa qual etiqueta foi cadastrada (antes voltava para a lista sem mensagem nenhuma) e,
     * se veio de Pendentes (origem=pendentes), volta para Pendentes na mesma busca e página, para cadastrar a próxima.
     */
    @PreAuthorize("@authService.temPermissao('RFID','CREATE')")
    @PostMapping("/salvar")
    fun salvar(
        @ModelAttribute("cadastro") cadastro: CadastroRequest,
        @RequestParam(required = false) origem: String?,
        @RequestParam(required = false) busca: String?,
        @RequestParam(required = false) pagina: String?,
        model: Model,
        redirect: RedirectAttributes
    ): String {
        return try {
            rfidService.cadastrarTag(cadastro)
            val epc = normalizarEpc(cadastro.tag)
            redirect.addFlashAttribute("msg", "Etiqueta $epc cadastrada.")
            if (origem == ORIGEM_PENDENTES) {
                log.info("Etiqueta {} cadastrada pela lista de pendentes: voltando para Pendentes", epc)
                RetornoLista.de(busca, pagina).noRedirect(redirect)
                "redirect:/admin/tags-pendentes"
            } else {
                "redirect:/admin/tags-cadastradas"
            }
        } catch (e: IllegalArgumentException) {
            // Volta para o formulário com o que foi digitado e a mensagem de erro
            model.addAttribute("erro", e.message)
            carregarCombos(model)
            retorno(model, origem, busca, pagina)
            "cadastro"
        }
    }

    /** De onde veio (validado): Cancelar e o redirect depois de salvar voltam para lá. */
    private fun retorno(model: Model, origem: String?, busca: String?, pagina: String?) {
        if (origem == ORIGEM_PENDENTES) {
            val retorno = RetornoLista.de(busca, pagina)
            model.addAttribute("origem", ORIGEM_PENDENTES)
            retorno.noModelo(model)
            model.addAttribute("linkVoltar", retorno.link("/admin/tags-pendentes"))
        } else {
            model.addAttribute("linkVoltar", "/admin/tags-cadastradas")
        }
    }

    private fun carregarCombos(model: Model) {
        model.addAttribute("tipos", catalogoService.listarTipos())
        model.addAttribute("marcas", catalogoService.listarMarcas())
        model.addAttribute("modelos", catalogoService.listarModelos())
        // Gestão de patrimônio (1.6.0): local, responsável e situação
        model.addAttribute("opcoesLocal", cadastros?.opcoesDeLocal().orEmpty())
        model.addAttribute("opcoesResponsavel", cadastros?.responsaveisAtivos().orEmpty())
        model.addAttribute("situacoesEditaveis", org.example.model.SituacaoBem.EDITAVEIS)
    }
}
