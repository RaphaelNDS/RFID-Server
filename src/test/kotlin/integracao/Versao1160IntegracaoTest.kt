package org.example.integracao

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.AreaAuditoria
import org.example.model.EstadoDevolucao
import org.example.model.ResultadoManutencao
import org.example.model.TipoManutencao
import org.example.repository.AuditoriaRepository
import org.example.repository.PedidoItemRepository
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.service.ConclusaoManutencao
import org.example.service.DadosCliente
import org.example.service.DadosPedido
import org.example.service.LinhaDoTempoService
import org.example.service.LocacaoService
import org.example.service.ManutencaoService
import org.example.service.PedidoManutencao
import org.example.service.RfidService
import org.example.service.TipoEvento
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** 1.16.0: linha do tempo do equipamento e prorrogação do pedido. */
class Versao1160IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var locacao: LocacaoService
    @Autowired lateinit var manutencoes: ManutencaoService
    @Autowired lateinit var linhaDoTempo: LinhaDoTempoService
    @Autowired lateinit var itens: PedidoItemRepository
    @Autowired lateinit var auditoria: AuditoriaRepository

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val hoje = LocalDate.now()

    private fun tag(epc: String) = tags.findByTagReal(epc).get()

    private fun pedidoCom(epc: String, cliente: String, devolucao: LocalDate = hoje.plusDays(5)): Long {
        val c = locacao.salvarCliente(null, DadosCliente(cliente))
        val p = locacao.criarPedido(DadosPedido(c.id, devolucao, localEntrega = "Obra $s"))
        locacao.adicionarLinha(p.id, null, null, null, null, epc)
        return p.id
    }

    @Test
    fun `linha do tempo junta cadastro, locacoes com avaria, manutencao com custo e mostra o resumo`() {
        rfid.cadastrarTag(CadastroRequest(tag = "LT-$s", codigoInterno = "TI-LT-$s", patrimonio = "P-LT-$s"))
        val tagId = tag("LT-$s").id

        // 1ª locação: volta com avaria e vai para a manutenção, que custa R$ 300
        val p1 = pedidoCom("LT-$s", "Alfa $s")
        locacao.registrarSaida(p1, listOf("LT-$s"))
        locacao.registrarDevolucao(listOf("LT-$s"))
        val i1 = itens.findAllByPedidoIdOrderByIdAsc(p1).single()
        locacao.conferirDevolucao(i1.id, EstadoDevolucao.AVARIA, true, "Tela trincada")
        val ordem = itens.findById(i1.id).get().manutencaoId!!
        manutencoes.concluir(ordem, ConclusaoManutencao(ResultadoManutencao.CONSERTADO, "Troca da tela", "300,00"))

        // 2ª locação: ainda com o cliente
        val p2 = pedidoCom("LT-$s", "Beta $s")
        locacao.registrarSaida(p2, listOf("LT-$s"))

        val l = linhaDoTempo.montar(tag("LT-$s"))
        assertThat(l.resumo.locacoes).isEqualTo(2)
        assertThat(l.resumo.clientes).isEqualTo(2)
        assertThat(l.resumo.manutencoes).isEqualTo(1)
        assertThat(l.resumo.custoManutencao).isEqualByComparingTo(BigDecimal("300.00"))
        assertThat(l.resumo.avarias).isEqualTo(1)
        assertThat(l.resumo.ultimoCliente).isEqualTo("Beta $s")

        val tipos = l.eventos.map { it.tipo }
        assertThat(tipos).contains(TipoEvento.CADASTRO, TipoEvento.SAIDA, TipoEvento.DEVOLUCAO, TipoEvento.CONFERENCIA, TipoEvento.MANUTENCAO)
        assertThat(l.eventos.count { it.tipo == TipoEvento.SAIDA }).isEqualTo(2)
        assertThat(l.eventos.first { it.tipo == TipoEvento.CONFERENCIA }.alerta).isTrue()
        // A mudança de situação da saída não aparece de novo como "Alterou Situação"
        assertThat(l.eventos.filter { it.tipo == TipoEvento.ALTERACAO }.map { it.titulo }).noneMatch { it.contains("Situação") }
        // Do mais novo para o mais antigo
        assertThat(l.eventos.map { it.momento }).isSortedAccordingTo(Comparator.reverseOrder())

        mvc.perform(get("/admin/etiquetas/$tagId/linha-do-tempo").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Saiu no pedido nº $p2 para Beta $s")))
            .andExpect(content().string(containsString("Devolução conferida: Com avaria")))
            .andExpect(content().string(containsString("custo total R\$ 300,00")))
        mvc.perform(get("/admin/etiquetas/$tagId/editar").with(Perfis.admin())).andExpect(content().string(containsString("/linha-do-tempo")))
        mvc.perform(get("/admin/etiquetas/$tagId/linha-do-tempo").with(Perfis.somenteCatalogo())).andExpect(status().isForbidden)
    }

    @Test
    fun `prorrogar muda a devolucao, guarda o historico e aparece no pedido e no contrato`() {
        rfid.cadastrarTag(CadastroRequest(tag = "PR-$s", codigoInterno = "TI-PR-$s", patrimonio = "P-PR-$s"))
        val id = pedidoCom("PR-$s", "Gama $s", hoje.plusDays(3))
        locacao.registrarSaida(id, listOf("PR-$s"))

        mvc.perform(post("/admin/pedidos/$id/prorrogacao").with(Perfis.gestor()).with(csrf())
            .param("data", hoje.plusDays(18).toString()).param("motivo", "Cliente pediu mais 15 dias"))
            .andExpect(flash().attribute("msg", containsString("Devolução prevista mudada")))
        assertThat(locacao.pedido(id).previsaoDevolucao).isEqualTo(hoje.plusDays(18))
        val historico = locacao.prorrogacoesDo(id).single()
        assertThat(historico.dataAnterior).isEqualTo(hoje.plusDays(3))
        assertThat(historico.motivo).isEqualTo("Cliente pediu mais 15 dias")
        assertThat(historico.registradaPor).isEqualTo("gestor@x.com")
        assertThat(auditoria.findAll().any { it.area == AreaAuditoria.PATRIMONIO && it.acao == "Devolução prorrogada" && it.alvo == "Pedido nº $id" }).isTrue()

        // A edição dos dados também registra
        locacao.editarPedido(id, DadosPedido(locacao.pedido(id).clienteId, hoje.plusDays(20), localEntrega = "Obra $s"))
        assertThat(locacao.prorrogacoesDo(id).map { it.motivo }).containsExactly("Cliente pediu mais 15 dias", "Alterada nos dados do pedido")

        mvc.perform(get("/admin/pedidos/$id").with(Perfis.usuario()))
            .andExpect(content().string(containsString("Cliente pediu mais 15 dias")))
            .andExpect(content().string(containsString("mudou 2 vez(es)")))
        val pdf = mvc.perform(get("/admin/pedidos/$id/contrato.pdf").with(Perfis.usuario()))
            .andExpect(content().contentType(MediaType.APPLICATION_PDF)).andReturn().response.contentAsByteArray
        assertThat(PDDocument.load(pdf).use { PDFTextStripper().getText(it) }.replace(Regex("\r?\n"), " ")).contains("Prorrogação", "Cliente pediu mais 15 dias")

        // Regras: motivo obrigatório, sem data no passado nem igual, e quem só vê não prorroga
        assertThatThrownBy { locacao.prorrogar(id, hoje.plusDays(30), " ") }.hasMessageContaining("motivo")
        assertThatThrownBy { locacao.prorrogar(id, hoje.minusDays(1), "x") }.hasMessageContaining("passado")
        assertThatThrownBy { locacao.prorrogar(id, hoje.plusDays(20), "x") }.hasMessageContaining("já está prevista")
        mvc.perform(post("/admin/pedidos/$id/prorrogacao").with(Perfis.usuario()).with(csrf())
            .param("data", hoje.plusDays(40).toString()).param("motivo", "x")).andExpect(status().isForbidden)

        // Pedido encerrado não muda mais
        locacao.registrarDevolucao(listOf("PR-$s"))
        assertThatThrownBy { locacao.prorrogar(id, hoje.plusDays(40), "tarde demais") }.hasMessageContaining("encerrado")
        assertThat(TipoManutencao.entries).isNotEmpty
        assertThat(PedidoManutencao("x", TipoManutencao.CORRETIVA, "y").codigo).isEqualTo("x")
    }
}
