package org.example.controller

import org.example.service.FiltroLeituras
import org.example.service.LeituraAndroidConsultaService
import org.example.util.FormatoPlanilha
import org.example.util.Planilha
import org.example.util.respostaPlanilha
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
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

    private val log = LoggerFactory.getLogger(javaClass)

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

        // Exportar leva os mesmos filtros da tela (todas as páginas)
        fun linkExportar(formato: String) = base.cloneBuilder().replacePath("/admin/leituras-android/exportar")
            .queryParam("formato", formato).encode().toUriString()
        model.addAttribute("linkExcel", linkExportar("xlsx"))
        model.addAttribute("linkCsv", linkExportar("csv"))

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

    /** Todas as leituras do filtro (não só a página da tela) em Excel (padrão) ou CSV. */
    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @GetMapping("/exportar")
    fun exportar(
        @RequestParam(required = false) dia: String?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) tipo: String?,
        @RequestParam(required = false) marca: String?,
        @RequestParam(required = false) modelo: String?,
        @RequestParam(required = false) numeroSerie: String?,
        @RequestParam(required = false) operador: String?,
        @RequestParam(required = false) formato: String?,
        auth: Authentication
    ): ResponseEntity<ByteArray> {
        val tipoArquivo = FormatoPlanilha.de(formato)
        val diaFiltro = dia?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
        val filtro = FiltroLeituras(
            dia = diaFiltro,
            tag = tag, tipo = tipo, marca = marca, modelo = modelo, numeroSerie = numeroSerie, operador = operador
        )

        val leituras = consulta.buscarParaExportar(filtro)
        val planilha = Planilha(
            nome = "Leituras",
            colunas = listOf("Data/hora", "Etiqueta (EPC)", "Código", "Patrimônio", "Tipo", "Marca", "Modelo", "Nº série", "Operador"),
            linhas = leituras.map { listOf(it.dataHora, it.tag, it.codigoInterno, it.patrimonio, it.tipo, it.marca, it.modelo, it.numeroSerie, it.operador) }
        )

        log.info("{} exportou {} leitura(s) em {} (filtros: {})",
            auth.name, leituras.size, tipoArquivo.extensao.uppercase(), consulta.descreverFiltro(filtro))

        // Ex.: leituras-2026-10-14 (dia filtrado ou o dia da exportação)
        return respostaPlanilha(planilha, tipoArquivo, "leituras-${diaFiltro ?: LocalDate.now()}")
    }
}
