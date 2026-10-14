package org.example.controller

import org.example.service.FiltroLeituras
import org.example.service.LeituraAndroidConsultaService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.util.UriComponentsBuilder
import java.time.LocalDate

@Controller
@RequestMapping("/admin/leituras-android")
class LeituraAndroidAdminController(
    private val consulta: LeituraAndroidConsultaService
) {

    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @GetMapping
    fun listar(
        @RequestParam(required = false) dia: String?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) tipo: String?,
        @RequestParam(required = false) marca: String?,
        @RequestParam(required = false) modelo: String?,
        @RequestParam(required = false) numeroSerie: String?,
        @RequestParam(required = false) operador: String?,
        @RequestParam(defaultValue = "1") pagina: Int,
        model: Model
    ): String {

        // Data inválida vira erro 400 com mensagem (TratamentoErros)
        val filtro = FiltroLeituras(
            dia = dia?.takeIf { it.isNotBlank() }?.let(LocalDate::parse),
            tag = tag, tipo = tipo, marca = marca, modelo = modelo, numeroSerie = numeroSerie, operador = operador
        )

        // Na tela as páginas começam em 1; na consulta, em 0
        val resultado = consulta.buscar(filtro, pagina - 1)

        // Links das páginas mantêm os filtros aplicados
        val base = UriComponentsBuilder.fromPath("/admin/leituras-android")
        mapOf("dia" to dia, "tag" to tag, "tipo" to tipo, "marca" to marca, "modelo" to modelo,
            "numeroSerie" to numeroSerie, "operador" to operador)
            .filterValues { !it.isNullOrBlank() }
            .forEach { (nome, valor) -> base.queryParam(nome, valor!!.trim()) }

        fun linkPagina(numero: Int) = base.cloneBuilder().queryParam("pagina", numero).encode().toUriString()

        val paginaAtual = resultado.number + 1
        model.addAttribute("leituras", resultado.content)
        model.addAttribute("totalLeituras", resultado.totalElements)
        model.addAttribute("paginaAtual", paginaAtual)
        model.addAttribute("totalPaginas", resultado.totalPages.coerceAtLeast(1))
        model.addAttribute("linkAnterior", if (resultado.hasPrevious()) linkPagina(paginaAtual - 1) else null)
        model.addAttribute("linkProxima", if (resultado.hasNext()) linkPagina(paginaAtual + 1) else null)
        model.addAttribute("contadorPorDia", consulta.diasRecentes())
        model.addAttribute("diaSelecionado", dia)

        return "leituras-android"
    }
}
