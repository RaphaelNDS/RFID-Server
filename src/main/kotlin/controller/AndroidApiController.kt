package org.example.controller

import org.example.repository.TagRepository
import org.example.response.TagResponse
import org.example.service.RfidService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

/**
 * API usada pelo app Android (coletor RFID).
 * Protegida pela chave do dispositivo e pelo operador logado no coletor (OperadorFilter); ping não exige operador.
 */
@RestController
@RequestMapping("/api/android")
class AndroidApiController(
    private val service: RfidService,
    private val tagRepository: TagRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class TagRequest(val tag: String)

    // O app usa para saber se o servidor está no ar E se a chave dele é aceita
    @GetMapping("/ping")
    fun ping(auth: Authentication): Map<String, String> {
        log.info("Ping recebido do dispositivo {}", auth.name)
        return mapOf("status" to "ok", "dispositivo" to auth.name)
    }

    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @GetMapping("/tags/{epc}")
    fun buscarTag(@PathVariable epc: String, auth: Authentication): ResponseEntity<TagResponse> {
        val tag = service.buscarPorTag(epc)

        if (tag == null) {
            log.debug("Consulta da tag {} pelo dispositivo {}: não cadastrada", epc, auth.name)
            return ResponseEntity.notFound().build()
        }

        log.debug("Consulta da tag {} pelo dispositivo {}: patrimônio {}", epc, auth.name, tag.patrimonio)
        return ResponseEntity.ok(tag)
    }

    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @GetMapping("/tags/patrimonio/{patrimonio}")
    fun buscarPorPatrimonio(@PathVariable patrimonio: String, auth: Authentication): ResponseEntity<Map<String, String>> {
        val encontradas = tagRepository.findAllByPatrimonio(patrimonio.trim())

        // Etiquetas antigas com o mesmo patrimônio: o operador não saberia qual procurar
        if (encontradas.size > 1) {
            log.warn("Localização: patrimônio {} está em {} etiquetas (dispositivo {}): {}", patrimonio, encontradas.size, auth.name, encontradas.joinToString { it.tagReal })
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                mapOf("mensagem" to "O patrimônio $patrimonio está em ${encontradas.size} etiquetas. Corrija o cadastro na web para localizar.")
            )
        }

        val tag = encontradas.firstOrNull()

        if (tag == null) {
            log.info("Localização: patrimônio {} não encontrado (dispositivo {})", patrimonio, auth.name)
            return ResponseEntity.notFound().build()
        }

        log.info("Localização: patrimônio {} → tag {} (dispositivo {})", patrimonio, tag.tagReal, auth.name)
        return ResponseEntity.ok(mapOf("epc" to tag.tagReal))
    }

    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @PostMapping("/naocadastrada")
    fun salvarNaoCadastrada(@RequestBody req: TagRequest) {
        service.salvarNaoCadastrada(req.tag)
    }
}
