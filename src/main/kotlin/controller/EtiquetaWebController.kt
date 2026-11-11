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
    private val historico: HistoricoEtiquetaService,
    private val cadastros: org.example.service.PatrimonioCadastrosService? = null,
    private val auditoria: org.example.service.AuditoriaService? = null,
    // 1.8.0: valor contábil do bem na tela de edição
    private val valores: org.example.service.ValorPatrimonialService? = null,
    // 1.9.0: fotos e anexos do bem
    private val anexos: org.example.service.AnexoBemService? = null,
    // 1.10.0: empréstimo aberto do bem
    private val emprestimos: org.example.service.EmprestimoService? = null
) {

    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @GetMapping("/{id}/editar")
    fun editar(
        @PathVariable id: Long,
        @RequestParam(required = false) busca: String?,
        @RequestParam(required = false) pagina: String?,
        model: Model
    ): String {
        val tag = rfidService.buscarPorId(id)
        model.addAttribute("cadastro", CadastroRequest(
            tag = tag.tagReal, codigoInterno = tag.codigoInterno, patrimonio = tag.patrimonio, numeroSerie = tag.numeroSerie,
            tipoEquipamentoId = tag.tipoEquipamentoId, marcaId = tag.marcaId, modeloId = tag.modeloId, versao = tag.versao,
            localId = tag.localId, responsavelId = tag.responsavelId, situacao = tag.situacao.name,
            valorAquisicao = tag.valorAquisicao?.let { org.example.util.FormatoBr.numero(it) },
            dataAquisicao = tag.dataAquisicao?.toString(), notaFiscal = tag.notaFiscal
        ))
        return formulario(id, RetornoLista.de(busca, pagina), model)
    }

    /**
     * Salvar volta para a lista na mesma busca e página de onde a pessoa veio (campos busca e pagina do formulário,
     * validados em [RetornoLista]); antes voltava sempre para a lista limpa, na primeira página.
     */
    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @PostMapping("/{id}/editar")
    fun salvar(
        @PathVariable id: Long,
        @ModelAttribute("cadastro") cadastro: CadastroRequest,
        @RequestParam(required = false) busca: String?,
        @RequestParam(required = false) pagina: String?,
        model: Model,
        redirect: RedirectAttributes
    ): String {
        val retorno = RetornoLista.de(busca, pagina)
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
            retorno.noRedirect(redirect)
            "redirect:/admin/tags-cadastradas"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("erro", e.message)
            val atual = rfidService.buscarPorId(id)
            cadastro.tag = atual.tagReal
            // Mudada por outra pessoa: a tela volta com o que foi digitado e a versão atual (salvar de novo grava por cima)
            if (e is EdicaoConcorrenteException) cadastro.versao = atual.versao
            formulario(id, retorno, model)
        }
    }

    @PreAuthorize("@authService.temPermissao('RFID','DELETE')")
    @PostMapping("/{id}/excluir")
    fun excluir(@PathVariable id: Long, redirect: RedirectAttributes): String = try {
        val tag = rfidService.excluirTag(id)
        redirect.addFlashAttribute("msg", "Etiqueta ${tag.tagReal} excluída. O histórico dela continua disponível.")
        "redirect:/admin/tags-cadastradas"
    } catch (e: IllegalArgumentException) {
        // Bem emprestado (1.10.0): volta para a edição com o motivo
        redirect.addFlashAttribute("erro", e.message)
        "redirect:/admin/etiquetas/$id/editar"
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

    /** Baixa patrimonial (1.6.0): motivo obrigatório; data vazia = hoje. */
    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @PostMapping("/{id}/baixa")
    fun darBaixa(
        @PathVariable id: Long,
        @RequestParam(required = false) motivo: String?,
        @RequestParam(required = false) data: String?,
        @RequestParam(required = false) observacao: String?,
        redirect: RedirectAttributes
    ): String = try {
        val m = org.example.model.MotivoBaixa.de(motivo) ?: throw IllegalArgumentException("Escolha o motivo da baixa")
        val dia = data?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { java.time.LocalDate.parse(it) }.getOrElse { throw IllegalArgumentException("Data da baixa inválida") }
        }
        val tag = rfidService.darBaixa(id, m, dia, observacao)
        auditoria?.registrar(org.example.model.AreaAuditoria.CATALOGO, "Baixa de bem", "${tag.tagReal} (patrimônio ${tag.patrimonio.ifBlank { "—" }})",
            "${m.rotulo} em ${tag.baixaEm}${tag.baixaObservacao?.let { " · $it" } ?: ""}")
        redirect.addFlashAttribute("msg", "Baixa do bem ${tag.patrimonio.ifBlank { tag.tagReal }} registrada (${m.rotulo}). Ele continua no filtro \"Baixados\" e no histórico.")
        "redirect:/admin/tags-cadastradas"
    } catch (e: IllegalArgumentException) {
        redirect.addFlashAttribute("erro", e.message)
        "redirect:/admin/etiquetas/$id/editar"
    }

    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @PostMapping("/{id}/baixa/desfazer")
    fun desfazerBaixa(@PathVariable id: Long, redirect: RedirectAttributes): String = try {
        val tag = rfidService.desfazerBaixa(id)
        auditoria?.registrar(org.example.model.AreaAuditoria.CATALOGO, "Baixa de bem desfeita", "${tag.tagReal} (patrimônio ${tag.patrimonio.ifBlank { "—" }})")
        redirect.addFlashAttribute("msg", "Baixa desfeita: o bem ${tag.patrimonio.ifBlank { tag.tagReal }} voltou a ficar Em uso.")
        "redirect:/admin/etiquetas/$id/editar"
    } catch (e: IllegalArgumentException) {
        redirect.addFlashAttribute("erro", e.message)
        "redirect:/admin/etiquetas/$id/editar"
    }

    private fun formulario(id: Long, retorno: RetornoLista, model: Model): String {
        val etiqueta = rfidService.buscarPorId(id)
        model.addAttribute("etiquetaId", id)
        model.addAttribute("etiqueta", etiqueta)
        model.addAttribute("baixado", etiqueta.baixado)
        retorno.noModelo(model)
        model.addAttribute("linkVoltar", retorno.link("/admin/tags-cadastradas"))
        model.addAttribute("tipos", catalogoService.listarTipos())
        // Gestão de patrimônio (1.6.0): inclui os desativados (o bem pode já estar num deles)
        model.addAttribute("opcoesLocal", cadastros?.opcoesDeLocal(incluirInativos = true).orEmpty())
        model.addAttribute("opcoesResponsavel", cadastros?.listarResponsaveis().orEmpty())
        model.addAttribute("situacoesEditaveis", org.example.model.SituacaoBem.EDITAVEIS)
        model.addAttribute("motivosBaixa", org.example.model.MotivoBaixa.entries)
        model.addAttribute("hoje", java.time.LocalDate.now())
        model.addAttribute("depreciacao", valores?.depreciacaoDe(etiqueta))
        model.addAttribute("fmt", org.example.util.FormatoBr)
        model.addAttribute("anexos", anexos?.listar(id).orEmpty())
        model.addAttribute("anexoMaximo", anexos?.maximoRotulo)
        model.addAttribute("anexoMaximoPorBem", anexos?.maximoPorBem)
        model.addAttribute("emprestimo", emprestimos?.abertoDoBem(id))
        return "editar-etiqueta"
    }
}
