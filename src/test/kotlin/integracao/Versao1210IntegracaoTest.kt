package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.EstadoDevolucao
import org.example.model.ResultadoManutencao
import org.example.model.SituacaoBem
import org.example.model.StatusManutencao
import org.example.model.TipoManutencao
import org.example.repository.ManutencaoRepository
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.ConclusaoManutencao
import org.example.service.DadosCliente
import org.example.service.DadosPedido
import org.example.service.LocacaoService
import org.example.service.ManutencaoService
import org.example.service.PainelService
import org.example.service.ProblemaLeitura
import org.example.service.RevisaoService
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import java.time.LocalDate
import java.util.UUID

/** 1.21.0: revisão automática por uso. */
class Versao1210IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var locacao: LocacaoService
    @Autowired lateinit var manutencao: ManutencaoService
    @Autowired lateinit var manutencoes: ManutencaoRepository
    @Autowired lateinit var revisao: RevisaoService
    @Autowired lateinit var painel: PainelService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val hoje = LocalDate.now()

    private fun tipo(nome: String) = catalogo.salvarTipo(TipoEquipamentoRequest("$nome $s")).id

    private fun cadastrar(epc: String, tipo: Long) =
        rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-$epc", patrimonio = "P-$epc", tipoEquipamentoId = tipo))

    /** Uma locação inteira: pedido, saída, devolução e conferência OK. */
    private fun alugar(epc: String) {
        val c = locacao.salvarCliente(null, DadosCliente("Cliente $epc ${UUID.randomUUID().toString().take(4)}"))
        val p = locacao.criarPedido(DadosPedido(c.id, hoje.plusDays(2)))
        locacao.adicionarLinha(p.id, null, null, null, null, epc)
        assertThat(locacao.registrarSaida(p.id, listOf(epc)).aceitos).hasSize(1)
        locacao.registrarDevolucao(listOf(epc))
        val item = locacao.detalhar(p.id).itens.single()
        locacao.conferirDevolucao(item.id, EstadoDevolucao.OK, false, null)
    }

    private fun situacao(epc: String) = tags.findByTagReal(epc).get().situacao

    @Test
    fun `a cada 2 locacoes, a segunda devolucao vai para a preventiva e a revisao zera a contagem`() {
        val t = tipo("Coletor")
        val epc = "RV1-$s"
        cadastrar(epc, t)
        revisao.salvarRegra(t, 2, null)

        alugar(epc)
        assertThat(situacao(epc)).isEqualTo(SituacaoBem.DISPONIVEL)
        alugar(epc)
        assertThat(situacao(epc)).isEqualTo(SituacaoBem.EM_MANUTENCAO)
        val ordem = manutencoes.findFirstByTagIdAndStatus(tags.findByTagReal(epc).get().id, StatusManutencao.ABERTA)!!
        assertThat(ordem.tipo).isEqualTo(TipoManutencao.PREVENTIVA)
        assertThat(ordem.problema).startsWith("Revisão automática: 2 locação(ões) desde o cadastro")

        manutencao.concluir(ordem.id, ConclusaoManutencao(ResultadoManutencao.CONSERTADO, "Revisado", null))
        assertThat(situacao(epc)).isEqualTo(SituacaoBem.DISPONIVEL)
        assertThat(revisao.motivoVencida(tags.findByTagReal(epc).get())).isNull()
        // Conta de novo do zero: mais uma locação não vence
        alugar(epc)
        assertThat(situacao(epc)).isEqualTo(SituacaoBem.DISPONIVEL)
    }

    @Test
    fun `a cada 30 dias, o parado no estoque nao sai, aparece no painel e nao entra na folha`() {
        val t = tipo("Impressora")
        val velho = "RV2-$s"
        val novo = "RV3-$s"
        cadastrar(velho, t)
        cadastrar(novo, t)
        revisao.salvarRegra(t, null, 30)
        // O "velho" foi cadastrado há 40 dias
        jdbc.update("update historico_etiquetas set momento = momento - interval '40 days' where tag_real = ?", velho)

        assertThat(revisao.motivoVencida(tags.findByTagReal(velho).get())).startsWith("40 dias desde o cadastro")
        assertThat(revisao.motivoVencida(tags.findByTagReal(novo).get())).isNull()
        assertThat(painel.painel().revisoesVencidas.map { it.tag.tagReal }).contains(velho).doesNotContain(novo)

        val c = locacao.salvarCliente(null, DadosCliente("Feira $s"))
        val p = locacao.criarPedido(DadosPedido(c.id, hoje.plusDays(2)))
        locacao.adicionarLinha(p.id, t, null, null, 2, null)
        assertThat(locacao.separacao(p.id).linhas.single().candidatos.map { it.tag }).contains(novo).doesNotContain(velho)
        val r = locacao.registrarSaida(p.id, listOf(velho, novo))
        assertThat(r.aceitos.map { it.tag!!.tagReal }).containsExactly(novo)
        assertThat(r.recusados.single().problema).isEqualTo(ProblemaLeitura.REVISAO_VENCIDA)
    }

    @Test
    fun `regra valida os numeros, desliga com os dois vazios e a tela grava`() {
        val t = tipo("Tablet")
        assertThatThrownBy { revisao.salvarRegra(t, 0, null) }.hasMessageContaining("Locações")
        assertThatThrownBy { revisao.salvarRegra(t, null, 5000) }.hasMessageContaining("Dias")
        assertThat(revisao.salvarRegra(t, 5, 90)!!.descricao).isEqualTo("a cada 5 locação(ões) ou 90 dias")
        assertThat(revisao.salvarRegra(t, null, null)).isNull()
        assertThat(revisao.regrasDaTela().single { it.tipoId == t }.regra).isNull()

        mvc.perform(post("/admin/manutencoes/revisao/$t").with(Perfis.admin()).with(csrf()).param("locacoes", "3").param("dias", ""))
            .andExpect(flash().attribute("msg", containsString("a cada 3 locação(ões)")))
        mvc.perform(post("/admin/manutencoes/revisao/$t").with(Perfis.admin()).with(csrf()).param("locacoes", "abc"))
            .andExpect(flash().attribute("erroRegra", containsString("número inteiro")))
        mvc.perform(get("/admin/manutencoes").with(Perfis.admin()))
            .andExpect(content().string(containsString("Revisão automática")))
            .andExpect(content().string(containsString("a cada 3 locação(ões)")))
        // Sem Editar na Manutenção, não grava
        mvc.perform(post("/admin/manutencoes/revisao/$t").with(Perfis.usuario()).with(csrf()).param("locacoes", "9"))
            .andExpect { assertThat(it.response.status).isIn(302, 403) }
        assertThat(revisao.regrasDaTela().single { it.tipoId == t }.regra!!.aCadaLocacoes).isEqualTo(3)
    }
}
