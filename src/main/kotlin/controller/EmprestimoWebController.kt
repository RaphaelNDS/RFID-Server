package org.example.controller

import org.example.service.EmprestimoService
import org.example.service.FiltroEmprestimo
import org.example.service.PatrimonioCadastrosService
import org.example.service.PdfService
import org.example.service.PedidoEmprestimo
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
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.LocalDate

/**
 * Empréstimo e devolução de bens (1.10.0): lista, novo empréstimo, o empréstimo (termo para imprimir e devolução) e
 * o termo em PDF. Ver: RFID · Ver; emprestar e devolver mudam a situação dos bens, então pedem RFID · Editar.
 */
@Controller
@RequestMapping("/admin/emprestimos")
class EmprestimoWebController(
    private val service: EmprestimoService,
    private val cadastros: PatrimonioCadastrosService,
    private val pdf: PdfService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping
    fun listar(
        @RequestParam(required = false) situacao: String?,
        @RequestParam(required = false) busca: String?,
        @RequestParam(defaultValue = "1") pagina: Int,
        model: Model
    ): String {
        val filtro = FiltroEmprestimo.de(situacao)
        val resultado = service.listar(filtro, busca, pagina - 1)
        model.addAttribute("emprestimos", resultado.content)
        model.addAttribute("filtro", filtro)
        model.addAttribute("filtros", FiltroEmprestimo.entries)
        model.addAttribute("busca", busca?.trim().orEmpty())
        model.addAttribute("atrasados", service.contarAtrasados())
        model.adicionarPaginacao(resultado, "/admin/emprestimos", mapOf("situacao" to filtro.name, "busca" to busca))
        return "emprestimos"
    }

    /** Formulário. Os bens vêm de [tags] (botão "Emprestar" na edição da etiqueta) ou de [codigos] (ao conferir). */
    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @RequestMapping("/novo", method = [RequestMethod.GET, RequestMethod.POST])
    fun novo(
        @RequestParam(required = false) tags: List<String>?,
        @RequestParam(required = false) codigos: String?,
        @RequestParam(required = false) responsavel: Long?,
        @RequestParam(required = false) saida: String?,
        @RequestParam(required = false) previsao: String?,
        @RequestParam(required = false) finalidade: String?,
        model: Model
    ): String {
        val lista = if (!tags.isNullOrEmpty()) tags else separar(codigos.orEmpty())
        formulario(model, lista, responsavel, saida, previsao, finalidade, null)
        return "emprestimo-novo"
    }

    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @PostMapping
    fun emprestar(
        @RequestParam(required = false) codigos: String?,
        @RequestParam(required = false) responsavel: Long?,
        @RequestParam(required = false) saida: String?,
        @RequestParam(required = false) previsao: String?,
        @RequestParam(required = false) finalidade: String?,
        model: Model,
        redirect: RedirectAttributes
    ): String {
        val lista = separar(codigos.orEmpty())
        val erro = try {
            val e = service.emprestar(PedidoEmprestimo(lista, responsavel?.takeIf { it > 0 }, dia(previsao, "previsão de devolução"),
                dia(saida, "saída"), finalidade))
            redirect.addFlashAttribute("msg", "Empréstimo nº ${e.id} registrado. Imprima o termo para a assinatura de quem recebe.")
            return "redirect:/admin/emprestimos/${e.id}"
        } catch (e: IllegalArgumentException) {
            e.message
        } catch (e: DataIntegrityViolationException) {
            // Índice único: outra tela emprestou um destes bens no mesmo instante
            log.warn("Empréstimo recusado na gravação: um dos bens entrou em outro empréstimo no mesmo instante")
            "Um destes bens acabou de entrar em outro empréstimo. Confira os bens e tente de novo."
        }
        log.info("Empréstimo recusado: {}", erro)
        formulario(model, lista, responsavel, saida, previsao, finalidade, erro)
        return "emprestimo-novo"
    }

    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping("/{id}")
    fun detalhe(@PathVariable id: Long, model: Model): String {
        model.addAttribute("d", service.detalhar(id))
        model.addAttribute("hoje", service.hoje())
        return "emprestimo"
    }

    /** Devolução dos bens marcados ([itens]); sem nenhum marcado, recusa (o formulário vem com todos marcados). */
    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @PostMapping("/{id}/devolucao")
    fun devolver(
        @PathVariable id: Long,
        @RequestParam(required = false) itens: List<Long>?,
        @RequestParam(required = false) data: String?,
        @RequestParam(required = false) condicao: String?,
        redirect: RedirectAttributes
    ): String {
        try {
            require(!itens.isNullOrEmpty()) { "Marque os bens que voltaram" }
            val devolvidos = service.devolver(id, itens, dia(data, "devolução"), condicao)
            val aberto = service.detalhar(id).aberto
            redirect.addFlashAttribute("msg", "Devolução de ${devolvidos.size} bem(ns) registrada." +
                if (aberto) " Ainda há bens a devolver." else " Empréstimo encerrado.")
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:/admin/emprestimos/$id"
    }

    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping("/{id}/termo.pdf")
    @ResponseBody
    fun termo(@PathVariable id: Long, auth: Authentication): ResponseEntity<ByteArray> {
        val d = service.detalhar(id)
        val bytes = pdf.gerar("pdf/termo-emprestimo", mapOf("d" to d))
        log.info("{} gerou o termo do empréstimo nº {} em PDF ({} bem(ns))", auth.name, id, d.itens.size)
        return pdf.resposta(bytes, "termo-emprestimo-$id.pdf")
    }

    private fun formulario(
        model: Model, lista: List<String>, responsavel: Long?, saida: String?, previsao: String?, finalidade: String?, erro: String?
    ) {
        val limpos = lista.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val hoje = service.hoje()
        model.addAttribute("codigos", limpos.joinToString("\n"))
        model.addAttribute("quantidade", limpos.size)
        model.addAttribute("maximo", EmprestimoService.MAXIMO_BENS)
        val localizados = if (limpos.isEmpty() || limpos.size > EmprestimoService.MAXIMO_BENS) null else service.localizar(limpos)
        model.addAttribute("localizados", localizados)
        model.addAttribute("podemSair", EmprestimoService.PODEM_SAIR)
        model.addAttribute("responsavel", responsavel)
        model.addAttribute("saida", runCatching { dia(saida, "saída") }.getOrNull() ?: hoje)
        model.addAttribute("previsao", runCatching { dia(previsao, "previsão") }.getOrNull())
        model.addAttribute("finalidade", finalidade)
        model.addAttribute("hoje", hoje)
        model.addAttribute("opcoesResponsavel", cadastros.responsaveisAtivos())
        if (erro != null) model.addAttribute("erro", erro)
        if (limpos.size > EmprestimoService.MAXIMO_BENS) model.addAttribute("erro", "No máximo ${EmprestimoService.MAXIMO_BENS} bens por empréstimo (informados ${limpos.size})")
    }

    /** Um código por linha (aceita também vírgula, ponto e vírgula e tab), como na transferência. */
    private fun separar(texto: String): List<String> = texto.split(Regex("[\\r\\n,;\\t]+")).map { it.trim() }.filter { it.isNotEmpty() }

    /** Data do formulário (aaaa-mm-dd); vazia = null. */
    private fun dia(texto: String?, campo: String): LocalDate? = texto?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { LocalDate.parse(it) }.getOrElse { throw IllegalArgumentException("Data de $campo inválida") }
    }
}
