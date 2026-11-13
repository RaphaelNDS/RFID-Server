package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.SituacaoBem
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.DadosCliente
import org.example.service.DadosPedido
import org.example.service.LocacaoService
import org.example.service.RelatorioLocacaoService
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
import java.time.LocalDate
import java.util.UUID

/** 1.25.0: sugestão de compra e venda. */
class Versao1250IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var locacao: LocacaoService
    @Autowired lateinit var relatorios: RelatorioLocacaoService
    @Autowired lateinit var tags: TagRepository

    private val s = UUID.randomUUID().toString().take(6).uppercase()

    @Test
    fun `falta e esgotado sugerem compra, sobra parada sugere venda, e por a venda muda so os disponiveis`() {
        // Totem: 2 equipamentos, um pedido de 3 que saiu com 2 e encerrou a separação (faltou 1, esgotou)
        val totem = catalogo.salvarTipo(TipoEquipamentoRequest("Totem $s")).id
        val marca = catalogo.salvarMarca(MarcaRequest("Marca $s", totem)).id
        val modelo = catalogo.salvarModelo(ModeloRequest("T$s", marca)).id
        listOf("TT1-$s", "TT2-$s").forEach {
            rfid.cadastrarTag(CadastroRequest(tag = it, codigoInterno = "TI-$it", patrimonio = "P-$it", tipoEquipamentoId = totem, marcaId = marca, modeloId = modelo))
        }
        val c = locacao.salvarCliente(null, DadosCliente("Evento $s"))
        val p = locacao.criarPedido(DadosPedido(c.id, LocalDate.now().plusDays(3)))
        locacao.adicionarLinha(p.id, totem, marca, modelo, 3, null)
        locacao.registrarSaida(p.id, listOf("TT1-$s", "TT2-$s"))
        locacao.encerrarSeparacao(p.id)

        // Monitor: 3 equipamentos que nunca saíram
        val monitor = catalogo.salvarTipo(TipoEquipamentoRequest("Monitor $s")).id
        listOf("MN1-$s", "MN2-$s", "MN3-$s").forEach {
            rfid.cadastrarTag(CadastroRequest(tag = it, codigoInterno = "TI-$it", patrimonio = "P-$it", tipoEquipamentoId = monitor))
        }

        val sug = relatorios.sugestoes(null, null)
        val compra = sug.compra.single { it.modelo == "Totem $s Marca $s T$s" }
        assertThat(compra.equipamentos).isEqualTo(2)
        assertThat(compra.pico).isEqualTo(2)
        assertThat(compra.faltas).isEqualTo(1)
        assertThat(compra.sugerido).isEqualTo(1)
        assertThat(compra.diasEsgotado).isGreaterThanOrEqualTo(1)
        assertThat(compra.motivo).contains("faltaram em 1 pedido").contains("esgotado")
        assertThat(sug.venda.map { it.modelo }).doesNotContain("Totem $s Marca $s T$s")

        val venda = sug.venda.single { it.modelo == "Monitor $s (sem marca)" }
        assertThat(venda.sobra).isEqualTo(3)
        assertThat(venda.candidatos.map { it.patrimonio }).containsExactlyInAnyOrder("P-MN1-$s", "P-MN2-$s", "P-MN3-$s")

        // Tela
        mvc.perform(get("/admin/compra-e-venda").with(Perfis.usuario()))
            .andExpect(content().string(containsString("Totem $s Marca $s T$s")))
            .andExpect(content().string(containsString("P-MN1-$s")))

        // Pôr à venda: só o Disponível muda (o alugado fica como está)
        val mn1 = tags.findByTagReal("MN1-$s").get().id
        val tt1 = tags.findByTagReal("TT1-$s").get().id
        mvc.perform(post("/admin/compra-e-venda/a-venda").with(Perfis.admin()).with(csrf()).param("tags", "$mn1", "$tt1"))
            .andExpect(flash().attribute("msg", containsString("1 equipamento(s) À venda")))
        assertThat(tags.findById(mn1).get().situacao).isEqualTo(SituacaoBem.A_VENDA)
        assertThat(tags.findById(tt1).get().situacao).isEqualTo(SituacaoBem.ALUGADO)
        // Só Ver não muda nada
        val mn2 = tags.findByTagReal("MN2-$s").get().id
        mvc.perform(post("/admin/compra-e-venda/a-venda").with(Perfis.usuario()).with(csrf()).param("tags", "$mn2"))
            .andExpect { assertThat(it.response.status).isIn(302, 403) }
        assertThat(tags.findById(mn2).get().situacao).isEqualTo(SituacaoBem.DISPONIVEL)
    }
}
