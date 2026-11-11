package org.example.controller

import org.example.service.AnexoBemService
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.util.concurrent.TimeUnit

/**
 * Fotos e documentos do bem (1.9.0), na tela de edição da etiqueta. RFID · Editar envia e exclui; RFID · Ver abre.
 * O envio de arquivo para [CAMINHO_ENVIO] é liberado no EnvioArquivoFilter só para quem pode editar.
 */
@Controller
@RequestMapping("/admin/etiquetas/{id}/anexos")
class AnexoBemWebController(private val anexos: AnexoBemService) {

    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @PostMapping
    fun enviar(
        @PathVariable id: Long,
        @RequestParam("arquivo", required = false) arquivo: MultipartFile?,
        @RequestParam(required = false) descricao: String?,
        redirect: RedirectAttributes
    ): String {
        try {
            require(arquivo != null && !arquivo.isEmpty) { "Escolha o arquivo (foto ou PDF)" }
            // Confere o tamanho antes de ler os bytes para a memória
            require(arquivo.size <= anexos.maximoBytes) { "O arquivo tem ${arquivo.size / (1024 * 1024)} MB: o máximo é ${anexos.maximoRotulo}" }
            val anexo = anexos.adicionar(id, arquivo.originalFilename, arquivo.bytes, descricao)
            redirect.addFlashAttribute("msg", "Anexo ${anexo.nome} enviado (${anexo.tamanhoRotulo}). Ficou no histórico da etiqueta.")
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:/admin/etiquetas/$id/editar#anexos"
    }

    /** Abre no navegador (foto ou PDF); com baixar=true, baixa o arquivo. */
    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping("/{anexoId}")
    fun abrir(@PathVariable id: Long, @PathVariable anexoId: Long, @RequestParam(defaultValue = "false") baixar: Boolean): ResponseEntity<ByteArray> {
        val arquivo = anexos.arquivo(id, anexoId)
        val disposicao = (if (baixar) ContentDisposition.attachment() else ContentDisposition.inline())
            .filename(arquivo.anexo.nome, Charsets.UTF_8).build()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(arquivo.anexo.tipo.contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, disposicao.toString())
            // O tipo vem dos bytes conferidos no envio: o navegador não pode adivinhar outro
            .header("X-Content-Type-Options", "nosniff")
            // O anexo não muda (excluir e enviar de novo gera outro id): o navegador pode guardar, só para esta pessoa
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
            .body(arquivo.conteudo)
    }

    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @PostMapping("/{anexoId}/excluir")
    fun excluir(@PathVariable id: Long, @PathVariable anexoId: Long, redirect: RedirectAttributes): String {
        val anexo = anexos.excluir(id, anexoId)
        redirect.addFlashAttribute("msg", "Anexo ${anexo.nome} excluído. O nome dele continua no histórico da etiqueta.")
        return "redirect:/admin/etiquetas/$id/editar#anexos"
    }

    companion object {
        /** Onde o EnvioArquivoFilter aceita multipart de quem tem RFID_UPDATE. */
        val CAMINHO_ENVIO = Regex("^/admin/etiquetas/\\d{1,18}/anexos$")
    }
}
