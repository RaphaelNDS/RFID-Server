package org.example.controller

import org.example.model.TipoMovimento
import org.example.request.MovimentoExpedicaoRequest
import org.example.repository.LeituraAndroidRepository
import org.example.service.LeituraAndroidService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/expedicao")
@PreAuthorize("hasRole('USER')")
class ExpedicaoController(
    private val leituraRepo: LeituraAndroidRepository,
    private val leituraService: LeituraAndroidService
) {

    @GetMapping
    fun tela(model: Model): String {
        model.addAttribute("leituras", leituraRepo.findTop100ByOrderByDataHoraDesc())
        model.addAttribute("tiposEntrada", listOf("REMESSA_CONSERTO", "RETORNO_LOCACAO"))
        model.addAttribute("entrada", TipoMovimento.ENTRADA.name)
        model.addAttribute("saida", TipoMovimento.SAIDA.name)
        return "expedicao"
    }

    @PostMapping("/{leituraId}/movimento")
    fun registrarMovimento(
        @PathVariable leituraId: Long,
        req: MovimentoExpedicaoRequest,
        ra: RedirectAttributes
    ): String {
        return try {
            leituraService.registrarMovimento(leituraId, req)
            ra.addFlashAttribute("msg", "Movimento registrado com sucesso")
            "redirect:/expedicao"
        } catch (e: Exception) {
            ra.addFlashAttribute("erro", e.message ?: "Erro ao registrar movimento")
            "redirect:/expedicao"
        }
    }
}
