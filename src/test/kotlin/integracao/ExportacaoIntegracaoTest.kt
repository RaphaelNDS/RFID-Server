package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.LeituraAndroidEntity
import org.example.repository.LeituraAndroidRepository
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.example.suporte.comoOperador
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.model
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipInputStream

/** Exportação para Excel/CSV das telas de Leituras e de Inventário. */
class ExportacaoIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var leituras: LeituraAndroidRepository
    @Autowired lateinit var operadores: OperadorTeste

    private val hoje = LocalDate.now()

    private fun popularLeituras() {
        leituras.saveAll(
            listOf(
                LeituraAndroidEntity(tag = "E1", patrimonio = "000123", tipo = "Notebook", operador = "Olga", dataHora = hoje.atTime(9, 0)),
                LeituraAndroidEntity(tag = "E2", patrimonio = "=SOMA(1)", tipo = "Monitor", operador = "Bruno", dataHora = hoje.atTime(10, 0))
            )
        )
    }

    private fun csvDe(bytes: ByteArray) = String(bytes, Charsets.UTF_8).removePrefix("﻿").trimEnd().split("\r\n")

    // ---------- Leituras ----------

    @Test
    fun `leituras em CSV respeitam o filtro da tela`() {
        popularLeituras()

        val resposta = mvc.perform(get("/admin/leituras-android/exportar").param("formato", "csv").param("operador", "olga").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("leituras-$hoje.csv")))
            .andReturn().response

        val linhas = csvDe(resposta.contentAsByteArray)
        assertThat(linhas).hasSize(2)   // cabeçalho + a leitura da Olga
        assertThat(linhas[0]).startsWith("Data/hora;Etiqueta (EPC);")
        assertThat(linhas[1]).contains(";E1;").contains(";000123;").endsWith(";Olga")
    }

    @Test
    fun `leituras em Excel por padrao com todas as linhas e formula neutralizada`() {
        popularLeituras()

        val bytes = mvc.perform(get("/admin/leituras-android/exportar").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(".xlsx")))
            .andReturn().response.contentAsByteArray

        val folha = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "xl/worksheets/sheet1.xml" }
            zip.readBytes().toString(Charsets.UTF_8)
        }
        assertThat(folha).contains(">000123<").contains(">=SOMA(1)<").doesNotContain("<f>")
        assertThat(Regex("<row ").findAll(folha).count()).isEqualTo(3)
    }

    @Test
    fun `tela de leituras mostra os links de exportacao com os filtros`() {
        popularLeituras()

        mvc.perform(get("/admin/leituras-android").param("tipo", "Notebook").with(Perfis.admin()))
            .andExpect(model().attribute("linkExcel", "/admin/leituras-android/exportar?tipo=Notebook&formato=xlsx"))
            .andExpect(model().attribute("linkCsv", "/admin/leituras-android/exportar?tipo=Notebook&formato=csv"))
            .andExpect(content().string(containsString("Excel")))
    }

    @Test
    fun `formato invalido e recusado`() {
        mvc.perform(get("/admin/leituras-android/exportar").param("formato", "pdf").with(Perfis.admin()))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `exportar leituras exige a permissao de Leituras`() {
        mvc.perform(get("/admin/leituras-android/exportar").with(Perfis.somenteCatalogo()))
            .andExpect(status().isForbidden)
    }

    // ---------- Inventário ----------

    private fun inventarioComItens(): String {
        val token = operadores.sessaoPadrao(mvc)
        val id = UUID.randomUUID().toString()
        fun coletor(url: String, json: String) =
            mvc.perform(post(url).comoOperador(token).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk)

        coletor("/api/android/inventarios", """{"id":"$id","local":"Almoxarifado Térreo","iniciadoEm":"2026-10-13T10:00:00-03:00"}""")
        coletor("/api/android/inventarios/$id/itens", """{"itens":[{"tag":"E-INV-1"},{"tag":"E-INV-2"}]}""")
        return id
    }

    @Test
    fun `inventario em CSV com uma linha por etiqueta e nome de arquivo com o local`() {
        val id = inventarioComItens()

        val resposta = mvc.perform(get("/admin/inventarios/$id/exportar").param("formato", "csv").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("inventario-almoxarifado-terreo-2026-10-13.csv")))
            .andReturn().response

        val linhas = csvDe(resposta.contentAsByteArray)
        assertThat(linhas).hasSize(3)
        assertThat(linhas[0]).isEqualTo("Lida em;Etiqueta (EPC);Situação;Patrimônio;Código;Tipo;Marca;Modelo;Operador")
        assertThat(linhas.drop(1)).allMatch { it.contains(";Não cadastrada;") && it.endsWith(";Olga Operadora") }
    }

    @Test
    fun `inventario em Excel e detalhe com os botoes`() {
        val id = inventarioComItens()

        mvc.perform(get("/admin/inventarios/$id/exportar").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(".xlsx")))

        mvc.perform(get("/admin/inventarios/$id").with(Perfis.admin()))
            .andExpect(content().string(containsString("/admin/inventarios/$id/exportar?formato=csv")))
    }

    @Test
    fun `inventario inexistente responde 404`() {
        mvc.perform(get("/admin/inventarios/${UUID.randomUUID()}/exportar").with(Perfis.admin()))
            .andExpect(status().isNotFound)
    }
}
