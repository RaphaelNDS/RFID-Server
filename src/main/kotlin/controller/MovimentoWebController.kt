package org.example.controller

import org.example.model.OrigemMovimento
import org.example.model.SituacaoBem
import org.example.model.TipoMovimento
import org.example.service.FiltroEtiquetas
import org.example.service.FiltroMovimentos
import org.example.service.MovimentoService
import org.example.service.NovoMovimento
import org.example.service.RfidService
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
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
 * Movimentações (2.0.0): a lista de saídas e entradas, "Nova saída", "Nova entrada" e o detalhe de cada uma (o
 * comprovante em PDF fica no PdfWebController). Permissões do módulo Movimentações: Ver para a lista, o detalhe e o
 * comprovante; Criar para registrar.
 */
@Controller
@RequestMapping("/admin/movimentos")
class MovimentoWebController(
    private val service: MovimentoService,
    private val rfid: RfidService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PreAuthorize("@authService.temPermissao('MOVIMENTO','READ')")
    @GetMapping
    fun listar(
        @RequestParam(required = false) tipo: String?,
        @RequestParam(required = false) de: String?,
        @RequestParam(required = false) ate: String?,
        @RequestParam(required = false) busca: String?,
        @RequestParam(defaultValue = "1") pagina: Int,
        model: Model
    ): String {
        // Valor inválido no filtro é ignorado, nunca erro (link antigo, data digitada pela metade)
        val filtro = FiltroMovimentos(TipoMovimento.de(tipo), data(de), data(ate))
        val resultado = service.listar(filtro, busca, pagina - 1)
        val termo = busca?.trim().orEmpty()
        model.addAttribute("movimentos", resultado.content)
        model.addAttribute("filtro", filtro)
        model.addAttribute("busca", termo)
        model.addAttribute("tipos", TipoMovimento.entries)
        model.adicionarPaginacao(resultado, "/admin/movimentos",
            mapOf("tipo" to filtro.tipo?.name, "de" to filtro.de, "ate" to filtro.ate, "busca" to termo))
        return "movimentos"
    }

    @PreAuthorize("@authService.temPermissao('MOVIMENTO','READ')")
    @GetMapping("/{id}")
    fun detalhe(@PathVariable id: Long, model: Model): String {
        val detalhe = service.detalhar(id)
        model.addAttribute("movimento", detalhe.movimento)
        model.addAttribute("itens", detalhe.itens)
        return "movimento"
    }

    @PreAuthorize("@authService.temPermissao('MOVIMENTO','CREATE')")
    @GetMapping("/saida")
    fun novaSaida(@RequestParam(required = false) tags: List<String>?, model: Model): String =
        formulario(TipoMovimento.SAIDA, model, lista = tags.orEmpty().joinToString("\n"))

    @PreAuthorize("@authService.temPermissao('MOVIMENTO','CREATE')")
    @GetMapping("/entrada")
    fun novaEntrada(@RequestParam(required = false) tags: List<String>?, model: Model): String =
        formulario(TipoMovimento.ENTRADA, model, lista = tags.orEmpty().joinToString("\n"))

    @PreAuthorize("@authService.temPermissao('MOVIMENTO','CREATE')")
    @PostMapping("/saida")
    fun registrarSaida(
        @RequestParam(required = false) lista: String?,
        @RequestParam(required = false) destino: String?,
        @RequestParam(required = false) observacao: String?,
        auth: Authentication, model: Model, redirect: RedirectAttributes
    ): String = registrar(TipoMovimento.SAIDA, lista, destino, observacao, auth, model, redirect)

    @PreAuthorize("@authService.temPermissao('MOVIMENTO','CREATE')")
    @PostMapping("/entrada")
    fun registrarEntrada(
        @RequestParam(required = false) lista: String?,
        @RequestParam(required = false) observacao: String?,
        auth: Authentication, model: Model, redirect: RedirectAttributes
    ): String = registrar(TipoMovimento.ENTRADA, lista, null, observacao, auth, model, redirect)

    /**
     * Busca da tela de saída e entrada: os equipamentos que podem ir (no estoque, na saída; fora do estoque, na
     * entrada) pela busca da tela de Etiquetas. No máximo uma página.
     */
    @PreAuthorize("@authService.temPermissao('MOVIMENTO','CREATE')")
    @GetMapping("/candidatos")
    @ResponseBody
    fun candidatos(@RequestParam(required = false) tipo: String?, @RequestParam(required = false) busca: String?): List<Map<String, String?>> {
        val t = TipoMovimento.de(tipo) ?: TipoMovimento.SAIDA
        val situacao = if (t == TipoMovimento.SAIDA) SituacaoBem.DISPONIVEL else SituacaoBem.FORA
        return rfid.listarParaAdmin(busca, 0, FiltroEtiquetas(situacao = situacao)).content.map {
            mapOf("epc" to it.tag, "patrimonio" to it.patrimonio.ifBlank { null }, "descricao" to it.descricao, "local" to it.localDescricao)
        }
    }

    private fun registrar(
        tipo: TipoMovimento, lista: String?, destino: String?, observacao: String?,
        auth: Authentication, model: Model, redirect: RedirectAttributes
    ): String {
        val identificadores = lista.orEmpty().split(Regex("[\\r\\n;,\\t]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val novo = NovoMovimento(tipo, identificadores, destino, observacao, OrigemMovimento.WEB, registradoPor = auth.name)
        val resultado = try {
            try {
                service.registrar(novo, tudoOuNada = true)
            } catch (e: OptimisticLockingFailureException) {
                // Outra movimentação mexeu num destes equipamentos no mesmo instante: de novo, ele já aparece movimentado
                log.info("{} de {} por {}: equipamento movimentado por outra pessoa no mesmo instante; conferindo de novo",
                    tipo.rotulo, identificadores.size, auth.name)
                service.registrar(novo, tudoOuNada = true)
            }
        } catch (e: IllegalArgumentException) {
            model.addAttribute("erro", e.message)
            return formulario(tipo, model, lista.orEmpty(), destino, observacao)
        }
        val movimento = resultado.movimento
        if (movimento == null) {
            model.addAttribute("erro", "Nada foi registrado: ${resultado.recusados.size} equipamento(s) não pode(m) ter esta " +
                "${tipo.rotulo.lowercase()}. Corrija a lista e envie de novo.")
            model.addAttribute("recusados", resultado.recusados)
            return formulario(tipo, model, lista.orEmpty(), destino, observacao)
        }
        redirect.addFlashAttribute("msg", "${tipo.rotulo} nº ${movimento.id} registrada: ${resultado.aceitos.size} equipamento(s) " +
            if (tipo == TipoMovimento.SAIDA) "fora do estoque." else "de volta ao estoque.")
        return "redirect:/admin/movimentos/${movimento.id}"
    }

    private fun formulario(tipo: TipoMovimento, model: Model, lista: String = "", destino: String? = null, observacao: String? = null): String {
        model.addAttribute("tipo", tipo)
        model.addAttribute("lista", lista)
        model.addAttribute("destino", destino.orEmpty())
        model.addAttribute("observacao", observacao.orEmpty())
        model.addAttribute("maximo", MovimentoService.MAXIMO_ITENS)
        return "movimento-novo"
    }

    private fun data(texto: String?): LocalDate? = texto?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
