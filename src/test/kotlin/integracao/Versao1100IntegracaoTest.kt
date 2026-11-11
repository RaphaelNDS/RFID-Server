package org.example.integracao

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.AreaAuditoria
import org.example.model.MotivoBaixa
import org.example.model.SituacaoBem
import org.example.repository.AuditoriaRepository
import org.example.repository.EmprestimoItemRepository
import org.example.repository.EmprestimoRepository
import org.example.repository.HistoricoEtiquetaRepository
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.service.EmprestimoService
import org.example.service.FiltroEmprestimo
import org.example.service.PatrimonioCadastrosService
import org.example.service.PedidoEmprestimo
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

/** 1.10.0: empréstimo e devolução de bens. */
class Versao1100IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var cadastros: PatrimonioCadastrosService
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var emprestimos: EmprestimoService
    @Autowired lateinit var emprestimoRepo: EmprestimoRepository
    @Autowired lateinit var itens: EmprestimoItemRepository
    @Autowired lateinit var historico: HistoricoEtiquetaRepository
    @Autowired lateinit var auditoria: AuditoriaRepository

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val hoje = LocalDate.now()

    private fun tag(epc: String) = tags.findByTagReal(epc).get()

    private fun cadastrar(epc: String, situacao: SituacaoBem = SituacaoBem.EM_USO): Long {
        rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-$epc", patrimonio = "P-$epc", situacao = situacao.name))
        return tag(epc).id
    }

    private fun pessoa(nome: String = "Rita $s") = cadastros.salvarResponsavel(null, nome, "TI", "M-$s", null)

    @Test
    fun `emprestar pela tela deixa os bens Emprestado e a devolucao parcial e depois total volta a situacao de antes`() {
        cadastrar("E-EM1-$s")
        cadastrar("E-EM2-$s", SituacaoBem.EM_ESTOQUE)
        val rita = pessoa()

        val resposta = mvc.perform(post("/admin/emprestimos").with(Perfis.gestor()).with(csrf())
            .param("codigos", "E-EM1-$s\nP-E-EM2-$s").param("responsavel", "${rita.id}")
            .param("previsao", hoje.plusDays(10).toString()).param("finalidade", "Evento na filial"))
            .andExpect(status().is3xxRedirection)
            .andExpect(flash().attribute("msg", containsString("registrado")))
            .andReturn().response.redirectedUrl!!
        val id = resposta.substringAfterLast('/').toLong()

        assertThat(tag("E-EM1-$s").situacao).isEqualTo(SituacaoBem.EMPRESTADO)
        assertThat(tag("E-EM2-$s").situacao).isEqualTo(SituacaoBem.EMPRESTADO)
        assertThat(emprestimoRepo.findById(id).get().responsavel).isEqualTo("Rita $s (TI, mat. M-$s)")
        assertThat(historico.findByTagRealOrderByMomentoDescIdDesc("E-EM1-$s").any { it.campo == "Situação" && it.origem.contains("Empréstimo nº $id") }).isTrue()
        assertThat(auditoria.findAll().any { it.area == AreaAuditoria.PATRIMONIO && it.acao == "Empréstimo de bens" && it.alvo == "Empréstimo nº $id" }).isTrue()

        // Tela do empréstimo e edição da etiqueta mostram o empréstimo; quem só vê não tem o formulário de devolução
        mvc.perform(get("/admin/emprestimos/$id").with(Perfis.gestor()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Termo de empréstimo de bens")))
            .andExpect(content().string(containsString("Registrar devolução")))
        mvc.perform(get("/admin/emprestimos/$id").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("Registrar devolução"))))
        mvc.perform(get("/admin/etiquetas/${tag("E-EM1-$s").id}/editar").with(Perfis.gestor()))
            .andExpect(content().string(containsString("Empréstimo nº $id")))
            .andExpect(content().string(not(containsString("Emprestar o bem"))))

        // Devolve só um
        val (i1, i2) = itens.findAllByEmprestimoIdOrderByIdAsc(id)
        mvc.perform(post("/admin/emprestimos/$id/devolucao").with(Perfis.gestor()).with(csrf())
            .param("itens", "${i1.id}").param("condicao", "Sem avarias"))
            .andExpect(flash().attribute("msg", containsString("Ainda há bens a devolver")))
        assertThat(tag("E-EM1-$s").situacao).isEqualTo(SituacaoBem.EM_USO)
        assertThat(tag("E-EM2-$s").situacao).isEqualTo(SituacaoBem.EMPRESTADO)
        assertThat(emprestimos.listar(FiltroEmprestimo.ABERTOS, "Rita $s", 0).content.single().pendentes).isEqualTo(1)

        // Devolve o outro: volta para "Em estoque", como estava
        mvc.perform(post("/admin/emprestimos/$id/devolucao").with(Perfis.gestor()).with(csrf()).param("itens", "${i2.id}"))
            .andExpect(flash().attribute("msg", containsString("Empréstimo encerrado")))
        assertThat(tag("E-EM2-$s").situacao).isEqualTo(SituacaoBem.EM_ESTOQUE)
        assertThat(itens.findById(i1.id).get().condicaoDevolucao).isEqualTo("Sem avarias")
        assertThat(emprestimos.listar(FiltroEmprestimo.ABERTOS, "Rita $s", 0).content).isEmpty()
        assertThat(emprestimos.listar(FiltroEmprestimo.DEVOLVIDOS, "E-EM2-$s", 0).content.single().emprestimo.id).isEqualTo(id)
        assertThat(historico.findByTagRealOrderByMomentoDescIdDesc("E-EM2-$s").any { it.origem.contains("Devolução do empréstimo nº $id") }).isTrue()
    }

    @Test
    fun `so sai bem Em uso ou Em estoque, e o mesmo bem nao entra em dois emprestimos`() {
        cadastrar("E-EM3-$s")
        cadastrar("E-EM4-$s", SituacaoBem.EM_MANUTENCAO)
        val rita = pessoa()
        val pedido = { codigo: String -> PedidoEmprestimo(listOf(codigo), rita.id, hoje.plusDays(5)) }

        assertThatThrownBy { emprestimos.emprestar(pedido("E-EM4-$s")) }.hasMessageContaining("Em manutenção")
        emprestimos.emprestar(pedido("E-EM3-$s"))
        assertThatThrownBy { emprestimos.emprestar(pedido("E-EM3-$s")) }.hasMessageContaining("Emprestado")
        assertThatThrownBy { emprestimos.emprestar(pedido("NAO-EXISTE-$s")) }.hasMessageContaining("Não encontrado")
        assertThatThrownBy { emprestimos.emprestar(PedidoEmprestimo(listOf("E-EM4-$s"), null, hoje)) }.hasMessageContaining("responsável")
        assertThatThrownBy { emprestimos.emprestar(PedidoEmprestimo(listOf("E-EM4-$s"), rita.id, null)) }.hasMessageContaining("previsão")
        assertThatThrownBy { emprestimos.emprestar(PedidoEmprestimo(listOf("E-EM4-$s"), rita.id, hoje.minusDays(1))) }
            .hasMessageContaining("antes da saída")
    }

    @Test
    fun `bem emprestado nao recebe baixa, nao e excluido e a situacao nao muda pela edicao`() {
        val id = cadastrar("E-EM5-$s")
        val e = emprestimos.emprestar(PedidoEmprestimo(listOf("E-EM5-$s"), pessoa().id, hoje.plusDays(3)))

        assertThatThrownBy { rfid.darBaixa(id, MotivoBaixa.VENDA, null, null) }.hasMessageContaining("empréstimo nº ${e.id}")
        mvc.perform(post("/admin/etiquetas/$id/excluir").with(Perfis.admin()).with(csrf()))
            .andExpect(flash().attribute("erro", containsString("registre a devolução antes de excluir")))
        assertThat(tags.findById(id)).isPresent
        mvc.perform(post("/admin/etiquetas/$id/editar").with(Perfis.admin()).with(csrf())
            .param("codigoInterno", "TI-E-EM5-$s").param("patrimonio", "P-E-EM5-$s").param("situacao", "EM_USO"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("a situação volta sozinha")))
        // Salvar outros campos com a situação "Emprestado" continua possível
        mvc.perform(post("/admin/etiquetas/$id/editar").with(Perfis.admin()).with(csrf())
            .param("codigoInterno", "TI-NOVO-$s").param("patrimonio", "P-E-EM5-$s").param("situacao", "EMPRESTADO"))
            .andExpect(status().is3xxRedirection)
        assertThat(tag("E-EM5-$s").codigoInterno).isEqualTo("TI-NOVO-$s")
    }

    @Test
    fun `atrasado aparece no filtro e na contagem, e o termo sai em PDF`() {
        cadastrar("E-EM6-$s")
        val e = emprestimos.emprestar(PedidoEmprestimo(listOf("E-EM6-$s"), pessoa("Otávio $s").id, hoje.minusDays(2), dataSaida = hoje.minusDays(9)))

        val atrasado = emprestimos.listar(FiltroEmprestimo.ATRASADOS, "Otávio $s", 0).content.single()
        assertThat(atrasado.diasAtraso).isEqualTo(2)
        assertThat(emprestimos.contarAtrasados()).isGreaterThanOrEqualTo(1)
        mvc.perform(get("/admin/emprestimos").param("situacao", "ATRASADOS").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Atrasado 2 dia(s)")))

        val pdf = mvc.perform(get("/admin/emprestimos/${e.id}/termo.pdf").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andReturn().response.contentAsByteArray
        val texto = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }.replace(Regex("\r?\n"), " ")
        assertThat(texto).contains("Termo de empréstimo de bens").contains("Otávio $s").contains("P-E-EM6-$s")
    }

    @Test
    fun `quem so ve nao empresta nem devolve`() {
        cadastrar("E-EM7-$s")
        val e = emprestimos.emprestar(PedidoEmprestimo(listOf("E-EM7-$s"), pessoa().id, hoje.plusDays(1)))
        mvc.perform(get("/admin/emprestimos/novo").with(Perfis.usuario())).andExpect(status().isForbidden)
        mvc.perform(post("/admin/emprestimos").with(Perfis.usuario()).with(csrf()).param("codigos", "E-EM7-$s"))
            .andExpect(status().isForbidden)
        mvc.perform(post("/admin/emprestimos/${e.id}/devolucao").with(Perfis.usuario()).with(csrf())
            .param("itens", "${itens.findAllByEmprestimoIdOrderByIdAsc(e.id).single().id}"))
            .andExpect(status().isForbidden)
        assertThat(tag("E-EM7-$s").situacao).isEqualTo(SituacaoBem.EMPRESTADO)
    }
}
