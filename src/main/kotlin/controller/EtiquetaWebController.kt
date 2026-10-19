package org.example.controller

import org.example.request.CadastroRequest
import org.example.service.CatalogoService
import org.example.service.HistoricoEtiquetaService
import org.example.service.RfidService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Edição, exclusão e histórico das etiquetas cadastradas. Permissões do módulo RFID: Editar para alterar,
 * Excluir para excluir, Ver para o histórico. Toda mudança vai para o histórico (HistoricoEtiquetaService).
 */
@Controller
@RequestMapping("/admin/etiquetas")
class EtiquetaWebController(
    private val rfidService: RfidService,
    private val catalogoService: CatalogoService,
    private val historico: HistoricoEtiquetaService
) {

    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @GetMapping("/{id}/editar")
    fun editar(@PathVariable id: Long, model: Model): String {
        val tag = rfidService.buscarPorId(id)
        model.addAttribute("cadastro", CadastroRequest(
            tag = tag.tagReal, codigoInterno = tag.codigoInterno, patrimonio = tag.patrimonio, numeroSerie = tag.numeroSerie,
            tipoEquipamentoId = tag.tipoEquipamentoId, marcaId = tag.marcaId, modeloId = tag.modeloId
        ))
        return formulario(id, model)
    }

    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @PostMapping("/{id}/editar")
    fun salvar(@PathVariable id: Long, @ModelAttribute("cadastro") cadastro: CadastroRequest, model: Model, redirect: RedirectAttributes): String {
        return try {
            val mudaram = rfidService.editarTag(id, cadastro)
            redirect.addFlashAttribute("msg",
                if (mudaram.isEmpty()) "Nada foi alterado na etiqueta ${rfidService.buscarPorId(id).tagReal}."
                else "Etiqueta alterada (${mudaram.joinToString()}). A mudança ficou no histórico.")
            "redirect:/admin/tags-cadastradas"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("erro", e.message)
            cadastro.tag = rfidService.buscarPorId(id).tagReal
            formulario(id, model)
        }
    }

    @PreAuthorize("@authService.temPermissao('RFID','DELETE')")
    @PostMapping("/{id}/excluir")
    fun excluir(@PathVariable id: Long, redirect: RedirectAttributes): String {
        val tag = rfidService.excluirTag(id)
        redirect.addFlashAttribute("msg", "Etiqueta ${tag.tagReal} excluída. O histórico dela continua disponível.")
        return "redirect:/admin/tags-cadastradas"
    }

    /** Histórico de uma etiqueta (pelo EPC, inclusive excluída) ou, sem filtro, as últimas mudanças. */
    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping("/historico")
    fun historico(@RequestParam(required = false) tag: String?, model: Model): String {
        val filtro = tag?.trim()?.takeIf { it.isNotEmpty() }
        model.addAttribute("tagFiltrada", filtro)
        model.addAttribute("linhas", if (filtro != null) historico.historico(filtro) else historico.recentes())
        return "historico-etiquetas"
    }

    private fun formulario(id: Long, model: Model): String {
        model.addAttribute("etiquetaId", id)
        model.addAttribute("tipos", catalogoService.listarTipos())
        return "editar-etiqueta"
    }
}
