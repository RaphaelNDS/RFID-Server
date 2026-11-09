package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.MotivoBaixa
import org.example.model.SituacaoBem
import org.example.repository.HistoricoEtiquetaRepository
import org.example.repository.TagRepository
import org.example.repository.TransferenciaItemRepository
import org.example.repository.TransferenciaRepository
import org.example.request.CadastroRequest
import org.example.service.AcaoDivergencia
import org.example.service.DivergenciaInventarioService
import org.example.service.EsperadoInventarioService
import org.example.service.PatrimonioCadastrosService
import org.example.service.PedidoTransferencia
import org.example.service.RfidService
import org.example.service.TransferenciaService
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

/** 1.6.1: transferência de bens com guia e ação sobre as divergências do inventário. */
class Versao161IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var cadastros: PatrimonioCadastrosService
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var historico: HistoricoEtiquetaRepository
    @Autowired lateinit var transferencias: TransferenciaService
    @Autowired lateinit var guias: TransferenciaRepository
    @Autowired lateinit var itensGuia: TransferenciaItemRepository
    @Autowired lateinit var divergencias: DivergenciaInventarioService
    @Autowired lateinit var esperados: EsperadoInventarioService
    @Autowired lateinit var operadores: OperadorTeste

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val token by lazy { operadores.sessaoPadrao(mvc, "v161-$s@x.com".lowercase()) }

    private fun tag(epc: String) = tags.findByTagReal(epc).get()

    private fun cadastrar(epc: String, localId: Long? = null, responsavelId: Long? = null, situacao: String? = null, patrimonio: String = "P-$epc") =
        rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-$epc", patrimonio = patrimonio,
            localId = localId, responsavelId = responsavelId, situacao = situacao))

    private fun coletor(url: String, json: String) =
        mvc.perform(post(url).comoOperador(token).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk)

    private fun inventario(local: String, vararg epcs: String, finalizar: Boolean = true): String {
        val id = UUID.randomUUID().toString()
        coletor("/api/android/inventarios", """{"id":"$id","local":"$local"}""")
        coletor("/api/android/inventarios/$id/itens", """{"itens":[${epcs.joinToString { """{"tag":"$it"}""" }}]}""")
        if (finalizar) coletor("/api/android/inventarios/$id/finalizar", "{}")
        return id
    }

    // ---------- Transferência ----------

    @Test
    fun `transferencia muda local e responsavel grava a guia com a origem e o historico`() {
        val matriz = cadastros.salvarUnidade(null, "Matriz $s")
        val sala = cadastros.salvarLocal(null, "Sala 1 $s", matriz.id)
        val almox = cadastros.salvarLocal(null, "Almox $s", matriz.id)
        val ana = cadastros.salvarResponsavel(null, "Ana $s", "TI", null, null)
        val bia = cadastros.salvarResponsavel(null, "Bia $s", "RH", null, null)
        cadastrar("E-TR1-$s", localId = sala.id, responsavelId = ana.id)
        cadastrar("E-TR2-$s", localId = sala.id, situacao = "EXTRAVIADO")

        // Pela etiqueta e pelo patrimônio, sem diferenciar maiúsculas na etiqueta
        val guia = transferencias.transferir(PedidoTransferencia(listOf("e-tr1-$s", "P-E-TR2-$s"), almox.id, bia.id,
            solicitante = "Gerente $s", motivo = "Mudança de setor"))

        assertThat(tag("E-TR1-$s").localId).isEqualTo(almox.id)
        assertThat(tag("E-TR1-$s").responsavelId).isEqualTo(bia.id)
        assertThat(tag("E-TR2-$s").situacao).isEqualTo(SituacaoBem.EM_USO)   // extraviado que muda de local foi achado
        assertThat(guia.localDestino).isEqualTo("Matriz $s › Almox $s")
        assertThat(guia.responsavelDestino).isEqualTo("Bia $s (RH)")
        assertThat(guia.data).isEqualTo(LocalDate.now())
        val itens = itensGuia.findAllByTransferenciaIdOrderByIdAsc(guia.id).associateBy { it.tagReal }
        assertThat(itens.getValue("E-TR1-$s").localAnterior).isEqualTo("Matriz $s › Sala 1 $s")
        assertThat(itens.getValue("E-TR1-$s").responsavelAnterior).isEqualTo("Ana $s")
        assertThat(itens.getValue("E-TR2-$s").responsavelAnterior).isNull()

        val linhas = historico.findByTagRealOrderByMomentoDescIdDesc("E-TR1-$s").filter { it.campo == "Local" || it.campo == "Responsável" }
        assertThat(linhas).hasSize(2)
        assertThat(linhas).allSatisfy { assertThat(it.origem).endsWith("Transferência nº ${guia.id}") }

        // Só o responsável: o local de cada bem fica como está
        val so = transferencias.transferir(PedidoTransferencia(listOf("E-TR1-$s"), null, ana.id))
        assertThat(so.localDestino).isNull()
        assertThat(tag("E-TR1-$s").localId).isEqualTo(almox.id)
        assertThat(tag("E-TR1-$s").responsavelId).isEqualTo(ana.id)
    }

    @Test
    fun `transferencia recusada nao muda nada`() {
        val sala = cadastros.salvarLocal(null, "Sala R $s", null)
        val outra = cadastros.salvarLocal(null, "Outra R $s", null)
        val fechada = cadastros.salvarLocal(null, "Fechada R $s", null).let { cadastros.salvarLocal(it.id, it.nome, null, ativo = false) }
        cadastrar("E-REC1-$s", localId = sala.id)
        cadastrar("E-REC2-$s", localId = outra.id)
        cadastrar("E-BAIX-$s")
        rfid.darBaixa(tag("E-BAIX-$s").id, MotivoBaixa.VENDA, null, null)
        val antes = guias.count()

        fun recusa(pedido: PedidoTransferencia, mensagem: String) =
            assertThatThrownBy { transferencias.transferir(pedido) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(mensagem)

        recusa(PedidoTransferencia(listOf("E-REC1-$s"), null, null), "Escolha o local ou o responsável")
        recusa(PedidoTransferencia(emptyList(), outra.id, null), "Informe os bens")
        recusa(PedidoTransferencia(listOf("E-REC1-$s", "NAO-EXISTE-$s"), outra.id, null), "NAO-EXISTE-$s")
        recusa(PedidoTransferencia(listOf("E-REC1-$s", "E-BAIX-$s"), outra.id, null), "baixado")
        // E-REC2 já está na Outra: a guia não lista bem que não saiu do lugar, e o E-REC1 também não muda
        recusa(PedidoTransferencia(listOf("E-REC1-$s", "E-REC2-$s"), outra.id, null), "Já está(ão) no destino: P-E-REC2-$s")
        recusa(PedidoTransferencia(listOf("E-REC1-$s"), fechada.id, null), "desativado")
        recusa(PedidoTransferencia(listOf("E-REC1-$s"), outra.id, null, data = LocalDate.now().plusDays(1)), "futuro")

        assertThat(tag("E-REC1-$s").localId).isEqualTo(sala.id)
        assertThat(guias.count()).isEqualTo(antes)
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
            .andExpect(jsonPath("$.itens[?(@.tag == 'E-DV1-$s')].situacao").value(hasItem("EM_USO")))
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

    @Test
    fun `transferir os que faltaram pelo inventario liga a guia ao inventario e eles saem do que faltou`() {
        val sala = cadastros.salvarLocal(null, "Sala X $s", null)
        val achado = cadastros.salvarLocal(null, "Sala Y $s", null)
        cadastrar("E-TX1-$s", localId = sala.id)
        cadastrar("E-TX2-$s", localId = sala.id)
        val id = inventario("Sala X $s", "E-TX1-$s")

        // O botão Transferir manda os marcados para o formulário, que já confere os bens
        mvc.perform(post("/admin/transferencias/nova").with(Perfis.gestor()).with(csrf()).param("tags", "E-TX2-$s").param("inventario", id))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("P-E-TX2-$s")))
            .andExpect(content().string(containsString("Sala X $s")))
            .andExpect(content().string(containsString("name=\"inventario\"")))

        mvc.perform(post("/admin/transferencias").with(Perfis.gestor()).with(csrf())
            .param("codigos", "E-TX2-$s").param("inventario", id).param("localDestino", achado.id.toString()))
            .andExpect(status().is3xxRedirection).andExpect(redirectedUrlPattern("/admin/transferencias/*"))

        assertThat(tag("E-TX2-$s").localId).isEqualTo(achado.id)
        val comparacao = esperados.faltandoNoInventario(id)
        assertThat(comparacao.faltando).isEmpty()
        mvc.perform(get("/admin/inventarios/$id").with(Perfis.gestor())).andExpect(status().isOk)
            .andExpect(content().string(containsString("Transferências feitas a partir deste inventário")))
    }

    // ---------- Telas e permissões ----------

    @Test
    fun `telas de transferencia divergencias e permissoes`() {
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

        // Transferência pela tela: recusa volta ao formulário com o texto; certa vai para a guia
        mvc.perform(post("/admin/transferencias").with(Perfis.gestor()).with(csrf()).param("codigos", "E-W1-$s\nNAO-$s"))
            .andExpect(status().isOk).andExpect(content().string(containsString("Escolha o local ou o responsável")))
            .andExpect(content().string(containsString("NAO-$s")))
        val resposta = mvc.perform(post("/admin/transferencias").with(Perfis.gestor()).with(csrf())
            .param("codigos", "E-W1-$s").param("localDestino", destino.id.toString()).param("data", LocalDate.now().toString())
            .param("solicitante", "Chefe $s")).andExpect(status().is3xxRedirection).andReturn().response
        val urlGuia = resposta.redirectedUrl!!
        mvc.perform(get(urlGuia).with(Perfis.usuario())).andExpect(status().isOk)
            .andExpect(content().string(containsString("Guia de transferência de bens")))
            .andExpect(content().string(containsString("Destino W $s")))
            .andExpect(content().string(containsString("Chefe $s")))
            .andExpect(content().string(containsString("Sala W $s")))
        mvc.perform(get("/admin/transferencias").param("busca", "p-e-w1-$s").with(Perfis.usuario())).andExpect(status().isOk)
            .andExpect(content().string(containsString(urlGuia)))
        mvc.perform(get("/admin/transferencias/nova").with(Perfis.usuario())).andExpect(status().isForbidden)
        mvc.perform(post("/admin/transferencias").with(Perfis.usuario()).with(csrf()).param("codigos", "E-W1-$s").param("localDestino", sala.id.toString()))
            .andExpect(status().isForbidden)

        // Atalhos: ficha do bem e lista filtrada por local
        mvc.perform(get("/admin/etiquetas/${tag("E-W1-$s").id}/editar").with(Perfis.gestor()))
            .andExpect(content().string(containsString("/admin/transferencias/nova?tags=E-W1-$s")))
        mvc.perform(get("/admin/tags-cadastradas").param("local", destino.id.toString()).with(Perfis.gestor()))
            .andExpect(content().string(containsString("/admin/transferencias/nova?deLocal=${destino.id}")))
        mvc.perform(get("/admin/transferencias/nova").param("deLocal", destino.id.toString()).with(Perfis.gestor()))
            .andExpect(content().string(containsString("E-W1-$s")))
    }
}
