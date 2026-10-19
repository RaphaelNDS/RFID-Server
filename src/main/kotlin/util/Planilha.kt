package org.example.util

import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Dados de uma exportação: o mesmo conteúdo vira Excel (.xlsx) ou CSV.
 * Valores aceitos nas linhas: texto, número, LocalDateTime, Boolean ("Sim"/"Não") ou null (célula vazia).
 */
class Planilha(val nome: String, val colunas: List<String>, val linhas: List<List<Any?>>)

enum class FormatoPlanilha(val extensao: String, val tipoConteudo: String) {
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("csv", "text/csv; charset=UTF-8");

    companion object {
        /** "xlsx" (padrão) ou "csv". Outro valor vira erro 400 com mensagem. */
        fun de(texto: String?): FormatoPlanilha = when (texto?.trim()?.lowercase()) {
            null, "", "xlsx", "excel" -> XLSX
            "csv" -> CSV
            else -> throw IllegalArgumentException("Formato de exportação inválido: '$texto'. Use xlsx ou csv")
        }
    }
}

/**
 * Resposta de download da planilha. [nomeBase] vira o nome do arquivo sem acentos nem símbolos
 * (ex.: "inventario-almoxarifado-2026-10-14"), com a extensão do formato.
 */
fun respostaPlanilha(planilha: Planilha, formato: FormatoPlanilha, nomeBase: String): ResponseEntity<ByteArray> {
    val arquivo = "${nomeArquivo(nomeBase)}.${formato.extensao}"
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(arquivo, Charsets.UTF_8).build().toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .contentType(MediaType.parseMediaType(formato.tipoConteudo))
        .body(GeradorPlanilha.gerar(planilha, formato))
}

internal fun nomeArquivo(texto: String): String =
    Normalizer.normalize(texto, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(80)
        .ifEmpty { "exportacao" }

/**
 * Gera a planilha sem biblioteca externa.
 *
 * - XLSX (o padrão): cada texto vai como texto (patrimônio "000123" não perde os zeros, EPC só com números
 *   não vira notação científica, e nada é interpretado como fórmula); datas vão como datas do Excel
 *   (dá para ordenar e filtrar). Cabeçalho em negrito e congelado.
 * - CSV: no padrão do Excel em português (separador ";" e UTF-8 com BOM, para os acentos). Texto que
 *   começa com = + - @ ganha um apóstrofo na frente, para o Excel não executar como fórmula
 *   (os textos vêm dos coletores: injeção de fórmula em CSV).
 */
object GeradorPlanilha {

    fun gerar(planilha: Planilha, formato: FormatoPlanilha): ByteArray = when (formato) {
        FormatoPlanilha.XLSX -> xlsx(planilha)
        FormatoPlanilha.CSV -> csv(planilha)
    }

    // ================= CSV =================

    private val FORMATO_DATA_CSV: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

    fun csv(planilha: Planilha): ByteArray {
        val texto = StringBuilder("﻿")
        (listOf(planilha.colunas) + planilha.linhas).forEach { linha ->
            texto.append(linha.joinToString(";") { campoCsv(it) }).append("\r\n")
        }
        return texto.toString().toByteArray(Charsets.UTF_8)
    }

    private fun campoCsv(valor: Any?): String {
        var texto = when (valor) {
            null -> ""
            is LocalDateTime -> FORMATO_DATA_CSV.format(valor)
            is Boolean -> if (valor) "Sim" else "Não"
            is Number -> valor.toString().replace('.', ',')
            else -> valor.toString()
        }
        if (valor is String && texto.isNotEmpty() && texto[0] in INICIO_DE_FORMULA) texto = "'$texto"

        val precisaAspas = texto.any { it == ';' || it == '"' || it == '\n' || it == '\r' }
        return if (precisaAspas) "\"" + texto.replace("\"", "\"\"") + "\"" else texto
    }

    private val INICIO_DE_FORMULA = setOf('=', '+', '-', '@', '\t', '\r')

    // ================= XLSX =================

    fun xlsx(planilha: Planilha): ByteArray {
        val saida = ByteArrayOutputStream()
        ZipOutputStream(saida).use { zip ->
            fun arquivo(caminho: String, conteudo: String) {
                zip.putNextEntry(ZipEntry(caminho))
                zip.write(conteudo.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            arquivo("[Content_Types].xml", CONTENT_TYPES)
            arquivo("_rels/.rels", RELS)
            arquivo("xl/workbook.xml", workbook(planilha.nome))
            arquivo("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            arquivo("xl/styles.xml", ESTILOS)
            arquivo("xl/worksheets/sheet1.xml", folha(planilha))
        }
        return saida.toByteArray()
    }

    private fun folha(planilha: Planilha): String {
        val xml = StringBuilder(XML_DECL)
        xml.append("""<worksheet xmlns="$NS">""")
        // Cabeçalho congelado: continua visível ao rolar
        xml.append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")

        xml.append("<cols>")
        planilha.colunas.indices.forEach { i ->
            val largura = (listOf(planilha.colunas[i]) + planilha.linhas.take(200).map { textoParaLargura(it.getOrNull(i)) })
                .maxOf { it.length }.coerceIn(8, 50) + 2
            xml.append("""<col min="${i + 1}" max="${i + 1}" width="$largura" customWidth="1"/>""")
        }
        xml.append("</cols><sheetData>")

        xml.append("""<row r="1">""")
        planilha.colunas.forEachIndexed { i, titulo -> xml.append(celulaTexto(referencia(i, 1), titulo, ESTILO_CABECALHO)) }
        xml.append("</row>")

        planilha.linhas.forEachIndexed { indiceLinha, linha ->
            val numero = indiceLinha + 2
            xml.append("""<row r="$numero">""")
            linha.forEachIndexed { i, valor ->
                val ref = referencia(i, numero)
                when (valor) {
                    null -> Unit
                    is LocalDateTime -> xml.append("""<c r="$ref" s="$ESTILO_DATA"><v>${serialExcel(valor)}</v></c>""")
                    is Boolean -> xml.append(celulaTexto(ref, if (valor) "Sim" else "Não", 0))
                    is Number -> xml.append("""<c r="$ref"><v>$valor</v></c>""")
                    else -> xml.append(celulaTexto(ref, valor.toString(), 0))
                }
            }
            xml.append("</row>")
        }

        xml.append("</sheetData></worksheet>")
        return xml.toString()
    }

    private fun textoParaLargura(valor: Any?): String = when (valor) {
        null -> ""
        is LocalDateTime -> "00/00/0000 00:00:00"
        else -> valor.toString()
    }

    // Texto "inline": nunca é interpretado como número nem como fórmula
    private fun celulaTexto(ref: String, texto: String, estilo: Int): String {
        val s = if (estilo != 0) """ s="$estilo"""" else ""
        return """<c r="$ref" t="inlineStr"$s><is><t xml:space="preserve">${escaparXml(texto)}</t></is></c>"""
    }

    /** A1, B1, ..., Z1, AA1... */
    internal fun referencia(coluna: Int, linha: Int): String {
        var n = coluna + 1
        val letras = StringBuilder()
        while (n > 0) {
            val resto = (n - 1) % 26
            letras.insert(0, 'A' + resto)
            n = (n - 1) / 26
        }
        return "$letras$linha"
    }

    /** Data do Excel: dias desde 30/12/1899, com a hora como fração do dia. */
    internal fun serialExcel(dataHora: LocalDateTime): String {
        val dias = ChronoUnit.DAYS.between(BASE_EXCEL, dataHora.toLocalDate())
        val fracao = dataHora.toLocalTime().toSecondOfDay() / 86_400.0
        return "%.8f".format(java.util.Locale.ROOT, dias + fracao)
    }

    // XML não aceita caracteres de controle (exceto tab e quebras de linha): saem, para o arquivo não corromper
    private fun escaparXml(texto: String): String = buildString {
        texto.forEach { c ->
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c < ' ' && c != '\t' && c != '\n' && c != '\r' -> Unit
                else -> append(c)
            }
        }
    }

    private fun workbook(nome: String): String {
        // Nome da aba: até 31 caracteres e sem : \ / ? * [ ]
        val aba = nome.replace(Regex("[:\\\\/?*\\[\\]]"), " ").trim().take(31).ifEmpty { "Planilha" }
        return """$XML_DECL<workbook xmlns="$NS" xmlns:r="$NS_R"><sheets><sheet name="${escaparXml(aba)}" sheetId="1" r:id="rId1"/></sheets></workbook>"""
    }

    private val BASE_EXCEL: LocalDate = LocalDate.of(1899, 12, 30)
    private const val ESTILO_CABECALHO = 1
    private const val ESTILO_DATA = 2

    private const val XML_DECL = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private const val CONTENT_TYPES = XML_DECL +
        """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
        """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
        """<Default Extension="xml" ContentType="application/xml"/>""" +
        """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
        """<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" +
        """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
        "</Types>"

    private const val RELS = XML_DECL +
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
        "</Relationships>"

    private const val WORKBOOK_RELS = XML_DECL +
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>""" +
        """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
        "</Relationships>"

    // Estilos: 0 = normal, 1 = cabeçalho em negrito, 2 = data e hora (dd/mm/aaaa hh:mm:ss)
    private const val ESTILOS = XML_DECL +
        """<styleSheet xmlns="$NS">""" +
        """<numFmts count="1"><numFmt numFmtId="164" formatCode="dd/mm/yyyy hh:mm:ss"/></numFmts>""" +
        """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>""" +
        """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
        """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
        """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
        """<cellXfs count="3">""" +
        """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
        """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
        """<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
        "</cellXfs>" +
        """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
        "</styleSheet>"
}
