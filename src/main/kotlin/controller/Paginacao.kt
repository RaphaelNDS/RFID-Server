package org.example.controller

import org.springframework.data.domain.Page
import org.springframework.ui.Model
import org.springframework.web.util.UriComponentsBuilder

/**
 * Total, página atual e links de anterior/próxima para o fragmento layout/paginacao. Os links mantêm os
 * [filtros] aplicados (os vazios ficam de fora). Na tela as páginas começam em 1; no Page, em 0.
 */
fun Model.adicionarPaginacao(resultado: Page<*>, caminho: String, filtros: Map<String, Any?> = emptyMap()) {
    fun link(numero: Int) = UriComponentsBuilder.fromPath(caminho)
        .apply {
            filtros.forEach { (nome, valor) ->
                val texto = valor?.toString()?.trim().orEmpty()
                if (texto.isNotEmpty()) queryParam(nome, texto)
            }
        }
        .queryParam("pagina", numero).encode().toUriString()

    val paginaAtual = resultado.number + 1
    addAttribute("total", resultado.totalElements)
    addAttribute("paginaAtual", paginaAtual)
    addAttribute("totalPaginas", resultado.totalPages.coerceAtLeast(1))
    addAttribute("linkAnterior", if (resultado.hasPrevious()) link(paginaAtual - 1) else null)
    addAttribute("linkProxima", if (resultado.hasNext()) link(paginaAtual + 1) else null)
}
