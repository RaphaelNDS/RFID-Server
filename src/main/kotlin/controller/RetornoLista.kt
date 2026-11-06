package org.example.controller

import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.web.util.UriComponentsBuilder

/**
 * Busca e página da lista de onde a pessoa veio (etiquetas cadastradas, pendentes, inventários), para Voltar,
 * Cancelar e o redirect depois de salvar devolverem à mesma busca e página, em vez da lista limpa.
 *
 * Só esses parâmetros conhecidos, já validados (busca de até 255 caracteres, página número > 1); o destino é sempre
 * um caminho fixo do sistema, nunca um endereço vindo da requisição (sem redirecionamento aberto).
 */
data class RetornoLista(val busca: String? = null, val pagina: Int? = null) {

    /** Os parâmetros para o modelo da tela (campos ocultos do formulário): vazios ficam null. */
    fun noModelo(model: org.springframework.ui.Model) {
        model.addAttribute("voltarBusca", busca)
        model.addAttribute("voltarPagina", pagina)
    }

    /** Endereço da lista com a busca e a página (os vazios ficam de fora). */
    fun link(caminho: String): String = UriComponentsBuilder.fromPath(caminho)
        .apply {
            busca?.let { queryParam("busca", it) }
            pagina?.let { queryParam("pagina", it) }
        }
        .encode().toUriString()

    /** Para "redirect:caminho": o Spring acrescenta e codifica os parâmetros. */
    fun noRedirect(redirect: RedirectAttributes) {
        busca?.let { redirect.addAttribute("busca", it) }
        pagina?.let { redirect.addAttribute("pagina", it) }
    }

    companion object {
        private const val TAMANHO_MAXIMO_BUSCA = 255

        fun de(busca: String?, pagina: String?) = RetornoLista(buscaValida(busca), paginaValida(pagina))

        fun buscaValida(busca: String?): String? =
            busca?.trim()?.takeIf { it.isNotEmpty() && it.length <= TAMANHO_MAXIMO_BUSCA }

        /** Página 1 é a padrão da lista: fica de fora. Texto que não é número também (ignorado, sem erro). */
        fun paginaValida(pagina: String?): Int? = pagina?.trim()?.toIntOrNull()?.takeIf { it > 1 }
    }
}
