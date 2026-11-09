package org.example.util

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * Lê uma planilha enviada pelo usuário (importação de etiquetas, 1.6.0): a primeira aba de um .xlsx ou um .csv, como
 * linhas de texto. Sem biblioteca nova: o .xlsx é um zip de XMLs (o mesmo formato que o servidor gera em [Planilha]).
 *
 * Cuidados com o arquivo de fora:
 *  - XML sem DTD nem entidades externas (XXE: ler arquivos do servidor; "bilhão de risadas": travar o servidor);
 *  - zip com limite do que é descompactado ([MAXIMO_DESCOMPACTADO]) e de linhas ([maximoLinhas]);
 *  - CSV em UTF-8 (com ou sem BOM) ou no padrão do Excel em português (Windows-1252), com ";" ou ",".
 */
object LeitorPlanilha {

    const val MAXIMO_DESCOMPACTADO = 60L * 1024 * 1024

    class PlanilhaInvalidaException(mensagem: String) : IllegalArgumentException(mensagem)

    fun ler(nomeArquivo: String, conteudo: ByteArray, maximoLinhas: Int): List<List<String>> {
        val nome = nomeArquivo.lowercase()
        val linhas = when {
            nome.endsWith(".xlsx") -> xlsx(conteudo, maximoLinhas)
            nome.endsWith(".csv") || nome.endsWith(".txt") -> csv(conteudo, maximoLinhas)
            nome.endsWith(".xls") -> throw PlanilhaInvalidaException("Arquivo .xls (Excel 97-2003) não é aceito: no Excel, salve como .xlsx")
            else -> throw PlanilhaInvalidaException("Envie uma planilha .xlsx (Excel) ou .csv")
        }
        // Linhas vazias no fim (e no meio) não contam
        return linhas.map { linha -> linha.map { it.trim() } }.filter { linha -> linha.any { it.isNotEmpty() } }
    }

    // ================= XLSX =================

    private fun xlsx(conteudo: ByteArray, maximoLinhas: Int): List<List<String>> {
        val partes = mutableMapOf<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(conteudo)).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entrada ->
                    val nome = entrada.name
                    if (nome == "xl/sharedStrings.xml" || nome == "xl/workbook.xml" || nome == "xl/_rels/workbook.xml.rels" ||
                        (nome.startsWith("xl/worksheets/") && nome.endsWith(".xml"))) {
                        val bytes = lerLimitado(zip, MAXIMO_DESCOMPACTADO - total)
                        total += bytes.size
                        partes[nome] = bytes
                    }
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw PlanilhaInvalidaException("O arquivo não é uma planilha .xlsx válida")
        }
        val compartilhados = partes["xl/sharedStrings.xml"]?.let(::textosCompartilhados).orEmpty()
        val aba = primeiraAba(partes) ?: throw PlanilhaInvalidaException("A planilha .xlsx não tem nenhuma aba")
        return celulas(aba, compartilhados, maximoLinhas)
    }

    private fun lerLimitado(entrada: InputStream, restante: Long): ByteArray {
        val saida = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var lidos = 0L
        while (true) {
            val n = entrada.read(buffer)
            if (n < 0) break
            lidos += n
            if (lidos > restante) throw PlanilhaInvalidaException("A planilha é grande demais (mais de ${MAXIMO_DESCOMPACTADO / 1024 / 1024} MB descompactada)")
            saida.write(buffer, 0, n)
        }
        return saida.toByteArray()
    }

    /** A aba que aparece primeiro no Excel (workbook.xml → relação → arquivo), ou a sheet1.xml. */
    private fun primeiraAba(partes: Map<String, ByteArray>): ByteArray? {
        val idRelacao = partes["xl/workbook.xml"]?.let { xml ->
            leitor(xml).use { r ->
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT && r.localName == "sheet") {
                        return@use (0 until r.attributeCount).firstOrNull { r.getAttributeLocalName(it) == "id" }?.let { r.getAttributeValue(it) }
                    }
                }
                null
            }
        }
        val alvo = idRelacao?.let { id ->
            partes["xl/_rels/workbook.xml.rels"]?.let { xml ->
                leitor(xml).use { r ->
                    while (r.hasNext()) {
                        if (r.next() == XMLStreamConstants.START_ELEMENT && r.localName == "Relationship" && r.getAttributeValue(null, "Id") == id) {
                            return@use r.getAttributeValue(null, "Target")
                        }
                    }
                    null
                }
            }
        }?.removePrefix("/")?.let { if (it.startsWith("xl/")) it else "xl/$it" }
        return alvo?.let(partes::get) ?: partes["xl/worksheets/sheet1.xml"]
            ?: partes.entries.filter { it.key.startsWith("xl/worksheets/") }.minByOrNull { it.key }?.value
    }

    private fun textosCompartilhados(xml: ByteArray): List<String> {
        val textos = mutableListOf<String>()
        leitor(xml).use { r ->
            var atual: StringBuilder? = null
            var noTexto = false
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                        "si" -> atual = StringBuilder()
                        "t" -> noTexto = true
                        // Texto fonético (furigana) não é o texto da célula
                        "rPh" -> noTexto = false
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (noTexto) atual?.append(r.text)
                    XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                        "t" -> noTexto = false
                        "si" -> { textos += atual?.toString().orEmpty(); atual = null }
                    }
                }
            }
        }
        return textos
    }

    private fun celulas(xml: ByteArray, compartilhados: List<String>, maximoLinhas: Int): List<List<String>> {
        val linhas = mutableListOf<MutableMap<Int, String>>()
        leitor(xml).use { r ->
            var linha: MutableMap<Int, String>? = null
            var coluna = 0
            var tipo: String? = null
            var valor: StringBuilder? = null
            var proximaColuna = 0
            // Só o valor (<v>) e o texto em linha (<t>): a fórmula (<f>) não é o conteúdo da célula
            var lendoValor = false
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                        "row" -> {
                            if (linhas.size > maximoLinhas) throw PlanilhaInvalidaException("A planilha tem mais de $maximoLinhas linhas: divida em partes")
                            linha = mutableMapOf(); proximaColuna = 0
                        }
                        "c" -> {
                            coluna = r.getAttributeValue(null, "r")?.let(::indiceDaColuna) ?: proximaColuna
                            tipo = r.getAttributeValue(null, "t")
                            valor = StringBuilder()
                        }
                        "v", "t" -> lendoValor = true
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (lendoValor) valor?.append(r.text)
                    XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                        "v", "t" -> lendoValor = false
                        "c" -> {
                            val bruto = valor?.toString().orEmpty()
                            val texto = when (tipo) {
                                "s" -> bruto.trim().toIntOrNull()?.let { compartilhados.getOrNull(it) }.orEmpty()
                                "b" -> if (bruto.trim() == "1") "sim" else "não"
                                else -> numeroComoTexto(bruto)
                            }
                            linha?.put(coluna, texto)
                            proximaColuna = coluna + 1
                            valor = null
                        }
                        "row" -> linha?.let { linhas += it }
                    }
                }
            }
        }
        return linhas.map { l -> if (l.isEmpty()) emptyList() else (0..l.keys.max()).map { l[it].orEmpty() } }
    }

    /** "123.0" vira "123"; número em notação científica (o Excel estragou um EPC só de números) fica como veio. */
    private fun numeroComoTexto(bruto: String): String {
        val t = bruto.trim()
        if (Regex("^-?\\d+\\.0+$").matches(t)) return t.substringBefore('.')
        return t
    }

    /** "C12" → 2 (A = 0). */
    private fun indiceDaColuna(referencia: String): Int =
        referencia.takeWhile { it.isLetter() }.uppercase().fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1

    private fun leitor(xml: ByteArray): FechavelXml {
        val fabrica = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
        }
        return FechavelXml(fabrica.createXMLStreamReader(ByteArrayInputStream(xml), "UTF-8"))
    }

    private class FechavelXml(val r: XMLStreamReader) : XMLStreamReader by r, AutoCloseable {
        override fun close() = r.close()
    }

    // ================= CSV =================

    private fun csv(conteudo: ByteArray, maximoLinhas: Int): List<List<String>> {
        val texto = decodificar(conteudo)
        val primeira = texto.lineSequence().firstOrNull().orEmpty()
        val separador = if (primeira.count { it == ';' } >= primeira.count { it == ',' }) ';' else ','
        val linhas = mutableListOf<List<String>>()
        var campo = StringBuilder()
        var linha = mutableListOf<String>()
        var entreAspas = false
        var i = 0
        while (i < texto.length) {
            val c = texto[i]
            when {
                entreAspas && c == '"' && texto.getOrNull(i + 1) == '"' -> { campo.append('"'); i++ }
                c == '"' -> entreAspas = !entreAspas
                !entreAspas && c == separador -> { linha += campo.toString(); campo = StringBuilder() }
                !entreAspas && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && texto.getOrNull(i + 1) == '\n') i++
                    linha += campo.toString(); campo = StringBuilder()
                    linhas += linha; linha = mutableListOf()
                    if (linhas.size > maximoLinhas + 1) throw PlanilhaInvalidaException("A planilha tem mais de $maximoLinhas linhas: divida em partes")
                }
                else -> campo.append(c)
            }
            i++
        }
        if (campo.isNotEmpty() || linha.isNotEmpty()) { linha += campo.toString(); linhas += linha }
        // O CSV exportado pelo servidor põe um ' na frente do texto que começa com = + - @ (contra fórmula): tira
        return linhas.map { l -> l.map { if (it.length > 1 && it[0] == '\'' && it[1] in "=+-@") it.substring(1) else it } }
    }

    private fun decodificar(conteudo: ByteArray): String {
        if (conteudo.size >= 3 && conteudo[0] == 0xEF.toByte() && conteudo[1] == 0xBB.toByte() && conteudo[2] == 0xBF.toByte()) {
            return String(conteudo, 3, conteudo.size - 3, Charsets.UTF_8)
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(conteudo)).toString()
        } catch (e: CharacterCodingException) {
            String(conteudo, Charset.forName("windows-1252"))   // CSV salvo pelo Excel em português
        }
    }
}
