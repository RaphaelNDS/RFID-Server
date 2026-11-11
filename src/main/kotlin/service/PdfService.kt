package org.example.service

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import org.jsoup.Jsoup
import org.jsoup.helper.W3CDom
import org.slf4j.LoggerFactory
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.thymeleaf.ITemplateEngine
import org.thymeleaf.context.Context
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * PDFs (1.8.0): um template Thymeleaf de templates/pdf vira HTML, o jsoup o transforma em XHTML (o openhtmltopdf só
 * aceita XML bem formado) e o openhtmltopdf desenha o PDF. As fontes são as padrão do PDF (Helvetica), que cobrem o
 * português; os templates não usam símbolos fora do Latin-1 (setas, ícones).
 *
 * Os templates de PDF não usam @{...} nem beans (@authService): são processados fora da requisição.
 */
@Service
class PdfService(
    private val templates: ITemplateEngine,
    buildProperties: org.springframework.beans.factory.ObjectProvider<org.springframework.boot.info.BuildProperties>,
    private val relogio: java.time.Clock
) {

    private val versao: String = buildProperties.ifAvailable?.version ?: "desenvolvimento"

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Gera o PDF. Além das [variaveis], o template recebe geradoEm, geradoPor (quem está logado), versao e fmt
     * ([org.example.util.FormatoBr], para valores e datas).
     */
    fun gerar(template: String, variaveis: Map<String, Any?>): ByteArray {
        val inicio = System.nanoTime()
        val comuns = mapOf(
            "geradoEm" to java.time.LocalDateTime.now(relogio),
            "geradoPor" to (org.springframework.security.core.context.SecurityContextHolder.getContext().authentication?.name ?: "sistema"),
            "versao" to versao,
            "fmt" to org.example.util.FormatoBr
        )
        val html = templates.process(template, Context(LOCALE, comuns + variaveis))
        val documento = W3CDom().fromJsoup(Jsoup.parse(html))
        val bytes = ByteArrayOutputStream().use { saida ->
            PdfRendererBuilder()
                .useFastMode()
                .withW3cDocument(documento, null)
                .toStream(saida)
                .run()
            saida.toByteArray()
        }
        log.debug("PDF {} gerado em {} ms ({} KB)", template, (System.nanoTime() - inicio) / 1_000_000, bytes.size / 1024)
        return bytes
    }

    /** Resposta para abrir no navegador (inline), com o nome do arquivo para quando a pessoa salvar. */
    fun resposta(bytes: ByteArray, nomeArquivo: String): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(nomeArquivo, Charsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(bytes)

    companion object {
        val LOCALE: Locale = Locale("pt", "BR")
    }
}
