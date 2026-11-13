package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.MotivoBaixa
import org.example.model.SituacaoBem
import org.example.repository.HistoricoEtiquetaRepository
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.service.AcaoDivergencia
import org.example.service.DivergenciaInventarioService
import org.example.service.EsperadoInventarioService
import org.example.service.PatrimonioCadastrosService
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.example.suporte.comoOperador
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

/** 1.6.1: ação sobre as divergências do inventário (a transferência saiu na 2.0.0). */
class Versao161IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var cadastros: PatrimonioCadastrosService
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var historico: HistoricoEtiquetaRepository
    @Autowired lateinit var divergencias: DivergenciaInventarioService
    @Autowired lateinit var esperados: EsperadoInventarioService
    @Autowired lateinit var operadores: OperadorTeste

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val token by lazy { operadores.sessaoPadrao(mvc, "v161-$s@x.com".lowercase()) }

    private fun tag(epc: String) = tags.findByTagReal(epc).get()

    private fun cadastrar(epc: String, localId: Long? = null, situacao: String? = null, patrimonio: String = "P-$epc") =
        rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-$epc", patrimonio = patrimonio,
            localId = localId, situacao = situacao))

    private fun coletor(url: String, json: String) =
        mvc.perform(post(url).comoOperador(token).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk)

    private fun inventario(local: String, vararg epcs: String, finalizar: Boolean = true): String {
        val id = UUID.randomUUID().toString()
        coletor("/api/android/inventarios", """{"id":"$id","local":"$local"}""")
        coletor("/api/android/inventarios/$id/itens", """{"itens":[${epcs.joinToString { """{"tag":"$it"}""" }}]}""")
        if (finalizar) coletor("/api/android/inventarios/$id/finalizar", "{}")
        return id
    }

    // ---------- Divergências ----------

    @Test
    fun `divergencias do inventario finalizado marcam extraviado e dao baixa so nos que faltaram`() {
        val local = cadastros.salvarLocal(null, "Sala D $s", null)
        listOf("E-DV1-$s", "E-DV2-$s", "E-DV3-$s").forEach { cadastrar(it, localId = local.id) }
        val id = inventario("Sala D $s", "E-DV1-$s")
        assertThat(esperados.faltandoNoInventario(id).faltando.map { it.tag }).containsExactlyInAnyOrder("E-DV2-$s", "E-DV3-$s")

        assertThatThrownBy { divergencias.tratar(id, listOf("E-DV1-$s"), AcaoDivergencia.EXTRAVIADO) }.hasMessageContaining("Não faltou")
        assertThatThrownBy { divergencias.tratar(id, emptyList(), AcaoDivergencia.EXTRAVIADO) }.hasMessageContaining("Marque ao menos um")
        assertThatThrownBy { divergencias.tratar(id, listOf("E-DV3-$s"), AcaoDivergencia.BAIXA) }.hasMessageContaining("motivo")

        val r = divergencias.tratar(id, listOf("e-dv2-$s"), AcaoDivergencia.EXTRAVIADO)
        assertThat(r.tratados).isEqualTo(1)
        assertThat(tag("E-DV2-$s").situacao).isEqualTo(SituacaoBem.EXTRAVIADO)
        assertThat(historico.findByTagRealOrderByMomentoDescIdDesc("E-DV2-$s").first { it.campo == "Situação" }.origem)
            .contains("Inventário de Sala D $s")
        // Marcar de novo não muda nada
        assertThat(divergencias.tratar(id, listOf("E-DV2-$s"), AcaoDivergencia.EXTRAVIADO).ignorados).hasSize(1)

        divergencias.tratar(id, listOf("E-DV3-$s"), AcaoDivergencia.BAIXA, MotivoBaixa.FURTO_ROUBO, observacao = "BO 123")
        assertThat(tag("E-DV3-$s").baixado).isTrue()
        assertThat(tag("E-DV3-$s").baixaObservacao).isEqualTo("BO 123")
        // O extraviado continua na lista do que faltou; o baixado sai
        assertThat(esperados.faltandoNoInventario(id).faltando.map { it.tag }).containsExactly("E-DV2-$s")

        // Esperado do coletor com a situação de cada bem (pedido do app, 1.6.1)
        mvc.perform(get("/api/android/inventarios/esperado").param("local", "Sala D $s").comoOperador(token)).andExpect(status().isOk)
            .andExpect(jsonPath("$.itens[?(@.tag == 'E-DV2-$s')].situacao").value(hasItem("EXTRAVIADO")))
            .andExpect(jsonPath("$.itens[?(@.tag == 'E-DV2-$s')].situacaoRotulo").value(hasItem("Extraviado")))
            .andExpect(jsonPath("$.itens[?(@.tag == 'E-DV1-$s')].situacao").value(hasItem("DISPONIVEL")))
    }

    @Test
    fun `inventario em andamento nao aceita acao sobre as divergencias`() {
        val local = cadastros.salvarLocal(null, "Sala A $s", null)
        cadastrar("E-AND1-$s", localId = local.id)
        cadastrar("E-AND2-$s", localId = local.id)
        val id = inventario("Sala A $s", "E-AND1-$s", finalizar = false)
        assertThatThrownBy { divergencias.tratar(id, listOf("E-AND2-$s"), AcaoDivergencia.EXTRAVIADO) }.hasMessageContaining("não foi finalizado")
        mvc.perform(get("/admin/inventarios/$id").with(Perfis.gestor())).andExpect(status().isOk)
            .andExpect(content().string(not(containsString("Com os marcados"))))
    }

    // ---------- Telas e permissões ----------

    @Test
    fun `telas de divergencias e permissoes`() {
        val sala = cadastros.salvarLocal(null, "Sala W $s", null)
        val destino = cadastros.salvarLocal(null, "Destino W $s", null)
        cadastrar("E-W1-$s", localId = sala.id)
        cadastrar("E-W2-$s", localId = sala.id)
        val id = inventario("Sala W $s", "E-W1-$s", "E-NOVA-$s")

        // Detalhe do inventário: ações para quem edita etiquetas, cadastrar a lida sem cadastro já no local
        mvc.perform(get("/admin/inventarios/$id").with(Perfis.gestor())).andExpect(status().isOk)
            .andExpect(content().string(containsString("Com os marcados")))
            .andExpect(content().string(containsString("name=\"tags\" value=\"E-W2-$s\"")))
            .andExpect(content().string(containsString("/admin/cadastro?tag=E-NOVA-$s&amp;local=${sala.id}")))
        mvc.perform(get("/admin/inventarios/$id").with(Perfis.usuario())).andExpect(status().isOk)
            .andExpect(content().string(not(containsString("Com os marcados"))))
        mvc.perform(get("/admin/cadastro").param("tag", "E-NOVA-$s").param("local", sala.id.toString()).with(Perfis.gestor()))
            .andExpect(content().string(containsString("value=\"${sala.id}\" selected")))

        // Ação pela tela
        mvc.perform(post("/admin/inventarios/$id/divergencias").with(Perfis.gestor()).with(csrf())
            .param("tags", "E-W2-$s").param("acao", "baixa").param("motivoBaixa", "PERDA"))
            .andExpect(status().is3xxRedirection).andExpect(flash().attribute("msg", containsString("1 bem(ns) baixado(s)")))
        assertThat(tag("E-W2-$s").baixaMotivo).isEqualTo(MotivoBaixa.PERDA)
        mvc.perform(post("/admin/inventarios/$id/divergencias").with(Perfis.gestor()).with(csrf()).param("tags", "E-W1-$s").param("acao", "extraviado"))
            .andExpect(flash().attribute("erro", containsString("Não faltou")))
        mvc.perform(post("/admin/inventarios/$id/divergencias").with(Perfis.usuario()).with(csrf()).param("tags", "E-W1-$s").param("acao", "extraviado"))
            .andExpect(status().isForbidden)

    }
}
