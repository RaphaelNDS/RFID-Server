package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.SituacaoBem
import org.example.model.TipoManutencao
import org.example.repository.PedidoItemRepository
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.DadosCliente
import org.example.service.DadosPedido
import org.example.service.LocacaoService
import org.example.service.ManutencaoService
import org.example.service.PainelService
import org.example.service.PedidoManutencao
import org.example.service.RfidService
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

/** 1.13.0: painel do dia (tela inicial) e disponibilidade por tipo e modelo. */
class Versao1130IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var painel: PainelService
    @Autowired lateinit var locacao: LocacaoService
    @Autowired lateinit var manutencoes: ManutencaoService
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var itens: PedidoItemRepository

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val hoje = LocalDate.now()

    private fun cadastrar(epc: String, tipo: Long, marca: Long? = null, modelo: Long? = null) =
        rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-$epc", patrimonio = "P-$epc", tipoEquipamentoId = tipo, marcaId = marca, modeloId = modelo))

    /**
     * Tipo com 2 modelos: 3 do MC3300 e 2 do TC21. Um pedido levou 2 MC3300 (volta em 3 dias) e outro está a separar
     * 2 do tipo; um TC21 está em manutenção atrasada.
     */
    private fun cenario(): Triple<Long, Long, Long> {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Coletor $s")).id
        val zebra = catalogo.salvarMarca(MarcaRequest("Zebra $s", tipo)).id
        val mc = catalogo.salvarModelo(ModeloRequest("MC3300", zebra)).id
        val tc = catalogo.salvarModelo(ModeloRequest("TC21", zebra)).id
        (1..3).forEach { cadastrar("M$it-$s", tipo, zebra, mc) }
        (1..2).forEach { cadastrar("T$it-$s", tipo, zebra, tc) }
        val cliente = locacao.salvarCliente(null, DadosCliente("Obra $s"))

        val alugado = locacao.criarPedido(DadosPedido(cliente.id, hoje.plusDays(3), hoje))
        locacao.adicionarLinha(alugado.id, tipo, zebra, mc, 2, null)
        locacao.registrarSaida(alugado.id, listOf("M1-$s", "M2-$s"))

        val aSeparar = locacao.criarPedido(DadosPedido(cliente.id, hoje.plusDays(9), hoje.plusDays(1)))
        locacao.adicionarLinha(aSeparar.id, tipo, null, null, 2, null)

        manutencoes.abrir(PedidoManutencao("T1-$s", TipoManutencao.CORRETIVA, "Tela", "Assist $s", hoje.minusDays(5), hoje.minusDays(1)))
        return Triple(tipo, alugado.id, aSeparar.id)
    }

    @Test
    fun `disponibilidade por tipo e modelo, com o que volta ate a data e o que esta comprometido`() {
        val (tipo, _, _) = cenario()

        val hojeD = painel.disponibilidade(null, tipo)
        val g = hojeD.grupos.single()
        assertThat(g.tipo).isEqualTo("Coletor $s")
        assertThat(g.total).isEqualTo(5)
        assertThat(g.disponiveis).isEqualTo(2)      // M3 e T2
        assertThat(g.alugados).isEqualTo(2)
        assertThat(g.emManutencao).isEqualTo(1)
        assertThat(g.aSeparar).isEqualTo(2)
        assertThat(g.livres).isEqualTo(0)
        assertThat(g.voltamAte).isEqualTo(0)        // voltam em 3 dias
        assertThat(g.linhas.map { it.modelo }).containsExactly("MC3300", "TC21")
        assertThat(g.linhas.first { it.modelo == "MC3300" }.alugados).isEqualTo(2)

        assertThat(painel.disponibilidade(hoje.plusDays(3), tipo).grupos.single().voltamAte).isEqualTo(2)

        mvc.perform(get("/admin/disponibilidade").param("tipo", "$tipo").param("ate", hoje.plusDays(3).toString()).with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Coletor $s")))
            .andExpect(content().string(containsString("Zebra $s MC3300")))
            .andExpect(content().string(containsString("2 voltam até")))
        mvc.perform(get("/admin/disponibilidade").with(Perfis.somenteCatalogo())).andExpect(status().isForbidden)
    }

    @Test
    fun `painel do dia mostra o que separar, as devolucoes atrasadas, o que conferir e as manutencoes atrasadas`() {
        val (_, alugado, aSeparar) = cenario()
        // O pedido alugado está atrasado? Ainda não: volta em 3 dias. Um terceiro pedido, saído há 10 dias, venceu ontem
        val cliente = locacao.salvarCliente(null, DadosCliente("Atrasado $s"))
        val vencido = locacao.criarPedido(DadosPedido(cliente.id, hoje.plusDays(1), hoje))
        locacao.adicionarLinha(vencido.id, null, null, null, null, "M3-$s")
        locacao.registrarSaida(vencido.id, listOf("M3-$s"))
        locacao.editarPedido(vencido.id, DadosPedido(cliente.id, hoje.minusDays(1)))
        // Um devolvido esperando a conferência
        locacao.registrarDevolucao(listOf("M1-$s"))

        val p = painel.painel()
        assertThat(p.saidas.map { it.resumo.pedido.id }).contains(aSeparar).doesNotContain(alugado)
        assertThat(p.saidas.first { it.resumo.pedido.id == aSeparar }.faltam).isEqualTo(2)
        assertThat(p.atrasados.map { it.pedido.id }).contains(vencido.id).doesNotContain(alugado)
        assertThat(p.aConferir).isGreaterThanOrEqualTo(1)
        assertThat(p.manutencoesAtrasadas.map { it.tagReal }).contains("T1-$s")
        assertThat(p.quantos(SituacaoBem.ALUGADO)).isGreaterThanOrEqualTo(2)

        mvc.perform(get("/dashboard").with(Perfis.gestor()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Separar e entregar")))
            .andExpect(content().string(containsString("Nº $aSeparar · Obra $s")))
            .andExpect(content().string(containsString("atrasado 1 dia(s)")))
            .andExpect(content().string(containsString("Manutenções atrasadas")))
        // Sem RFID · Ver, a tela inicial não mostra o painel
        mvc.perform(get("/dashboard").with(Perfis.somenteCatalogo()))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("Separar e entregar"))))
    }
}
