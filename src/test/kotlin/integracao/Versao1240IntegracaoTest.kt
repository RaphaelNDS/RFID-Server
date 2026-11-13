package org.example.integracao

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.repository.PedidoLocacaoRepository
import org.example.request.CadastroRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.AgendaService
import org.example.service.CatalogoService
import org.example.service.DadosCliente
import org.example.service.DadosPedido
import org.example.service.LocacaoService
import org.example.service.RfidService
import org.example.service.TipoAgenda
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
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** 1.24.0: agenda de entregas e retiradas. */
class Versao1240IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var locacao: LocacaoService
    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var pedidos: PedidoLocacaoRepository

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val hoje = LocalDate.now()

    @Test
    fun `entrega pela saida prevista, retirada pela devolucao prevista, atrasados so hoje e roteiro por responsavel`() {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Totem $s")).id
        rfid.cadastrarTag(CadastroRequest(tag = "AG1-$s", codigoInterno = "TI-AG1-$s", patrimonio = "P-AG1-$s", tipoEquipamentoId = tipo))
        val c = locacao.salvarCliente(null, org.example.service.DadosCliente("Feira $s", contato = "Rita", telefone = "11 99999-0000", endereco = "Rua A, 10"))

        // Entrega amanhã: 2 totens (nenhum saiu)
        val entrega = locacao.criarPedido(DadosPedido(c.id, hoje.plusDays(5), hoje.plusDays(1)))
        locacao.adicionarLinha(entrega.id, tipo, null, null, 2, null)
        // Retirada amanhã: 1 na rua
        val retirada = locacao.criarPedido(DadosPedido(c.id, hoje.plusDays(1)))
        locacao.adicionarLinha(retirada.id, null, null, null, null, "AG1-$s")
        locacao.registrarSaida(retirada.id, listOf("AG1-$s"))
        // Entrega atrasada: saída prevista ontem (só aparece em "hoje")
        val atrasada = locacao.criarPedido(DadosPedido(c.id, hoje.plusDays(5), hoje))
        locacao.adicionarLinha(atrasada.id, tipo, null, null, 1, null)
        pedidos.findById(atrasada.id).get().also { it.saidaPrevista = hoje.minusDays(1); pedidos.saveAndFlush(it) }

        agenda.definir(entrega.id, TipoAgenda.ENTREGA, "João", "9:30")
        agenda.definir(retirada.id, TipoAgenda.RETIRADA, " João ", "08:00")
        assertThatThrownBy { agenda.definir(entrega.id, TipoAgenda.ENTREGA, "João", "25:00") }.hasMessageContaining("Hora inválida")

        val amanha = agenda.agenda(hoje.plusDays(1))
        assertThat(amanha.entregas.map { it.pedido.id }).containsExactly(entrega.id)
        assertThat(amanha.entregas.single().quantidade).isEqualTo(2)
        assertThat(amanha.entregas.single().hora).isEqualTo(LocalTime.of(9, 30))
        assertThat(amanha.retiradas.map { it.pedido.id }).containsExactly(retirada.id)
        assertThat(amanha.retiradas.single().endereco).isEqualTo("Rua A, 10")
        assertThat(amanha.atrasados).isEmpty()
        // Roteiro: o João com a retirada das 8h antes da entrega das 9h30
        assertThat(amanha.porResponsavel.single().first).isEqualTo("João")
        assertThat(amanha.porResponsavel.single().second.map { it.tipo }).containsExactly(TipoAgenda.RETIRADA, TipoAgenda.ENTREGA)

        val deHoje = agenda.agenda(null)
        assertThat(deHoje.atrasados.map { it.pedido.id }).contains(atrasada.id)
        assertThat(deHoje.proximos.single { it.first == hoje.plusDays(1) }.second).isGreaterThanOrEqualTo(2)

        val detalhes = agenda.detalhes(amanha.entregas + amanha.retiradas)
        assertThat(detalhes["${entrega.id}-ENTREGA"]).containsExactly("2 × Totem $s")
        assertThat(detalhes["${retirada.id}-RETIRADA"]!!.single()).startsWith("P-AG1-$s")

        // Tela, alteração e PDF
        val dia = hoje.plusDays(1).toString()
        mvc.perform(get("/admin/agenda").param("dia", dia).with(Perfis.usuario()))
            .andExpect(content().string(containsString("Nº ${entrega.id} · Feira $s")))
            .andExpect(content().string(containsString("09:30")))
        mvc.perform(post("/admin/agenda/${entrega.id}/entrega").with(Perfis.admin()).with(csrf())
            .param("responsavel", "Paulo").param("hora", "10:15").param("dia", dia))
            .andExpect(flash().attribute("msg", containsString("entrega atualizada")))
        assertThat(locacao.pedido(entrega.id).entregaResponsavel).isEqualTo("Paulo")
        val pdf = mvc.perform(get("/admin/agenda/roteiro.pdf").param("dia", dia).with(Perfis.usuario())).andReturn().response.contentAsByteArray
        val texto = PDFTextStripper().getText(PDDocument.load(pdf))
        assertThat(texto).contains("Roteiro de entregas e retiradas").contains("Paulo").contains("João").contains("2 × Totem $s").contains("Rua A, 10")
        // Ver não altera
        mvc.perform(post("/admin/agenda/${entrega.id}/entrega").with(Perfis.usuario()).with(csrf()).param("responsavel", "X"))
            .andExpect { assertThat(it.response.status).isIn(302, 403) }
        assertThat(locacao.pedido(entrega.id).entregaResponsavel).isEqualTo("Paulo")
    }
}
