package org.example.controller

import jakarta.servlet.http.HttpServletRequest
import org.example.config.ColetorContatoInterceptor
import org.example.service.AtualizacaoAppService
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneId

/**
 * Atualização do app pelo servidor, do lado do coletor. Exige só a chave do coletor (sem operador logado:
 * a consulta acontece na tela de login). O APK foi conferido na publicação (AtualizacaoAppService).
 */
@RestController
@RequestMapping("/api/android/atualizacao")
class AtualizacaoAndroidController(
    private val atualizacao: AtualizacaoAppService
) {

    /** Versão publicada (o app compara com a instalada), ou 204 se nenhuma foi publicada. */
    @GetMapping
    fun consultar(auth: Authentication, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val instalado = ColetorContatoInterceptor.codigoVersao(request.getHeader(ColetorContatoInterceptor.HEADER_VERSAO_CODIGO))
        val publicada = atualizacao.consultar(auth.name, instalado) ?: return ResponseEntity.noContent().build()

        return ResponseEntity.ok(
            mapOf(
                "versaoNome" to publicada.versaoNome,
                "codigo" to publicada.codigo,
                "tamanho" to publicada.tamanho,
                "sha256" to publicada.sha256,
                "notas" to publicada.notas,
                "publicadaEm" to publicada.publicadaEm.atZone(ZoneId.systemDefault()).toOffsetDateTime()
            )
        )
    }

    /** O APK da versão publicada. O app confere o SHA-256 (também no header X-Sha256) antes de instalar. */
    @GetMapping("/apk")
    fun baixar(auth: Authentication): ResponseEntity<Resource> {
        val (versao, arquivo) = atualizacao.abrirParaDownload(auth.name) ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(AtualizacaoAppService.TIPO_APK))
            .contentLength(versao.tamanho)
            .header(HEADER_SHA256, versao.sha256)
            .header(ColetorContatoInterceptor.HEADER_VERSAO_CODIGO, versao.codigo.toString())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(versao.arquivo).build().toString())
            .body(FileSystemResource(arquivo))
    }

    companion object {
        const val HEADER_SHA256 = "X-Sha256"
    }
}
