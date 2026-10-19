package org.example.util

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.util.zip.ZipInputStream

class GeradorPlanilhaTest {

    private val planilha = Planilha(
        nome = "Inventário: Sala/101",
        colunas = listOf("Data", "Patrimônio", "Texto", "Cadastrada", "Vazio"),
        linhas = listOf(
            listOf(LocalDateTime.of(2026, 10, 14, 13, 30, 15), "000123", "=HYPERLINK(\"x\")", true, null),
            listOf(LocalDateTime.of(2026, 1, 2, 0, 0, 0), "1E5", "a;b \"c\" <d> & e\u0001", false, null)
        )
    )

    // ================= CSV =================

    private fun csv() = GeradorPlanilha.csv(planilha)

    @Test
    fun `csv tem BOM UTF-8 separador ponto e virgula e quebra de linha do Windows`() {
        val bytes = csv()
        assertThat(bytes.take(3)).containsExactly(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        val linhas = String(bytes, Charsets.UTF_8).removePrefix("﻿").split("\r\n")
        assertThat(linhas[0]).isEqualTo("Data;Patrimônio;Texto;Cadastrada;Vazio")
        assertThat(linhas[1]).startsWith("14/10/2026 13:30:15;000123;")
        assertThat(linhas[1]).endsWith(";Sim;")
    }

    @Test
    fun `csv neutraliza formula e escapa aspas e separador`() {
        val texto = String(csv(), Charsets.UTF_8)

        // Começa com "=": ganha apóstrofo (e vai entre aspas por ter aspas dentro)
        assertThat(texto).contains("\"'=HYPERLINK(\"\"x\"\")\"")
        assertThat(texto).contains("\"a;b \"\"c\"\" <d> & e\u0001\"")
    }

    @Test
    fun `csv protege todos os inicios de formula`() {
        val p = Planilha("x", listOf("A"), listOf("=1", "+1", "-1", "@SOMA(A1)", "normal").map { listOf(it) })
        val linhas = String(GeradorPlanilha.csv(p), Charsets.UTF_8).removePrefix("﻿").split("\r\n")

        assertThat(linhas.subList(1, 6)).containsExactly("'=1", "'+1", "'-1", "'@SOMA(A1)", "normal")
    }

    // ================= XLSX =================

    private fun arquivosDoXlsx(bytes: ByteArray): Map<String, String> {
        val arquivos = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { arquivos[it.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        return arquivos
    }

    @Test
    fun `xlsx tem todas as partes que o Excel exige`() {
        val arquivos = arquivosDoXlsx(GeradorPlanilha.xlsx(planilha))

        assertThat(arquivos.keys).containsExactlyInAnyOrder(
            "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml"
        )
        // Nome da aba sem os caracteres proibidos (: e /)
        assertThat(arquivos["xl/workbook.xml"]).contains("""name="Inventário  Sala 101"""")
    }

    @Test
    fun `xlsx guarda textos como texto e datas como data do Excel`() {
        val folha = arquivosDoXlsx(GeradorPlanilha.xlsx(planilha))["xl/worksheets/sheet1.xml"]!!

        // Patrimônio com zeros à esquerda e "1E5" continuam texto (não viram número)
        assertThat(folha).contains("""<c r="B2" t="inlineStr"><is><t xml:space="preserve">000123</t></is></c>""")
        assertThat(folha).contains("""<c r="B3" t="inlineStr"><is><t xml:space="preserve">1E5</t></is></c>""")
        // Fórmula vira texto puro; caracteres especiais escapados e de controle removidos
        assertThat(folha).contains("<t xml:space=\"preserve\">=HYPERLINK(&quot;x&quot;)</t>")
        assertThat(folha).contains("<t xml:space=\"preserve\">a;b &quot;c&quot; &lt;d&gt; &amp; e</t>")
        assertThat(folha).doesNotContain("<f>")
        // 14/10/2026 13:30:15 = dia 46309 do Excel + 13:30:15 do dia
        assertThat(folha).contains("""<c r="A2" s="2"><v>46309.56267361</v></c>""")
        assertThat(folha).contains("<t xml:space=\"preserve\">Sim</t>")
        // Cabeçalho em negrito e célula nula fica vazia
        assertThat(folha).contains("""<c r="A1" t="inlineStr" s="1">""")
        assertThat(folha).doesNotContain("r=\"E2\"")
    }

    @Test
    fun `referencia de coluna segue o padrao do Excel`() {
        assertThat(GeradorPlanilha.referencia(0, 1)).isEqualTo("A1")
        assertThat(GeradorPlanilha.referencia(25, 2)).isEqualTo("Z2")
        assertThat(GeradorPlanilha.referencia(26, 3)).isEqualTo("AA3")
        assertThat(GeradorPlanilha.referencia(701, 4)).isEqualTo("ZZ4")
        assertThat(GeradorPlanilha.referencia(702, 5)).isEqualTo("AAA5")
    }

    @Test
    fun `data base do Excel`() {
        assertThat(GeradorPlanilha.serialExcel(LocalDateTime.of(1900, 1, 1, 0, 0))).isEqualTo("2.00000000")
        assertThat(GeradorPlanilha.serialExcel(LocalDateTime.of(2026, 10, 14, 12, 0))).isEqualTo("46309.50000000")
    }

    // ================= Formato e nome do arquivo =================

    @Test
    fun `formato padrao e xlsx e formato desconhecido e recusado`() {
        assertThat(FormatoPlanilha.de(null)).isEqualTo(FormatoPlanilha.XLSX)
        assertThat(FormatoPlanilha.de(" CSV ")).isEqualTo(FormatoPlanilha.CSV)
        assertThatThrownBy { FormatoPlanilha.de("pdf") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Use xlsx ou csv")
    }

    @Test
    fun `nome do arquivo sem acentos nem simbolos`() {
        assertThat(nomeArquivo("inventario-Almoxarifado Térreo/2-2026-10-14")).isEqualTo("inventario-almoxarifado-terreo-2-2026-10-14")
        assertThat(nomeArquivo("***")).isEqualTo("exportacao")
    }
}
