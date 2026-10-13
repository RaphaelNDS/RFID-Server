package org.example.controller

import org.example.service.InventarioService
import org.example.service.ItemLido
import org.example.service.ResultadoItens
import org.example.security.nomeOperador
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
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
    private val service: InventarioService
) {

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

    @PreAuthorize(LEITURA)
    @PostMapping("/inventarios")
    fun abrir(@RequestBody req: AbrirInventarioRequest, auth: Authentication): Map<String, String> {
        val inventario = service.abrir(req.id, req.local, req.iniciadoEm, auth.name, auth.nomeOperador())
        return mapOf("id" to inventario.id, "status" to inventario.status.name)
    }

    @PreAuthorize(LEITURA)
    @PostMapping("/inventarios/{id}/itens")
    fun itens(@PathVariable id: String, @RequestBody req: ItensRequest, auth: Authentication): ResultadoItens =
        service.registrarItens(id, req.itens.map { ItemLido(it.tag, it.lidoEm) }, auth.name, auth.nomeOperador())

    @PreAuthorize(LEITURA)
    @PostMapping("/inventarios/{id}/finalizar")
    fun finalizar(@PathVariable id: String, @RequestBody(required = false) req: FinalizarRequest?, auth: Authentication): Map<String, String> {
        val inventario = service.finalizar(id, req?.finalizadoEm, auth.name)
        return mapOf("id" to inventario.id, "status" to inventario.status.name)
    }
}

/** Inventário exige a mesma permissão da tela de Leituras. */
private const val LEITURA = "@authService.temPermissao('LEITURA','READ')"
