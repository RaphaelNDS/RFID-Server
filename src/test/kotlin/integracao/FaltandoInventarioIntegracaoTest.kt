package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.request.CadastroRequest
import org.example.service.FaltandoInventario
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.example.suporte.comoOperador
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** Tela de detalhe do inventário e exportação: o que faltou em relação ao inventário anterior do mesmo local. */
class FaltandoInventarioIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfidService: RfidService
    @Autowired lateinit var operadores: OperadorTeste

    private val token by lazy { operadores.sessaoPadrao(mvc) }
    private val local = "Sala ${UUID.randomUUID().toString().take(8)}"

    @BeforeEach
    fun cadastrar() {
        rfidService.cadastrarTag(CadastroRequest(tag = "E-F1", codigoInterno = "TI-1", patrimonio = "PAT-1"))
        rfidService.cadastrarTag(CadastroRequest(tag = "E-F2", codigoInterno = "TI-2", patrimonio = "PAT-2"))
        rfidService.cadastrarTag(CadastroRequest(tag = "E-F3", codigoInterno = "TI-3", patrimonio = "PAT-3"))
    }

    private fun coletor(url: String, json: String) =
        mvc.perform(post(url).comoOperador(token).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk)

    /** Inventário no [local], do dia [inicio] (ISO) e, se [fim] for informado, finalizado nessa hora. */
    private fun inventario(inicio: String, fim: String?, vararg tags: String): String {
        val id = UUID.randomUUID().toString()
        coletor("/api/android/inventarios", """{"id":"$id","local":"$local","iniciadoEm":"$inicio"}""")
        if (tags.isNotEmpty()) coletor("/api/android/inventarios/$id/itens", """{"itens":[${tags.joinToString { """{"tag":"$it"}""" }}]}""")
        fim?.let { coletor("/api/android/inventarios/$id/finalizar", """{"finalizadoEm":"$it"}""") }
        return id
    }

    private fun comparacao(id: String): FaltandoInventario =
        mvc.perform(get("/admin/inventarios/$id").with(Perfis.admin())).andExpect(status().isOk)
            .andReturn().modelAndView!!.model["comparacao"] as FaltandoInventario

    @Test
    fun `primeiro inventario do local nao tem comparacao e diz isso`() {
        val id = inventario("2026-09-01T09:00:00-03:00", "2026-09-01T10:00:00-03:00", "E-F1")

        mvc.perform(get("/admin/inventarios/$id").with(Perfis.admin()))
            .andExpect(content().string(containsString("Primeiro inventário deste local, sem comparação")))

        assertThat(comparacao(id).referencia.temReferencia).isFalse()
    }

    @Test
    fun `faltando compara com o inventario finalizado antes do inicio deste`() {
        val a = inventario("2026-09-01T09:00:00-03:00", "2026-09-01T10:00:00-03:00", "E-F1", "E-F2", "E-F3", "E-DESCONHECIDA")
        val b = inventario("2026-09-05T09:00:00-03:00", "2026-09-05T10:00:00-03:00", "E-F1")
        val c = inventario("2026-09-10T09:00:00-03:00", "2026-09-10T10:00:00-03:00", "E-F1")

        // B compara com A (o anterior a ele), não com C (o mais recente)
        val deB = comparacao(b)
        assertThat(deB.referencia.inventarioReferencia).isEqualTo(a)
        assertThat(deB.esperadas).isEqualTo(3)   // a não cadastrada de A não conta
        assertThat(deB.encontradas).isEqualTo(1)
        assertThat(deB.faltando.map { it.patrimonio }).containsExactly("PAT-2", "PAT-3")

        // C compara com B, em que só E-F1 foi encontrada: nada faltou
        val deC = comparacao(c)
        assertThat(deC.referencia.inventarioReferencia).isEqualTo(b)
        assertThat(deC.faltando).isEmpty()
        mvc.perform(get("/admin/inventarios/$c").with(Perfis.admin()))
            .andExpect(content().string(containsString("Nada faltou")))
    }

    @Test
    fun `inventario em andamento mostra a lista como provisoria`() {
        inventario("2026-09-01T09:00:00-03:00", "2026-09-01T10:00:00-03:00", "E-F1", "E-F2")
        val aberto = inventario("2026-09-05T09:00:00-03:00", null, "E-F1")

        mvc.perform(get("/admin/inventarios/$aberto").with(Perfis.admin()))
            .andExpect(content().string(containsString("Faltando até agora")))
            .andExpect(content().string(containsString("a lista é provisória")))
            .andExpect(content().string(containsString("PAT-2")))
            .andExpect(content().string(not(containsString("Nada faltou"))))
    }

    @Test
    fun `exportacao traz as lidas e depois as que faltaram com a situacao Faltando`() {
        inventario("2026-09-01T09:00:00-03:00", "2026-09-01T10:00:00-03:00", "E-F1", "E-F2")
        val b = inventario("2026-09-05T09:00:00-03:00", "2026-09-05T10:00:00-03:00", "E-F1")

        val csv = mvc.perform(get("/admin/inventarios/$b/exportar").param("formato", "csv").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsByteArray
        val linhas = String(csv, Charsets.UTF_8).removePrefix("﻿").trimEnd().split("\r\n")

        assertThat(linhas).hasSize(3)
        assertThat(linhas[1]).contains(";E-F1;Cadastrada;PAT-1;")
        assertThat(linhas[2]).startsWith(";E-F2;Faltando;PAT-2;")
    }

    @Test
    fun `exportacao de inventario em andamento marca a falta como provisoria`() {
        inventario("2026-09-01T09:00:00-03:00", "2026-09-01T10:00:00-03:00", "E-F1", "E-F2")
        val aberto = inventario("2026-09-05T09:00:00-03:00", null)

        val csv = mvc.perform(get("/admin/inventarios/$aberto/exportar").param("formato", "csv").with(Perfis.admin()))
            .andReturn().response.contentAsByteArray

        assertThat(String(csv, Charsets.UTF_8)).contains(";Faltando até agora;")
    }
}
