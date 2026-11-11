package org.example.integracao

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.example.model.EstadoDevolucao
import org.example.repository.PedidoItemRepository
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.DadosCliente
import org.example.service.DadosPedido
import org.example.service.LocacaoService
import org.example.service.PatrimonioCadastrosService
import org.example.service.RelatorioLocacaoService
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

/** 1.17.0: folha de separação do pedido e relatórios internos da locação. */
class Versao1170IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var cadastros: PatrimonioCadastrosService
    @Autowired lateinit var locacao: LocacaoService
    @Autowired lateinit var relatorios: RelatorioLocacaoService
    @Autowired lateinit var itens: PedidoItemRepository

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val hoje = LocalDate.now()

    private fun cadastrar(epc: String, tipo: Long, local: Long? = null) =
        rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-$epc", patrimonio = "P-$epc", tipoEquipamentoId = tipo, localId = local))

    @Test
    fun `folha de separacao lista os disponiveis que servem, com o local, so do que falta`() {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Rádio $s")).id
        val outro = catalogo.salvarTipo(TipoEquipamentoRequest("Mesa $s")).id
        val galpao = cadastros.salvarLocal(null, "Galpão $s", null)
        (1..3).forEach { cadastrar("FS$it-$s", tipo, galpao.id) }
        cadastrar("FSX-$s", outro, galpao.id)
        val c = locacao.salvarCliente(null, DadosCliente("Evento $s"))
        val p = locacao.criarPedido(DadosPedido(c.id, hoje.plusDays(2), localEntrega = "Arena $s"))
        locacao.adicionarLinha(p.id, tipo, null, null, 2, null)
        locacao.registrarSaida(p.id, listOf("FS1-$s"))   // falta 1

        val folha = locacao.separacao(p.id)
        assertThat(folha.faltam).isEqualTo(1)
        val linha = folha.linhas.single()
        assertThat(linha.candidatos.map { it.tag }).containsExactlyInAnyOrder("FS2-$s", "FS3-$s")   // FS1 já saiu; FSX é de outro tipo
        assertThat(linha.candidatos.map { it.local }).allMatch { it!!.contains("Galpão $s") }

        val pdf = mvc.perform(get("/admin/pedidos/${p.id}/separacao.pdf").with(Perfis.usuario()))
            .andExpect(status().isOk).andExpect(content().contentType(MediaType.APPLICATION_PDF)).andReturn().response.contentAsByteArray
        val texto = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }.replace(Regex("\r?\n"), " ")
        assertThat(texto).contains("Folha de separação", "Evento $s", "Arena $s", "separar 1", "P-FS2-$s", "Galpão $s").doesNotContain("P-FSX-$s")
        mvc.perform(get("/admin/pedidos/${p.id}").with(Perfis.gestor())).andExpect(content().string(containsString("/separacao.pdf")))
        mvc.perform(get("/admin/pedidos/${p.id}/separacao.pdf").with(Perfis.somenteCatalogo())).andExpect(status().isForbidden)
    }

    @Test
    fun `relatorios mostram uso por modelo, parados, avarias e a pontualidade dos clientes`() {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Coletor $s")).id
        cadastrar("RL1-$s", tipo)
        cadastrar("RL2-$s", tipo)
        cadastrar("RL3-$s", tipo)   // nunca sai: parado
        val c = locacao.salvarCliente(null, DadosCliente("Lento $s"))
        // Saiu, voltou com avaria; e outro está com o cliente, com a devolução vencida
        val p1 = locacao.criarPedido(DadosPedido(c.id, hoje.plusDays(1)))
        locacao.adicionarLinha(p1.id, tipo, null, null, 1, null)
        locacao.registrarSaida(p1.id, listOf("RL1-$s"))
        locacao.registrarDevolucao(listOf("RL1-$s"))
        locacao.conferirDevolucao(itens.findAllByPedidoIdOrderByIdAsc(p1.id).single().id, EstadoDevolucao.AVARIA, false, "Riscado")
        val p2 = locacao.criarPedido(DadosPedido(c.id, hoje.plusDays(1)))
        locacao.adicionarLinha(p2.id, tipo, null, null, 1, null)
        locacao.registrarSaida(p2.id, listOf("RL2-$s"))
        locacao.editarPedido(p2.id, DadosPedido(c.id, hoje.minusDays(3)))

        val r = relatorios.relatorio(hoje.minusDays(30), hoje, diasParadoMinimo = 0)
        val uso = r.uso.single { it.modelo == "Coletor $s (sem marca)" }
        assertThat(uso.equipamentos).isEqualTo(3)
        assertThat(uso.locacoes).isEqualTo(2)
        assertThat(r.parados.map { it.patrimonio }).contains("P-RL3-$s").doesNotContain("P-RL2-$s")   // o RL2 está alugado
        val av = r.avarias.single { it.modelo == "Coletor $s (sem marca)" }
        assertThat(av.devolucoes).isEqualTo(1)
        assertThat(av.avarias).isEqualTo(1)
        val pont = r.atrasos.single { it.cliente == "Lento $s" }
        assertThat(pont.atrasadosAgora).isEqualTo(1)
        assertThat(pont.devolucoes).isEqualTo(1)

        // Com 60 dias de corte, o recém-cadastrado não aparece como parado
        assertThat(relatorios.relatorio(null, null).parados.map { it.patrimonio }).doesNotContain("P-RL3-$s")

        mvc.perform(get("/admin/relatorios-locacao").param("parado", "0").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Coletor $s (sem marca)")))
            .andExpect(content().string(containsString("Lento $s")))
            .andExpect(content().string(containsString("nunca alugado")))
        mvc.perform(get("/admin/relatorios-locacao").with(Perfis.somenteCatalogo())).andExpect(status().isForbidden)
    }
}
