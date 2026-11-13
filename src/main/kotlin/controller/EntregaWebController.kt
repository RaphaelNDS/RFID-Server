package org.example.controller

import org.example.model.TipoAnexo
import org.example.service.EntregaService
import org.example.service.FotoEnviada
import org.example.service.LocacaoService
import org.example.service.PdfService
import org.example.util.FormatoBr
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
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
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Comprovante de entrega do pedido (1.23.0): assinatura na tela e fotos no local. Registrar: Locação · Editar; ver,
 * abrir as imagens e o PDF: Locação · Ver. O envio das fotos (multipart) para [CAMINHO_ENVIO] é liberado no
 * EnvioArquivoFilter só para quem pode editar.
 */
@Controller
@RequestMapping("/admin/pedidos/{pedidoId}/entregas")
class EntregaWebController(
    private val service: EntregaService,
    private val locacao: LocacaoService,
    private val pdf: PdfService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @GetMapping("/nova")
    fun nova(@PathVariable pedidoId: Long, model: Model): String = formulario(pedidoId, model, null, null, null, null)

    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @PostMapping
    fun registrar(
        @PathVariable pedidoId: Long,
        @RequestParam(required = false) recebidoPor: String?, @RequestParam(required = false) documento: String?,
        @RequestParam(required = false) observacao: String?, @RequestParam(required = false) assinatura: String?,
        @RequestParam(required = false) fotos: List<MultipartFile>?,
        model: Model, redirect: RedirectAttributes
    ): String = try {
        val e = service.registrar(pedidoId, recebidoPor, documento, observacao, assinatura,
            fotos.orEmpty().filter { !it.isEmpty }.map { FotoEnviada(it.originalFilename, it.bytes) })
        redirect.addFlashAttribute("msg", "Comprovante de entrega nº ${e.id} registrado (recebido por ${e.recebidoPor}).")
        "redirect:/admin/pedidos/$pedidoId"
    } catch (e: IllegalArgumentException) {
        log.info("Comprovante de entrega do pedido nº {} recusado: {}", pedidoId, e.message)
        formulario(pedidoId, model, recebidoPor, documento, observacao, e.message)
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','READ')")
    @GetMapping("/{entregaId}/assinatura.png")
    @ResponseBody
    fun assinatura(@PathVariable pedidoId: Long, @PathVariable entregaId: Long): ResponseEntity<ByteArray> =
        imagem(TipoAnexo.PNG, service.entrega(pedidoId, entregaId).assinatura)

    @PreAuthorize("@authService.temPermissao('LOCACAO','READ')")
    @GetMapping("/{entregaId}/fotos/{fotoId}")
    @ResponseBody
    fun foto(@PathVariable pedidoId: Long, @PathVariable entregaId: Long, @PathVariable fotoId: Long): ResponseEntity<ByteArray> {
        val f = service.foto(pedidoId, entregaId, fotoId)
        return imagem(f.tipo, f.conteudo)
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','READ')")
    @GetMapping("/{entregaId}/comprovante.pdf")
    @ResponseBody
    fun comprovante(@PathVariable pedidoId: Long, @PathVariable entregaId: Long, auth: Authentication): ResponseEntity<ByteArray> {
        val e = service.entrega(pedidoId, entregaId)
        val p = locacao.pedido(pedidoId)
        val bytes = pdf.gerar("pdf/comprovante-entrega", mapOf(
            "e" to e, "p" to p, "fmt" to FormatoBr,
            "assinatura" to EntregaService.dataUri(TipoAnexo.PNG, e.assinatura),
            "fotos" to service.fotosDa(entregaId).map { EntregaService.dataUri(it.tipo, it.conteudo) }
        ))
        log.info("{} gerou o comprovante de entrega nº {} do pedido nº {} em PDF", auth.name, entregaId, pedidoId)
        return pdf.resposta(bytes, "comprovante-entrega-$pedidoId-$entregaId.pdf")
    }

    private fun imagem(tipo: TipoAnexo, bytes: ByteArray): ResponseEntity<ByteArray> = ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(tipo.contentType))
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .body(bytes)

    private fun formulario(pedidoId: Long, model: Model, recebidoPor: String?, documento: String?, observacao: String?, erro: String?): String {
        val p = locacao.pedido(pedidoId)
        model.addAttribute("p", p)
        model.addAttribute("equipamentos", service.naRua(pedidoId))
        model.addAttribute("recebidoPor", recebidoPor ?: locacao.cliente(p.clienteId).contato)
        model.addAttribute("documento", documento)
        model.addAttribute("observacao", observacao)
        model.addAttribute("maximoFotos", service.maximoFotos)
        model.addAttribute("maximoFoto", service.maximoFotoRotulo)
        if (erro != null) model.addAttribute("erro", erro)
        return "entrega-nova"
    }

    companion object {
        /** POST do comprovante (com as fotos): liberado no EnvioArquivoFilter para Locação · Editar. */
        val CAMINHO_ENVIO = Regex("^/admin/pedidos/\\d+/entregas$")
    }
}
