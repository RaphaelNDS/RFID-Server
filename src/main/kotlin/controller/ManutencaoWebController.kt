package org.example.controller

import org.example.model.ResultadoManutencao
import org.example.model.TipoManutencao
import org.example.service.ConclusaoManutencao
import org.example.service.FiltroManutencao
import org.example.service.ManutencaoService
import org.example.service.PdfService
import org.example.service.PedidoManutencao
import org.example.service.RevisaoService
import org.example.util.FormatoBr
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.LocalDate

/**
 * Manutenção do bem (1.11.0): lista das ordens, nova ordem, a ordem (concluir ou cancelar) e a ordem em PDF.
 * Módulo Manutenção (1.15.0): Ver; abrir: Criar; concluir e cancelar: Editar.
 */
@Controller
@RequestMapping("/admin/manutencoes")
class ManutencaoWebController(
    private val service: ManutencaoService,
    private val pdf: PdfService,
    private val revisao: RevisaoService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PreAuthorize("@authService.temPermissao('MANUTENCAO','READ')")
    @GetMapping
    fun listar(
        @RequestParam(required = false) situacao: String?,
        @RequestParam(required = false) busca: String?,
        @RequestParam(defaultValue = "1") pagina: Int,
        model: Model
    ): String {
        val filtro = FiltroManutencao.de(situacao)
        val resultado = service.listar(filtro, busca, pagina - 1)
        model.addAttribute("manutencoes", resultado.content)
        model.addAttribute("filtro", filtro)
        model.addAttribute("filtros", FiltroManutencao.entries)
        model.addAttribute("busca", busca?.trim().orEmpty())
        model.addAttribute("atrasadas", service.contarAtrasadas())
        model.addAttribute("fmt", FormatoBr)
        model.adicionarPaginacao(resultado, "/admin/manutencoes", mapOf("situacao" to filtro.name, "busca" to busca))
        model.addAttribute("regras", revisao.regrasDaTela())
        return "manutencoes"
    }

    /** 1.21.0: regra de revisão automática de um tipo; os dois vazios desligam. */
    @PreAuthorize("@authService.temPermissao('MANUTENCAO','UPDATE')")
    @PostMapping("/revisao/{tipoId}")
    fun salvarRegra(
        @PathVariable tipoId: Long, @RequestParam(required = false) locacoes: String?, @RequestParam(required = false) dias: String?,
        redirect: RedirectAttributes
    ): String {
        try {
            fun numero(v: String?, campo: String): Int? = v?.trim()?.takeIf { it.isNotEmpty() }?.let {
                it.toIntOrNull() ?: throw IllegalArgumentException("$campo precisa ser um número inteiro: $it")
            }
            val r = revisao.salvarRegra(tipoId, numero(locacoes, "Locações"), numero(dias, "Dias"))
            redirect.addFlashAttribute("msg", if (r == null) "Revisão automática desligada para o tipo." else "Revisão automática: ${r.descricao}.")
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erroRegra", e.message)
        }
        return "redirect:/admin/manutencoes#revisao-automatica"
    }

    /** Formulário; [tag] vem do botão "Manutenção" na edição da etiqueta, e [tipo] do painel (revisão vencida, 1.21.0). */
    @PreAuthorize("@authService.temPermissao('MANUTENCAO','CREATE')")
    @GetMapping("/nova")
    fun nova(@RequestParam(required = false) tag: String?, @RequestParam(required = false) tipo: String?, model: Model): String {
        val tipoValido = TipoManutencao.entries.firstOrNull { it.name == tipo }
        val motivo = if (tipoValido == TipoManutencao.PREVENTIVA && tag != null)
            service.localizar(tag.trim()).bens.singleOrNull()?.let { b -> revisao.motivoVencidaPorId(b.id) }?.let { "Revisão automática: $it" } else null
        formulario(model, tag, tipoValido?.name, motivo, null, null, null, null)
        return "manutencao-nova"
    }

    @PreAuthorize("@authService.temPermissao('MANUTENCAO','CREATE')")
    @PostMapping
    fun abrir(
        @RequestParam(required = false) codigo: String?,
        @RequestParam(required = false) tipo: String?,
        @RequestParam(required = false) problema: String?,
        @RequestParam(required = false) fornecedor: String?,
        @RequestParam(required = false) envio: String?,
        @RequestParam(required = false) previsao: String?,
        model: Model,
        redirect: RedirectAttributes
    ): String {
        val erro = try {
            val ordem = service.abrir(PedidoManutencao(codigo, TipoManutencao.entries.firstOrNull { it.name == tipo }, problema, fornecedor,
                dia(envio, "envio"), dia(previsao, "previsão de retorno")))
            redirect.addFlashAttribute("msg", "Manutenção nº ${ordem.id} aberta: o bem está Em manutenção até a conclusão.")
            return "redirect:/admin/manutencoes/${ordem.id}"
        } catch (e: IllegalArgumentException) {
            e.message
        } catch (e: DataIntegrityViolationException) {
            log.warn("Manutenção recusada na gravação: o bem entrou em outra manutenção no mesmo instante")
            "Este bem acabou de entrar em outra manutenção. Confira e tente de novo."
        }
        log.info("Manutenção recusada: {}", erro)
        formulario(model, codigo, tipo, problema, fornecedor, envio, previsao, erro)
        return "manutencao-nova"
    }

    @PreAuthorize("@authService.temPermissao('MANUTENCAO','READ')")
    @GetMapping("/{id}")
    fun detalhe(@PathVariable id: Long, model: Model): String {
        model.addAttribute("r", service.detalhar(id))
        model.addAttribute("hoje", service.hoje())
        model.addAttribute("resultados", ResultadoManutencao.entries)
        model.addAttribute("fmt", FormatoBr)
        return "manutencao"
    }

    @PreAuthorize("@authService.temPermissao('MANUTENCAO','UPDATE')")
    @PostMapping("/{id}/conclusao")
    fun concluir(
        @PathVariable id: Long,
        @RequestParam(required = false) resultado: String?,
        @RequestParam(required = false) servico: String?,
        @RequestParam(required = false) custo: String?,
        @RequestParam(required = false) data: String?,
        redirect: RedirectAttributes
    ): String {
        try {
            val r = ResultadoManutencao.entries.firstOrNull { it.name == resultado }
            val ordem = service.concluir(id, ConclusaoManutencao(r, servico, custo, dia(data, "conclusão")))
            redirect.addFlashAttribute("msg", "Manutenção nº $id concluída: o bem voltou a ficar ${ordem.situacaoAnterior.rotulo}." +
                if (ordem.resultado == ResultadoManutencao.SEM_CONSERTO) " Sem conserto: se for o caso, dê baixa pela edição da etiqueta." else "")
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:/admin/manutencoes/$id"
    }

    @PreAuthorize("@authService.temPermissao('MANUTENCAO','UPDATE')")
    @PostMapping("/{id}/cancelamento")
    fun cancelar(@PathVariable id: Long, @RequestParam(required = false) motivo: String?, redirect: RedirectAttributes): String {
        try {
            val ordem = service.cancelar(id, motivo)
            redirect.addFlashAttribute("msg", "Manutenção nº $id cancelada: o bem voltou a ficar ${ordem.situacaoAnterior.rotulo}.")
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:/admin/manutencoes/$id"
    }

    @PreAuthorize("@authService.temPermissao('MANUTENCAO','READ')")
    @GetMapping("/{id}/ordem.pdf")
    @ResponseBody
    fun ordemPdf(@PathVariable id: Long, auth: Authentication): ResponseEntity<ByteArray> {
        val r = service.detalhar(id)
        val bytes = pdf.gerar("pdf/ordem-manutencao", mapOf("m" to r.manutencao))
        log.info("{} gerou a ordem de manutenção nº {} em PDF", auth.name, id)
        return pdf.resposta(bytes, "ordem-manutencao-$id.pdf")
    }

    private fun formulario(
        model: Model, codigo: String?, tipo: String?, problema: String?, fornecedor: String?, envio: String?, previsao: String?, erro: String?
    ) {
        val hoje = service.hoje()
        val limpo = codigo?.trim().orEmpty()
        model.addAttribute("codigo", limpo)
        model.addAttribute("bem", if (limpo.isEmpty()) null else service.localizar(limpo).bens.singleOrNull())
        model.addAttribute("tipos", TipoManutencao.entries)
        model.addAttribute("tipo", tipo ?: TipoManutencao.CORRETIVA.name)
        model.addAttribute("problema", problema)
        model.addAttribute("fornecedor", fornecedor)
        model.addAttribute("envio", runCatching { dia(envio, "envio") }.getOrNull() ?: hoje)
        model.addAttribute("previsao", runCatching { dia(previsao, "previsão") }.getOrNull())
        model.addAttribute("hoje", hoje)
        if (erro != null) model.addAttribute("erro", erro)
    }

    private fun dia(texto: String?, campo: String): LocalDate? = texto?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { LocalDate.parse(it) }.getOrElse { throw IllegalArgumentException("Data de $campo inválida") }
    }
}
