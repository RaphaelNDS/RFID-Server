package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.PedidoLocacaoEntity
import org.example.repository.PedidoLocacaoRepository
import org.example.request.CadastroRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.AvisoLocacaoService
import org.example.service.CatalogoService
import org.example.service.DadosCliente
import org.example.service.DadosPedido
import org.example.service.LocacaoService
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import java.time.LocalDate
import java.util.UUID

/** 1.20.0: resumo diário da locação pelos avisos e o Telegram na tela Status. */
class Versao1200IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var locacao: LocacaoService
    @Autowired lateinit var pedidos: PedidoLocacaoRepository
    @Autowired lateinit var aviso: AvisoLocacaoService

    private val s = UUID.randomUUID().toString().take(6).uppercase()

    @Test
    fun `resumo diario lista a devolucao atrasada`() {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Leitor $s")).id
        rfid.cadastrarTag(CadastroRequest(tag = "AT1-$s", codigoInterno = "TI-AT1-$s", patrimonio = "P-AT1-$s", tipoEquipamentoId = tipo))
        val c = locacao.salvarCliente(null, DadosCliente("Atrasado $s"))
        val p = locacao.criarPedido(DadosPedido(c.id, LocalDate.now().plusDays(1)))
        locacao.adicionarLinha(p.id, null, null, null, null, "AT1-$s")
        locacao.registrarSaida(p.id, listOf("AT1-$s"))
        // Volta a previsão para o passado direto no banco (a tela não deixa)
        val salvo: PedidoLocacaoEntity = pedidos.findById(p.id).get()
        salvo.previsaoDevolucao = LocalDate.now().minusDays(3)
        pedidos.saveAndFlush(salvo)

        val (assunto, texto) = aviso.montar()!!
        assertThat(assunto).startsWith("Locação: ").contains("devolução(ões) atrasada(s)")
        assertThat(texto).contains("Pedido nº ${p.id} — Atrasado $s").contains("3 dia(s) de atraso")
    }

    @Test
    fun `tela status mostra o telegram desligado com a explicacao`() {
        mvc.perform(get("/status").with(Perfis.admin()))
            .andExpect(content().string(containsString("Avisos pelo Telegram")))
            .andExpect(content().string(containsString("RFID_TELEGRAM_TOKEN")))
    }
}
