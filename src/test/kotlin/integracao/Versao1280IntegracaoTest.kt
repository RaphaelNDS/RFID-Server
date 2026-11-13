package org.example.integracao

import org.example.model.TipoManutencao
import org.example.request.CadastroRequest
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.AgendaService
import org.example.service.CatalogoService
import org.example.service.DadosCliente
import org.example.service.DadosPedido
import org.example.service.LocacaoService
import org.example.service.ManutencaoService
import org.example.service.PedidoManutencao
import org.example.service.RfidService
import org.example.service.TipoAgenda
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

/** 1.28.0: painel para a TV do depósito. */
class Versao1280IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var locacao: LocacaoService
    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var manutencoes: ManutencaoService
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var catalogo: CatalogoService

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val hoje = LocalDate.now()

    @Test
    fun `a tela da TV mostra o que separar, o que volta hoje e a manutencao atrasada, e o miolo se atualiza`() {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Gerador $s")).id
        val marca = catalogo.salvarMarca(MarcaRequest("Marca $s", tipo)).id
        val modelo = catalogo.salvarModelo(ModeloRequest("G1", marca)).id
        (1..3).forEach { rfid.cadastrarTag(CadastroRequest(tag = "TV$it-$s", codigoInterno = "TI-TV$it-$s", patrimonio = "P-TV$it-$s", tipoEquipamentoId = tipo, marcaId = marca, modeloId = modelo)) }
        val cliente = locacao.salvarCliente(null, DadosCliente("Obra TV $s"))

        // Volta hoje, com quem busca e a hora
        val volta = locacao.criarPedido(DadosPedido(cliente.id, hoje, hoje))
        locacao.adicionarLinha(volta.id, tipo, marca, modelo, 1, null)
        locacao.registrarSaida(volta.id, listOf("TV1-$s"))
        agenda.definir(volta.id, TipoAgenda.RETIRADA, "Motorista $s", "15:30")

        // A separar amanhã
        val separar = locacao.criarPedido(DadosPedido(cliente.id, hoje.plusDays(5), hoje.plusDays(1)))
        locacao.adicionarLinha(separar.id, tipo, null, null, 2, null)

        manutencoes.abrir(PedidoManutencao("TV3-$s", TipoManutencao.CORRETIVA, "Não liga", "Oficina $s", hoje.minusDays(5), hoje.minusDays(1)))

        mvc.perform(get("/admin/painel-tv").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Depósito")))
            .andExpect(content().string(containsString("Nº ${separar.id} · Obra TV $s")))
            .andExpect(content().string(containsString("2 a separar")))
            .andExpect(content().string(containsString("Nº ${volta.id} · Obra TV $s")))
            .andExpect(content().string(containsString("Retirada às 15:30 · Motorista $s")))
            .andExpect(content().string(containsString("P-TV3-$s")))
            .andExpect(content().string(containsString("/admin/painel-tv")))
            // Sem o menu do sistema
            .andExpect(content().string(not(containsString("Agenda de entregas"))))

        // O miolo que a tela busca a cada minuto: só o conteúdo, com a marca que o script confere
        mvc.perform(get("/admin/painel-tv/conteudo").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("data-painel-tv")))
            .andExpect(content().string(containsString("Retirada às 15:30")))
            .andExpect(content().string(not(containsString("<html"))))

        // Quem não vê a locação não abre o painel; o menu mostra o link para quem vê
        mvc.perform(get("/admin/painel-tv").with(Perfis.somenteCatalogo())).andExpect(status().isForbidden)
        mvc.perform(get("/admin/painel-tv/conteudo").with(Perfis.somenteCatalogo())).andExpect(status().isForbidden)
        mvc.perform(get("/dashboard").with(Perfis.usuario())).andExpect(content().string(containsString("Painel para TV")))
    }
}
