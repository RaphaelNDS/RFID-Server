package org.example.controller

import org.example.service.CatalogoService
import org.example.service.FiltroValores
import org.example.service.PatrimonioCadastrosService
import org.example.service.ValorPatrimonialService
import org.example.util.FormatoBr
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Valor patrimonial (1.8.0): os totais de valor de aquisição, depreciação e valor contábil, com filtro por unidade,
 * local e tipo, o PDF ("Bens com valor contábil") e a regra de depreciação de cada tipo de equipamento.
 * Ver: RFID Ver. Mudar a regra: Catálogo Editar (é um dado do tipo de equipamento).
 */
@Controller
@RequestMapping("/admin/valores")
class ValorWebController(
    private val valores: ValorPatrimonialService,
    private val cadastros: PatrimonioCadastrosService,
    private val catalogo: CatalogoService
) {

    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping
    fun tela(
        @RequestParam(required = false) unidade: String?,
        @RequestParam(required = false) local: String?,
        @RequestParam(required = false) tipo: String?,
        @RequestParam(required = false) baixados: Boolean?,
        model: Model
    ): String {
        val filtro = filtro(unidade, local, tipo, baixados)
        val relatorio = valores.relatorio(filtro)
        model.addAttribute("filtro", filtro)
        model.addAttribute("relatorio", relatorio)
        model.addAttribute("descricaoFiltro", valores.descricaoFiltro(filtro))
        model.addAttribute("tiposRegra", valores.tipos())
        model.addAttribute("unidades", cadastros.listarUnidades())
        model.addAttribute("opcoesLocal", cadastros.opcoesDeLocal(incluirInativos = true))
        model.addAttribute("tipos", catalogo.listarTipos())
        model.addAttribute("fmt", FormatoBr)
        return "valores"
    }

    @PreAuthorize("@authService.temPermissao('CATALOGO','UPDATE')")
    @PostMapping("/tipos/{id}")
    fun salvarRegra(
        @PathVariable id: Long,
        @RequestParam(required = false) vidaUtil: String?,
        @RequestParam(required = false) residual: String?,
        redirect: RedirectAttributes
    ): String {
        try {
            val tipo = valores.salvarRegra(id, vidaUtil, residual)
            redirect.addFlashAttribute("msg", if (tipo.vidaUtilMeses == null) "O tipo ${tipo.nome} não deprecia."
                else "Depreciação do tipo ${tipo.nome}: ${tipo.vidaUtilMeses} meses, residual ${(tipo.valorResidualPct ?: java.math.BigDecimal.ZERO).stripTrailingZeros().toPlainString()}%.")
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        } catch (e: NoSuchElementException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:/admin/valores#regras"
    }

    companion object {
        /** Os parâmetros da tela e do PDF: número inválido é ignorado (vira "sem filtro"). */
        fun filtro(unidade: String?, local: String?, tipo: String?, baixados: Boolean?) = FiltroValores(
            unidadeId = unidade?.trim()?.toLongOrNull()?.takeIf { it > 0 },
            localId = local?.trim()?.toLongOrNull()?.takeIf { it > 0 },
            tipoId = tipo?.trim()?.toLongOrNull()?.takeIf { it > 0 },
            incluirBaixados = baixados == true
        )
    }
}
