package org.example.controller

import org.example.request.CadastroRequest
import org.example.service.CatalogoService
import org.example.service.HistoricoEtiquetaService
import org.example.service.EdicaoConcorrenteException
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
            tipoEquipamentoId = tag.tipoEquipamentoId, marcaId = tag.marcaId, modeloId = tag.modeloId, versao = tag.versao
        ))
        return formulario(id, model)
    }

    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @PostMapping("/{id}/editar")
    fun salvar(@PathVariable id: Long, @ModelAttribute("cadastro") cadastro: CadastroRequest, model: Model, redirect: RedirectAttributes): String {
        return try {
            val mudaram = try {
                rfidService.editarTag(id, cadastro)
            } catch (e: org.springframework.dao.OptimisticLockingFailureException) {
                // As duas pessoas salvaram no mesmo instante: a conferência da versão passou para as duas e o banco
                // recusou a segunda na gravação (@Version). Antes ia para a página genérica de erro, sem o formulário
                org.slf4j.LoggerFactory.getLogger(javaClass).warn("Edição da etiqueta {} recusada na gravação: salva por outra pessoa no mesmo instante", id)
                throw EdicaoConcorrenteException("Esta etiqueta foi alterada por outra pessoa no mesmo instante (veja o histórico). " +
                    "Os dados abaixo são os seus: confira e salve de novo para gravar por cima.")
            }
            redirect.addFlashAttribute("msg",
                if (mudaram.isEmpty()) "Nada foi alterado na etiqueta ${rfidService.buscarPorId(id).tagReal}."
                else "Etiqueta alterada (${mudaram.joinToString()}). A mudança ficou no histórico.")
            "redirect:/admin/tags-cadastradas"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("erro", e.message)
            val atual = rfidService.buscarPorId(id)
            cadastro.tag = atual.tagReal
            // Mudada por outra pessoa: a tela volta com o que foi digitado e a versão atual (salvar de novo grava por cima)
            if (e is EdicaoConcorrenteException) cadastro.versao = atual.versao
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
