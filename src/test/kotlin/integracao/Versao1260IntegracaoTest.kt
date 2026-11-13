package org.example.integracao

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.TipoManutencao
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.GarantiaService
import org.example.service.HistoricoEtiquetaService
import org.example.service.ManutencaoService
import org.example.service.PedidoManutencao
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import java.time.LocalDate
import java.util.UUID

/** 1.26.0: garantia do fabricante. */
class Versao1260IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var garantias: GarantiaService
    @Autowired lateinit var manutencao: ManutencaoService
    @Autowired lateinit var historico: HistoricoEtiquetaService
    @Autowired lateinit var tags: TagRepository

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val hoje = LocalDate.now()

    @Test
    fun `garantia na etiqueta, aviso e marca na manutencao, vencendo e historico`() {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Impressora $s")).id
        listOf("GA1-$s", "GA2-$s").forEach {
            rfid.cadastrarTag(CadastroRequest(tag = it, codigoInterno = "TI-$it", patrimonio = "P-$it", tipoEquipamentoId = tipo))
        }
        val naGarantia = tags.findByTagReal("GA1-$s").get().id
        val vencida = tags.findByTagReal("GA2-$s").get().id

        assertThatThrownBy { garantias.definir(naGarantia, null, "Zebra") }.hasMessageContaining("até quando")
        // Pela tela
        mvc.perform(post("/admin/etiquetas/$naGarantia/garantia").with(Perfis.admin()).with(csrf())
            .param("garantiaAte", hoje.plusDays(10).toString()).param("garantiaFornecedor", "Zebra Brasil"))
            .andExpect(flash().attribute("msg", containsString("Garantia até")))
        garantias.definir(vencida, hoje.minusDays(1), "Elgin")

        mvc.perform(get("/admin/etiquetas/$naGarantia/editar").with(Perfis.admin()))
            .andExpect(content().string(containsString("Na garantia")))
            .andExpect(content().string(containsString("Zebra Brasil")))
        assertThat(historico.historico("GA1-$s").map { it.campo }).contains("Garantia até", "Garantia com")

        // Manutenção nova mostra o aviso só para quem está na garantia
        mvc.perform(get("/admin/manutencoes/nova").param("tag", "GA1-$s").with(Perfis.admin()))
            .andExpect(content().string(containsString("acione o fabricante")))
        mvc.perform(get("/admin/manutencoes/nova").param("tag", "GA2-$s").with(Perfis.admin()))
            .andExpect(content().string(not(containsString("acione o fabricante"))))

        val ordem = manutencao.abrir(PedidoManutencao("GA1-$s", TipoManutencao.CORRETIVA, "Não liga"))
        assertThat(ordem.emGarantia).isTrue()
        assertThat(ordem.garantiaFornecedor).isEqualTo("Zebra Brasil")
        val outra = manutencao.abrir(PedidoManutencao("GA2-$s", TipoManutencao.CORRETIVA, "Não liga"))
        assertThat(outra.emGarantia).isFalse()

        mvc.perform(get("/admin/manutencoes/${ordem.id}").with(Perfis.admin()))
            .andExpect(content().string(containsString("acionar o fabricante")))
        val pdf = mvc.perform(get("/admin/manutencoes/${ordem.id}/ordem.pdf").with(Perfis.admin())).andReturn().response.contentAsByteArray
        assertThat(PDFTextStripper().getText(PDDocument.load(pdf))).contains("NA GARANTIA")

        // Vencendo nos próximos 30 dias: só a que ainda não venceu
        assertThat(garantias.vencendo(30).map { it.id }).contains(naGarantia).doesNotContain(vencida)
        mvc.perform(get("/admin/manutencoes").with(Perfis.admin()))
            .andExpect(content().string(containsString("Garantias vencendo")))

        // Apagar a garantia
        garantias.definir(naGarantia, null, null)
        assertThat(tags.findById(naGarantia).get().garantiaFornecedor).isNull()
    }
}
