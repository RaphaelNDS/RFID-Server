package org.example.integracao

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.request.CadastroRequest
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.AcessorioService
import org.example.service.CatalogoService
import org.example.service.DadosCliente
import org.example.service.DadosPedido
import org.example.service.LocacaoService
import org.example.service.QuantidadesAcessorio
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

/** 1.18.0: kits e acessórios da locação. */
class Versao1180IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var locacao: LocacaoService
    @Autowired lateinit var acessorios: AcessorioService

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val hoje = LocalDate.now()

    private fun cadastrar(epc: String, tipo: Long, marca: Long? = null, modelo: Long? = null) =
        rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-$epc", patrimonio = "P-$epc", tipoEquipamentoId = tipo, marcaId = marca, modeloId = modelo))

    @Test
    fun `o kit do modelo vale antes do kit do tipo e entra no pedido com a linha`() {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Coletor $s")).id
        val marca = catalogo.salvarMarca(MarcaRequest("Zebra $s", tipo)).id
        val modelo = catalogo.salvarModelo(ModeloRequest("MC $s", marca)).id
        val carregador = acessorios.salvar(null, "Carregador $s")
        val bateria = acessorios.salvar(null, "Bateria $s")
        val alca = acessorios.salvar(null, "Alça $s")
        assertThatThrownBy { acessorios.salvar(null, "  carregador $s ") }.hasMessageContaining("Já existe")

        acessorios.adicionarAoKit(tipo, null, carregador.id, 1)                    // kit do tipo
        acessorios.adicionarAoKit(null, modelo, carregador.id, 1)                  // kit do modelo (o tipo vem do modelo)
        val kitModelo = acessorios.adicionarAoKit(tipo, modelo, bateria.id, 2)
        assertThat(kitModelo.itens.map { it.acessorio to it.quantidade }).containsExactlyInAnyOrder("Carregador $s" to 1, "Bateria $s" to 2)
        acessorios.adicionarAoKit(tipo, modelo, bateria.id, 3)                     // de novo: só muda a quantidade
        assertThat(acessorios.kitPara(tipo, modelo).single { it.acessorioId == bateria.id }.quantidade).isEqualTo(3)

        val c = locacao.salvarCliente(null, DadosCliente("Feira $s"))
        val p = locacao.criarPedido(DadosPedido(c.id, hoje.plusDays(5)))
        val linhaModelo = locacao.adicionarLinha(p.id, tipo, marca, modelo, 2, null)
        val linhaTipo = locacao.adicionarLinha(p.id, tipo, null, null, 3, null)
        val doPedido = acessorios.doPedido(p.id)
        assertThat(doPedido.filter { it.linhaId == linhaModelo.id }.map { it.descricao to it.quantidade })
            .containsExactlyInAnyOrder("Carregador $s" to 2, "Bateria $s" to 6)
        assertThat(doPedido.filter { it.linhaId == linhaTipo.id }.map { it.descricao to it.quantidade }).containsExactly("Carregador $s" to 3)

        // Avulso; tirar a linha leva o kit dela junto (nada enviado)
        acessorios.adicionarAvulso(p.id, alca.id, 1)
        locacao.removerLinha(p.id, linhaTipo.id)
        assertThat(acessorios.doPedido(p.id).map { it.descricao }).containsExactlyInAnyOrder("Carregador $s", "Bateria $s", "Alça $s")

        // Desativado não entra em kit nem em pedido
        acessorios.salvar(alca.id, "Alça $s", ativo = false)
        assertThatThrownBy { acessorios.adicionarAvulso(p.id, alca.id, 1) }.hasMessageContaining("desativado")
        assertThatThrownBy { acessorios.adicionarAoKit(tipo, null, alca.id, 1) }.hasMessageContaining("desativado")
    }

    @Test
    fun `envio e devolucao dos acessorios, o que falta e os PDFs`() {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Impressora $s")).id
        val cabo = acessorios.salvar(null, "Cabo USB $s")
        val fonte = acessorios.salvar(null, "Fonte $s")
        acessorios.adicionarAoKit(tipo, null, cabo.id, 1)
        acessorios.adicionarAoKit(tipo, null, fonte.id, 1)
        cadastrar("AC1-$s", tipo)
        cadastrar("AC2-$s", tipo)
        val c = locacao.salvarCliente(null, DadosCliente("Loja $s"))
        val p = locacao.criarPedido(DadosPedido(c.id, hoje.plusDays(3)))
        locacao.adicionarLinha(p.id, tipo, null, null, 2, null)
        val (aCabo, aFonte) = acessorios.doPedido(p.id).sortedBy { it.descricao }.let { it[0] to it[1] }

        // A folha de separação lista os acessórios a separar
        val folha = mvc.perform(get("/admin/pedidos/${p.id}/separacao.pdf").with(Perfis.usuario())).andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertThat(PDDocument.load(folha).use { PDFTextStripper().getText(it) }).contains("Acessórios a separar", "Cabo USB $s", "Fonte $s")

        // Tudo enviado pela tela, e saem os equipamentos
        mvc.perform(post("/admin/pedidos/${p.id}/acessorios").param("acao", "enviarTudo").with(Perfis.gestor()).with(csrf()))
            .andExpect(status().is3xxRedirection).andExpect(flash().attribute("msg", containsString("enviados")))
        assertThat(acessorios.doPedido(p.id).map { it.enviada }).containsOnly(2)
        // Enviado não sai do pedido
        assertThatThrownBy { acessorios.removerDoPedido(p.id, aFonte.id) }.hasMessageContaining("Já foram enviados")
        locacao.registrarSaida(p.id, listOf("AC1-$s", "AC2-$s"))

        // Voltam os equipamentos, mas só 1 fonte
        locacao.registrarDevolucao(listOf("AC1-$s", "AC2-$s"))
        mvc.perform(post("/admin/pedidos/${p.id}/acessorios").param("acao", "salvar")
            .param("devolvida_${aCabo.id}", "2").param("devolvida_${aFonte.id}", "1").with(Perfis.gestor()).with(csrf()))
            .andExpect(flash().attribute("msg", containsString("Faltam voltar 1")))
        // Devolvido não passa do enviado
        assertThatThrownBy { acessorios.registrar(p.id, mapOf(aFonte.id to QuantidadesAcessorio(null, 3))) }.hasMessageContaining("não pode passar")

        val d = locacao.detalhar(p.id)
        assertThat(d.pedido.situacao.name).isEqualTo("ENCERRADO")
        assertThat(d.acessoriosFaltando).isEqualTo(1)
        // Encerrado: só a devolução muda
        assertThatThrownBy { acessorios.registrar(p.id, mapOf(aFonte.id to QuantidadesAcessorio(5, null))) }.hasMessageContaining("encerrado")

        mvc.perform(get("/admin/pedidos/${p.id}").with(Perfis.usuario()))
            .andExpect(content().string(containsString("Faltam 1 acessório(s)")))
            .andExpect(content().string(containsString("Fonte $s")))
        val pendente = acessorios.aCobrar().single { it.pedidoId == p.id }
        assertThat(pendente.descricao).isEqualTo("Fonte $s")
        assertThat(pendente.faltando).isEqualTo(1)
        mvc.perform(get("/admin/relatorios-locacao").with(Perfis.usuario()))
            .andExpect(content().string(containsString("Acessórios que não voltaram")))
            .andExpect(content().string(containsString("Fonte $s")))

        val contrato = mvc.perform(get("/admin/pedidos/${p.id}/contrato.pdf").with(Perfis.usuario())).andReturn().response.contentAsByteArray
        assertThat(PDDocument.load(contrato).use { PDFTextStripper().getText(it) }).contains("Acessórios", "Cabo USB $s", "Fonte $s")

        // A fonte chegou depois: Tudo devolvido, mesmo com o pedido encerrado
        acessorios.devolverTudo(p.id)
        assertThat(acessorios.aCobrar().none { it.pedidoId == p.id }).isTrue()
    }

    @Test
    fun `tela de acessorios e kits e as permissoes`() {
        mvc.perform(post("/admin/acessorios").param("nome", "Berço $s").with(Perfis.gestor()).with(csrf()))
            .andExpect(status().is3xxRedirection).andExpect(flash().attribute("msg", containsString("Berço $s")))
        val berco = acessorios.listar().single { it.nome == "Berço $s" }
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Leitor $s")).id
        mvc.perform(post("/admin/acessorios/kits").param("tipoEquipamentoId", "$tipo").param("acessorioId", "${berco.id}").param("quantidade", "1")
            .with(Perfis.gestor()).with(csrf())).andExpect(flash().attribute("msg", containsString("Leitor $s")))
        mvc.perform(get("/admin/acessorios").with(Perfis.usuario()))
            .andExpect(status().isOk).andExpect(content().string(containsString("Berço $s"))).andExpect(content().string(containsString("kit do tipo")))
        // Só ver: não cadastra
        mvc.perform(post("/admin/acessorios").param("nome", "Outro $s").with(Perfis.usuario()).with(csrf())).andExpect(status().isForbidden)
        mvc.perform(get("/admin/acessorios").with(Perfis.somenteCatalogo())).andExpect(status().isForbidden)

        val item = acessorios.kits().single { it.tipoId == tipo }.itens.single()
        mvc.perform(post("/admin/acessorios/kits/${item.id}/excluir").with(Perfis.gestor()).with(csrf())).andExpect(status().is3xxRedirection)
        assertThat(acessorios.kits().none { it.tipoId == tipo }).isTrue()
    }
}
