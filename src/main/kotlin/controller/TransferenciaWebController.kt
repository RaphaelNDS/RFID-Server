package org.example.controller

import org.example.service.FiltroEtiquetas
import org.example.service.PatrimonioCadastrosService
import org.example.service.PedidoTransferencia
import org.example.service.RfidService
import org.example.service.TransferenciaService
import org.slf4j.LoggerFactory
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.Clock
import java.time.LocalDate

/**
 * Transferência de bens (1.6.1): lista das guias, nova transferência e a guia para imprimir. Ver: RFID · Ver;
 * transferir muda o cadastro dos bens, então pede RFID · Editar.
 */
@Controller
@RequestMapping("/admin/transferencias")
class TransferenciaWebController(
    private val service: TransferenciaService,
    private val cadastros: PatrimonioCadastrosService,
    private val rfid: RfidService,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping
    fun listar(@RequestParam(required = false) busca: String?, @RequestParam(defaultValue = "1") pagina: Int, model: Model): String {
        val resultado = service.listar(busca, pagina - 1)
        model.addAttribute("transferencias", resultado.content)
        model.addAttribute("busca", busca?.trim().orEmpty())
        model.adicionarPaginacao(resultado, "/admin/transferencias", mapOf("busca" to busca))
        return "transferencias"
    }

    /**
     * Formulário da transferência. Os bens vêm de [tags] (ficha do bem, divergências do inventário: POST com os
     * marcados), de [codigos] (o texto já digitado, ao conferir) ou de todos os bens de um local ou responsável
     * ([deLocal]/[deResponsavel], botão "Transferir estes" na lista de etiquetas filtrada). Só monta a tela.
     */
    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @RequestMapping("/nova", method = [RequestMethod.GET, RequestMethod.POST])
    fun nova(
        @RequestParam(required = false) tags: List<String>?,
        @RequestParam(required = false) codigos: String?,
        @RequestParam(required = false) deLocal: Long?,
        @RequestParam(required = false) deResponsavel: Long?,
        @RequestParam(required = false) inventario: String?,
        @RequestParam(required = false) localDestino: Long?,
        @RequestParam(required = false) data: String?,
        @RequestParam(required = false) solicitante: String?,
        @RequestParam(required = false) motivo: String?,
        model: Model
    ): String {
        val lista = when {
            !tags.isNullOrEmpty() -> tags
            !codigos.isNullOrBlank() -> separar(codigos)
            deLocal != null || deResponsavel != null ->
                rfid.listarTodasParaAdmin(null, FiltroEtiquetas(localId = deLocal, responsavelId = deResponsavel)).map { it.tag }
            else -> emptyList()
        }
        formulario(model, lista, inventario, localDestino, data, solicitante, motivo, null)
        return "transferencia-nova"
    }

    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @PostMapping
    fun transferir(
        @RequestParam(required = false) codigos: String?,
        @RequestParam(required = false) inventario: String?,
        @RequestParam(required = false) localDestino: Long?,
        @RequestParam(required = false) data: String?,
        @RequestParam(required = false) solicitante: String?,
        @RequestParam(required = false) motivo: String?,
        model: Model,
        redirect: RedirectAttributes
    ): String {
        val lista = separar(codigos.orEmpty())
        return try {
            // 1.18.2: a web só muda o local (o responsável de destino saiu da tela)
            val local = localDestino?.takeIf { it > 0 } ?: throw IllegalArgumentException("Escolha o local de destino")
            val guia = service.transferir(PedidoTransferencia(lista, local, null, dia(data), solicitante, motivo, inventario))
            redirect.addFlashAttribute("msg", "Transferência nº ${guia.id} registrada. Imprima a guia para as assinaturas.")
            "redirect:/admin/transferencias/${guia.id}"
        } catch (e: IllegalArgumentException) {
            // Volta para o formulário com tudo o que foi digitado (o texto dos bens pode ser longo demais para a URL)
            log.info("Transferência recusada: {}", e.message)
            formulario(model, lista, inventario, localDestino, data, solicitante, motivo, e.message)
            "transferencia-nova"
        }
    }

    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping("/{id}")
    fun guia(@PathVariable id: Long, model: Model): String {
        val detalhe = service.detalhar(id)
        model.addAttribute("guia", detalhe.transferencia)
        model.addAttribute("itens", detalhe.itens)
        return "transferencia-guia"
    }

    private fun formulario(
        model: Model, lista: List<String>, inventario: String?, localDestino: Long?,
        data: String?, solicitante: String?, motivo: String?, erro: String?
    ) {
        val limpos = lista.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        model.addAttribute("codigos", limpos.joinToString("\n"))
        // Conferência: o que cada código virou (bem com origem), e os que não acharam
        val localizados = if (limpos.isEmpty() || limpos.size > TransferenciaService.MAXIMO_BENS) null else service.localizar(limpos)
        model.addAttribute("localizados", localizados)
        model.addAttribute("quantidade", limpos.size)
        model.addAttribute("maximo", TransferenciaService.MAXIMO_BENS)
        model.addAttribute("inventario", inventario?.takeIf { it.isNotBlank() })
        model.addAttribute("localDestino", localDestino)
        model.addAttribute("data", runCatching { dia(data) }.getOrNull() ?: LocalDate.now(relogio))
        model.addAttribute("hoje", LocalDate.now(relogio))
        model.addAttribute("solicitante", solicitante)
        model.addAttribute("motivo", motivo)
        model.addAttribute("opcoesLocal", cadastros.opcoesDeLocal())
        if (erro != null) model.addAttribute("erro", erro)
        if (limpos.size > TransferenciaService.MAXIMO_BENS) model.addAttribute("erro", "No máximo ${TransferenciaService.MAXIMO_BENS} bens por transferência (informados ${limpos.size})")
    }

    /** Um código por linha (aceita também vírgula, ponto e vírgula e tab). Espaço não separa: há patrimônio com espaço. */
    private fun separar(texto: String): List<String> = texto.split(Regex("[\\r\\n,;\\t]+")).map { it.trim() }.filter { it.isNotEmpty() }

    /** Data do formulário (aaaa-mm-dd); vazia = hoje. */
    private fun dia(texto: String?): LocalDate? = texto?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { LocalDate.parse(it) }.getOrElse { throw IllegalArgumentException("Data da transferência inválida") }
    }
}
