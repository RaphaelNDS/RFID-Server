package org.example.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.example.service.EsperadoInventarioService
import org.example.service.InventarioService
import org.example.service.ItemLido
import org.example.service.ResultadoItens
import org.example.security.nomeOperador
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.security.MessageDigest
import java.time.OffsetDateTime

/**
 * Inventário por local, usado pelo app Android. Protegido pela chave do coletor
 * (SecurityConfig.dispositivosFilterChain cobre tudo em /api/android).
 *
 * Todas as chamadas podem ser repetidas sem duplicar: o coletor reenvia o que não teve confirmação.
 */
@RestController
@RequestMapping("/api/android")
class InventarioAndroidController(
    private val service: InventarioService,
    private val esperados: EsperadoInventarioService,
    private val sessoes: org.example.service.SessaoOperadorService,
    /** O mesmo ObjectMapper das outras respostas: o JSON do esperado sai igual ao de antes do ETag. */
    private val json: ObjectMapper
) {

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    companion object {
        /** ETag forte: hash SHA-256 do corpo (os 32 primeiros dígitos hexadecimais bastam), entre aspas. */
        fun etagDe(corpo: ByteArray): String =
            "\"" + MessageDigest.getInstance("SHA-256").digest(corpo).joinToString("") { "%02x".format(it) }.take(32) + "\""

        /** If-None-Match com o [etag]: aceita lista separada por vírgula, "*" e a forma fraca (W/"..."). */
        fun etagConfere(ifNoneMatch: String?, etag: String): Boolean =
            !ifNoneMatch.isNullOrBlank() && ifNoneMatch.split(',').map { it.trim().removePrefix("W/") }.any { it == "*" || it == etag }
    }

    data class AbrirInventarioRequest(
        val id: String,                       // UUID gerado no coletor
        val local: String,
        val iniciadoEm: OffsetDateTime? = null
    )

    data class ItemRequest(val tag: String, val lidoEm: OffsetDateTime? = null)

    data class ItensRequest(val itens: List<ItemRequest> = emptyList())

    data class FinalizarRequest(val finalizadoEm: OffsetDateTime? = null)

    /** Locais já usados em inventários, para o app sugerir. */
    @PreAuthorize(LEITURA)
    @GetMapping("/locais")
    fun locais(): List<String> = service.listarLocais()

    /**
     * O que é esperado no local (o que foi encontrado no último inventário finalizado dele), para o coletor mostrar o que
     * falta. Com ETag: o coletor guarda a lista e manda If-None-Match na próxima vez; sem mudança, a resposta é 304 sem
     * corpo, e as milhares de etiquetas não passam de novo pelo Wi-Fi.
     *
     * O ETag é o hash do próprio JSON. O servidor ainda monta a lista a cada pedido, mas o hash nunca fica velho: muda
     * com qualquer mudança no cadastro ou com um inventário novo finalizado, sem precisar saber o que mudou.
     */
    @PreAuthorize(LEITURA)
    @GetMapping("/inventarios/esperado")
    fun esperado(@RequestParam local: String, auth: Authentication,
                 @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?): ResponseEntity<ByteArray> {
        val esperado = esperados.esperadoNoLocal(local, auth.name)
        val corpo = json.writeValueAsBytes(esperado)
        val etag = etagDe(corpo)
        if (etagConfere(ifNoneMatch, etag)) {
            log.info("Coletor {}: esperado do local '{}' sem mudança desde o último pedido (304, {} etiqueta(s) não reenviada(s))",
                auth.name, esperado.local, esperado.itens.size)
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build()
        }
        return ResponseEntity.ok().eTag(etag).contentType(MediaType.APPLICATION_JSON).body(corpo)
    }

    @PreAuthorize(LEITURA)
    @PostMapping("/inventarios")
    fun abrir(@RequestBody req: AbrirInventarioRequest, auth: Authentication): Map<String, String> {
        val inventario = repetindoSeReenvioSimultaneo("Abertura do inventário ${req.id}") {
            service.abrir(req.id, req.local, req.iniciadoEm, auth.name, auth.nomeOperador())
        }
        return mapOf("id" to inventario.id, "status" to inventario.status.name)
    }

    @PreAuthorize(LEITURA)
    @PostMapping("/inventarios/{id}/itens")
    fun itens(@PathVariable id: String, @RequestBody req: ItensRequest, auth: Authentication,
              request: jakarta.servlet.http.HttpServletRequest): ResultadoItens {
        // Hora de leitura fora da sessão do operador (qualquer data passada era aceita): o item continua no
        // inventário, com a hora do recebimento. Recusar só o item faria o app reenviar para sempre
        val token = request.getHeader(org.example.config.OperadorFilter.HEADER_SESSAO)?.trim().orEmpty()
        val foraDaSessao = sessoes.conferirHorasDoColetor(token, auth.name, req.itens.map { it.lidoEm })
        if (foraDaSessao.isNotEmpty()) {
            org.slf4j.LoggerFactory.getLogger(javaClass).warn(
                "Inventário {}: {} etiqueta(s) com hora de leitura fora da sessão do operador no coletor {}: gravadas com a hora do recebimento",
                id, foraDaSessao.size, auth.name)
        }
        val lidos = req.itens.mapIndexed { i, it -> ItemLido(it.tag, if (i in foraDaSessao) null else it.lidoEm) }
        return repetindoSeReenvioSimultaneo("Etiquetas do inventário $id") {
            service.registrarItens(id, lidos, auth.name, auth.nomeOperador())
        }
    }

    /**
     * Justificativas dos bens que faltaram (1.5.0): lista de {"tag","motivo","observacao"}. Motivos: EMPRESTADO,
     * MANUTENCAO, BAIXADO, NAO_ENCONTRADO, OUTRO (ou o nome: "Em manutenção"). Aceita também depois de finalizado e é
     * idempotente: o reenvio da mesma etiqueta atualiza a justificativa dela.
     */
    @PreAuthorize(LEITURA)
    @PostMapping("/inventarios/{id}/justificativas")
    fun justificativas(@PathVariable id: String, @RequestBody req: List<org.example.service.JustificativaRecebida>,
                       auth: Authentication): org.example.service.ResultadoJustificativas =
        repetindoSeReenvioSimultaneo("Justificativas do inventário $id") {
            service.registrarJustificativas(id, req, auth.name, auth.nomeOperador())
        }

    @PreAuthorize(LEITURA)
    @PostMapping("/inventarios/{id}/finalizar")
    fun finalizar(@PathVariable id: String, @RequestBody(required = false) req: FinalizarRequest?, auth: Authentication): Map<String, String> {
        val inventario = service.finalizar(id, req?.finalizadoEm, auth.name)
        return mapOf("id" to inventario.id, "status" to inventario.status.name)
    }
}

/** Inventário exige a mesma permissão da tela de Leituras. */
private const val LEITURA = "@authService.temPermissao('LEITURA','READ')"
