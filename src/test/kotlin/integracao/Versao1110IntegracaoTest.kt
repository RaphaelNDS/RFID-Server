package org.example.integracao

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.AreaAuditoria
import org.example.model.MotivoBaixa
import org.example.model.ResultadoManutencao
import org.example.model.SituacaoBem
import org.example.model.StatusManutencao
import org.example.model.TipoManutencao
import org.example.repository.AuditoriaRepository
import org.example.repository.HistoricoEtiquetaRepository
import org.example.repository.ManutencaoRepository
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.service.ConclusaoManutencao
import org.example.service.EmprestimoService
import org.example.service.FiltroManutencao
import org.example.service.ManutencaoService
import org.example.service.PatrimonioCadastrosService
import org.example.service.PedidoEmprestimo
import org.example.service.PedidoManutencao
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

/** 1.11.0: manutenção do bem. */
class Versao1110IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var manutencoes: ManutencaoService
    @Autowired lateinit var repo: ManutencaoRepository
    @Autowired lateinit var emprestimos: EmprestimoService
    @Autowired lateinit var cadastros: PatrimonioCadastrosService
    @Autowired lateinit var historico: HistoricoEtiquetaRepository
    @Autowired lateinit var auditoria: AuditoriaRepository

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val hoje = LocalDate.now()

    private fun tag(epc: String) = tags.findByTagReal(epc).get()

    private fun cadastrar(epc: String, situacao: SituacaoBem = SituacaoBem.EM_USO): Long {
        rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-$epc", patrimonio = "P-$epc", situacao = situacao.name))
        return tag(epc).id
    }

    private fun abrir(epc: String, previsao: LocalDate? = null, envio: LocalDate? = null) =
        manutencoes.abrir(PedidoManutencao(epc, TipoManutencao.CORRETIVA, "Não liga", "Assistência $s", envio, previsao))

    @Test
    fun `abrir pela tela deixa o bem Em manutencao e concluir com custo volta a situacao de antes`() {
        val tagId = cadastrar("E-MN1-$s", SituacaoBem.EM_ESTOQUE)

        val url = mvc.perform(post("/admin/manutencoes").with(Perfis.gestor()).with(csrf())
            .param("codigo", "P-E-MN1-$s").param("tipo", "CORRETIVA").param("problema", "Tela piscando")
            .param("fornecedor", "Assistência $s").param("previsao", hoje.plusDays(7).toString()))
            .andExpect(status().is3xxRedirection)
            .andExpect(flash().attribute("msg", containsString("aberta")))
            .andReturn().response.redirectedUrl!!
        val id = url.substringAfterLast('/').toLong()
        assertThat(tag("E-MN1-$s").situacao).isEqualTo(SituacaoBem.EM_MANUTENCAO)
        assertThat(repo.findById(id).get().situacaoAnterior).isEqualTo(SituacaoBem.EM_ESTOQUE)

        mvc.perform(get("/admin/etiquetas/$tagId/editar").with(Perfis.gestor()))
            .andExpect(content().string(containsString("Manutenção nº $id")))
            .andExpect(content().string(not(containsString("Mandar o bem para conserto"))))
        mvc.perform(get("/admin/manutencoes/$id").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Tela piscando")))
            .andExpect(content().string(not(containsString("Concluir manutenção"))))

        mvc.perform(post("/admin/manutencoes/$id/conclusao").with(Perfis.gestor()).with(csrf())
            .param("resultado", "CONSERTADO").param("servico", "Troca do cabo flat").param("custo", "1.250,50"))
            .andExpect(flash().attribute("msg", containsString("voltou a ficar Em estoque")))

        val ordem = repo.findById(id).get()
        assertThat(ordem.status).isEqualTo(StatusManutencao.CONCLUIDA)
        assertThat(ordem.custo).isEqualByComparingTo("1250.50")
        assertThat(ordem.concluidaPor).isEqualTo("gestor@x.com")
        assertThat(tag("E-MN1-$s").situacao).isEqualTo(SituacaoBem.EM_ESTOQUE)
        assertThat(historico.findByTagRealOrderByMomentoDescIdDesc("E-MN1-$s").map { it.origem })
            .anyMatch { it.contains("Manutenção nº $id concluída") }.anyMatch { it.endsWith("Manutenção nº $id") }
        assertThat(auditoria.findAll().any { it.area == AreaAuditoria.PATRIMONIO && it.acao == "Manutenção concluída" && it.alvo == "Manutenção nº $id" }).isTrue()

        // Histórico na edição da etiqueta, com o custo somado
        mvc.perform(get("/admin/etiquetas/$tagId/editar").with(Perfis.gestor()))
            .andExpect(content().string(containsString("custo total R\$ 1.250,50")))
        assertThat(manutencoes.listar(FiltroManutencao.ENCERRADAS, "E-MN1-$s", 0).content.single().manutencao.id).isEqualTo(id)
        assertThat(manutencoes.listar(FiltroManutencao.ABERTAS, "E-MN1-$s", 0).content).isEmpty()
    }

    @Test
    fun `cancelar devolve a situacao, e so vai bem Em uso ou Em estoque`() {
        cadastrar("E-MN2-$s")
        cadastrar("E-MN3-$s")
        val ordem = abrir("E-MN2-$s")

        assertThatThrownBy { abrir("E-MN2-$s") }.hasMessageContaining("Em manutenção")
        // Bem emprestado não vai para a manutenção, e bem em manutenção não é emprestado
        val rita = cadastros.salvarResponsavel(null, "Rita $s", null, null, null)
        emprestimos.emprestar(PedidoEmprestimo(listOf("E-MN3-$s"), rita.id, hoje.plusDays(2)))
        assertThatThrownBy { abrir("E-MN3-$s") }.hasMessageContaining("Emprestado")
        assertThatThrownBy { emprestimos.emprestar(PedidoEmprestimo(listOf("E-MN2-$s"), rita.id, hoje.plusDays(2))) }
            .hasMessageContaining("Em manutenção")

        assertThatThrownBy { manutencoes.cancelar(ordem.id, " ") }.hasMessageContaining("motivo")
        manutencoes.cancelar(ordem.id, "Aberta por engano")
        assertThat(tag("E-MN2-$s").situacao).isEqualTo(SituacaoBem.EM_USO)
        assertThat(repo.findById(ordem.id).get().status).isEqualTo(StatusManutencao.CANCELADA)
        assertThatThrownBy { manutencoes.concluir(ordem.id, ConclusaoManutencao(ResultadoManutencao.CONSERTADO, null, null)) }
            .hasMessageContaining("já está cancelada")
    }

    @Test
    fun `bem em manutencao nao recebe baixa, nao e excluido e a situacao nao muda pela edicao`() {
        val id = cadastrar("E-MN4-$s")
        val ordem = abrir("E-MN4-$s")

        assertThatThrownBy { rfid.darBaixa(id, MotivoBaixa.SUCATA, null, null) }
            .hasMessageContaining("na manutenção nº ${ordem.id}: conclua a manutenção antes da baixa")
        mvc.perform(post("/admin/etiquetas/$id/excluir").with(Perfis.admin()).with(csrf()))
            .andExpect(flash().attribute("erro", containsString("conclua a manutenção antes de excluir")))
        mvc.perform(post("/admin/etiquetas/$id/editar").with(Perfis.admin()).with(csrf())
            .param("codigoInterno", "TI-E-MN4-$s").param("patrimonio", "P-E-MN4-$s").param("situacao", "EM_USO"))
            .andExpect(content().string(containsString("a situação volta sozinha quando a manutenção for concluída")))

        // Sem conserto também volta a situação; a baixa (sucata) passa a ser possível depois
        manutencoes.concluir(ordem.id, ConclusaoManutencao(ResultadoManutencao.SEM_CONSERTO, "Placa queimada", "0"))
        rfid.darBaixa(id, MotivoBaixa.SUCATA, null, null)
        assertThat(tag("E-MN4-$s").situacao).isEqualTo(SituacaoBem.BAIXADO)
    }

    @Test
    fun `atrasada aparece no filtro, datas e custo sao conferidos, e a ordem sai em PDF`() {
        cadastrar("E-MN5-$s")
        cadastrar("E-MN6-$s")
        assertThatThrownBy { abrir("E-MN5-$s", previsao = hoje.minusDays(1)) }.hasMessageContaining("antes do envio")
        assertThatThrownBy { abrir("E-MN5-$s", envio = hoje.plusDays(1)) }.hasMessageContaining("futuro")
        assertThatThrownBy { manutencoes.abrir(PedidoManutencao("E-MN5-$s", TipoManutencao.PREVENTIVA, " ")) }
            .hasMessageContaining("o que será revisado")

        val ordem = abrir("E-MN5-$s", previsao = hoje.minusDays(3), envio = hoje.minusDays(10))
        assertThat(manutencoes.listar(FiltroManutencao.ATRASADAS, "E-MN5-$s", 0).content.single().diasAtraso).isEqualTo(3)
        assertThat(manutencoes.contarAtrasadas()).isGreaterThanOrEqualTo(1)
        mvc.perform(get("/admin/manutencoes").param("situacao", "ATRASADAS").param("busca", "Assistência $s").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Atrasada 3 dia(s)")))

        assertThatThrownBy { manutencoes.concluir(ordem.id, ConclusaoManutencao(ResultadoManutencao.CONSERTADO, null, "abc")) }
            .hasMessageContaining("Valor inválido")
        assertThatThrownBy { manutencoes.concluir(ordem.id, ConclusaoManutencao(ResultadoManutencao.CONSERTADO, null, null, hoje.minusDays(11))) }
            .hasMessageContaining("antes do envio")

        val pdf = mvc.perform(get("/admin/manutencoes/${ordem.id}/ordem.pdf").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andReturn().response.contentAsByteArray
        val texto = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }.replace(Regex("\r?\n"), " ")
        assertThat(texto).contains("Ordem de manutenção").contains("P-E-MN5-$s").contains("Não liga")
    }

    @Test
    fun `quem so ve nao abre, nao conclui e nao cancela`() {
        cadastrar("E-MN7-$s")
        val ordem = abrir("E-MN7-$s")
        mvc.perform(get("/admin/manutencoes/nova").with(Perfis.usuario())).andExpect(status().isForbidden)
        mvc.perform(post("/admin/manutencoes").with(Perfis.usuario()).with(csrf()).param("codigo", "E-MN7-$s"))
            .andExpect(status().isForbidden)
        mvc.perform(post("/admin/manutencoes/${ordem.id}/conclusao").with(Perfis.usuario()).with(csrf()).param("resultado", "CONSERTADO"))
            .andExpect(status().isForbidden)
        mvc.perform(post("/admin/manutencoes/${ordem.id}/cancelamento").with(Perfis.usuario()).with(csrf()).param("motivo", "x"))
            .andExpect(status().isForbidden)
        assertThat(tag("E-MN7-$s").situacao).isEqualTo(SituacaoBem.EM_MANUTENCAO)
    }
}
