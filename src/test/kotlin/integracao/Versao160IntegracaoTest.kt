package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.MotivoBaixa
import org.example.model.SituacaoBem
import org.example.repository.HistoricoEtiquetaRepository
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.service.FiltroEtiquetas
import org.example.service.ImportacaoEtiquetasService
import org.example.service.PatrimonioCadastrosService
import org.example.service.RelatorioEtiquetasService
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.example.suporte.comoOperador
import org.example.util.FormatoPlanilha
import org.example.util.GeradorPlanilha
import org.example.util.Planilha
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** 1.6.0: gestão de patrimônio (locais, responsáveis, situação, baixa, termo e importação de planilha). */
class Versao160IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var cadastros: PatrimonioCadastrosService
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var historico: HistoricoEtiquetaRepository
    @Autowired lateinit var relatorio: RelatorioEtiquetasService
    @Autowired lateinit var importacao: ImportacaoEtiquetasService
    @Autowired lateinit var operadores: OperadorTeste

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val token by lazy { operadores.sessaoPadrao(mvc, "v160-$s@x.com".lowercase()) }

    private fun tag(epc: String) = tags.findByTagReal(epc).get()

    private fun cadastrar(epc: String, localId: Long? = null, situacao: String? = null) =
        rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-$epc", patrimonio = "P-$epc",
            localId = localId, situacao = situacao))

    private fun coletor(url: String, json: String) =
        mvc.perform(post(url).comoOperador(token).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk)

    private fun inventario(local: String, vararg epcs: String): String {
        val id = UUID.randomUUID().toString()
        coletor("/api/android/inventarios", """{"id":"$id","local":"$local"}""")
        coletor("/api/android/inventarios/$id/itens", """{"itens":[${epcs.joinToString { """{"tag":"$it"}""" }}]}""")
        coletor("/api/android/inventarios/$id/finalizar", "{}")
        return id
    }

    // ---------- Cadastros ----------

    @Test
    fun `unidades e locais com nomes unicos e desativacao`() {
        val matriz = cadastros.salvarUnidade(null, "Matriz $s")
        assertThatThrownBy { cadastros.salvarUnidade(null, " matriz  $s ") }.hasMessageContaining("Já existe a unidade")
        val sala = cadastros.salvarLocal(null, "Sala  101 $s", matriz.id)
        assertThat(sala.nome).isEqualTo("Sala 101 $s")
        assertThatThrownBy { cadastros.salvarLocal(null, "sala 101 $s", null) }.hasMessageContaining("Já existe o local")

        cadastros.salvarLocal(sala.id, sala.nome, matriz.id, ativo = false)
        assertThat(cadastros.opcoesDeLocal().map { it.id }).doesNotContain(sala.id)
        assertThat(cadastros.opcoesDeLocal(incluirInativos = true).first { it.id == sala.id }.descricao).isEqualTo("Matriz $s › Sala 101 $s")
        // Local desativado não pode ser escolhido num bem novo
        assertThatThrownBy { cadastrar("E-DESAT-$s", localId = sala.id) }.hasMessageContaining("desativado")
    }

    // ---------- Etiqueta: local, responsável, situação, histórico e ficha ----------

    @Test
    fun `etiqueta com local e situacao no historico e na ficha do coletor`() {
        val unidade = cadastros.salvarUnidade(null, "Filial $s")
        val local = cadastros.salvarLocal(null, "Almox $s", unidade.id)
        val outro = cadastros.salvarLocal(null, "Sala 2 $s", unidade.id)
        cadastrar("E-BEM-$s", localId = local.id, situacao = "EM_ESTOQUE")
        val t = tag("E-BEM-$s")
        assertThat(t.situacao).isEqualTo(SituacaoBem.DISPONIVEL)

        rfid.editarTag(t.id, CadastroRequest(codigoInterno = t.codigoInterno, patrimonio = t.patrimonio, localId = outro.id,
            situacao = "EXTRAVIADO"))
        val linhas = historico.findByTagRealOrderByMomentoDescIdDesc("E-BEM-$s").filter { it.campo != null }.associateBy { it.campo }
        assertThat(linhas.getValue("Local").valorAntigo).isEqualTo("Almox $s")
        assertThat(linhas.getValue("Local").valorNovo).isEqualTo("Sala 2 $s")
        assertThat(linhas.getValue("Situação").valorNovo).isEqualTo("Extraviado")
        assertThatThrownBy { rfid.editarTag(t.id, CadastroRequest(codigoInterno = "X", situacao = "BAIXADO")) }.hasMessageContaining("Dar baixa")
        assertThat(tag("E-BEM-$s").codigoInterno).isEqualTo("TI-E-BEM-$s")   // a recusa não deixou nada alterado

        mvc.perform(get("/api/android/tags/E-BEM-$s").comoOperador(token))
            .andExpect(jsonPath("$.situacao").value("EXTRAVIADO"))
            .andExpect(jsonPath("$.situacaoRotulo").value("Extraviado"))
            .andExpect(jsonPath("$.local").value("Sala 2 $s"))
            .andExpect(jsonPath("$.unidade").value("Filial $s"))
            .andExpect(jsonPath("$.responsavel").doesNotExist())
    }

    // ---------- Baixa ----------

    @Test
    fun `baixa tira o bem das listas do esperado e das nao vistas e pode ser desfeita`() {
        val local = cadastros.salvarLocal(null, "Depósito $s", null)
        cadastrar("E-BX-$s", localId = local.id)
        val id = tag("E-BX-$s").id

        mvc.perform(post("/admin/etiquetas/$id/baixa").with(Perfis.gestor()).with(csrf())
            .param("motivo", "DOACAO").param("observacao", "Doado à escola $s"))
            .andExpect(status().is3xxRedirection)
        val t = tag("E-BX-$s")
        assertThat(t.situacao).isEqualTo(SituacaoBem.BAIXADO)
        assertThat(t.baixaMotivo).isEqualTo(MotivoBaixa.DOACAO)
        assertThat(historico.findByTagRealOrderByMomentoDescIdDesc("E-BX-$s").any { it.campo == "Baixa" && it.valorNovo!!.contains("Doação") }).isTrue()

        assertThat(rfid.listarTodasParaAdmin("E-BX-$s").map { it.tag }).isEmpty()
        assertThat(rfid.listarTodasParaAdmin("E-BX-$s", FiltroEtiquetas(incluirBaixados = true)).map { it.tag }).containsExactly("E-BX-$s")
        assertThat(rfid.listarTodasParaAdmin(null, FiltroEtiquetas(situacao = SituacaoBem.BAIXADO)).map { it.tag }).contains("E-BX-$s")
        assertThat(relatorio.naoVistasTodas(1).map { it.etiqueta.tag }).doesNotContain("E-BX-$s")
        mvc.perform(get("/api/android/inventarios/esperado").param("local", local.nome).comoOperador(token))
            .andExpect(jsonPath("$.itens.length()").value(0))
        assertThatThrownBy { rfid.darBaixa(id, MotivoBaixa.VENDA, null, null) }.hasMessageContaining("já foi baixado")

        mvc.perform(post("/admin/etiquetas/$id/baixa/desfazer").with(Perfis.gestor()).with(csrf())).andExpect(status().is3xxRedirection)
        assertThat(tag("E-BX-$s").situacao).isEqualTo(SituacaoBem.DISPONIVEL)
        assertThat(tag("E-BX-$s").baixaMotivo).isNull()
        // Quem só vê não dá baixa
        mvc.perform(post("/admin/etiquetas/$id/baixa").with(Perfis.usuario()).with(csrf()).param("motivo", "VENDA")).andExpect(status().isForbidden)
    }

    // ---------- Inventário: local do bem e esperado pelo cadastro ----------

    @Test
    fun `inventario finalizado poe o bem no local e o extraviado achado volta em uso`() {
        val a = cadastros.salvarLocal(null, "Sala A $s", null)
        cadastrar("E-INV1-$s", localId = a.id, situacao = "EXTRAVIADO")
        cadastrar("E-INV2-$s")
        cadastrar("E-INV3-$s")
        rfid.darBaixa(tag("E-INV3-$s").id, MotivoBaixa.SUCATA, null, null)

        inventario("Sala B $s", "E-INV1-$s", "E-INV2-$s", "E-INV3-$s")
        val salaB = cadastros.localPorNome("Sala B $s", null, criar = false)!!
        assertThat(tag("E-INV1-$s").localId).isEqualTo(salaB.id)
        assertThat(tag("E-INV1-$s").situacao).isEqualTo(SituacaoBem.DISPONIVEL)
        assertThat(tag("E-INV2-$s").localId).isEqualTo(salaB.id)
        assertThat(tag("E-INV3-$s").localId).isNull()   // baixado não muda
        assertThat(historico.findByTagRealOrderByMomentoDescIdDesc("E-INV1-$s").first { it.campo == "Local" }.origem).startsWith("Coletor")
    }

    @Test
    fun `local sem inventario mas com bens cadastrados ja tem o esperado`() {
        val local = cadastros.salvarLocal(null, "Novo $s", null)
        cadastrar("E-ESP1-$s", localId = local.id)
        cadastrar("E-ESP2-$s", localId = local.id)

        mvc.perform(get("/api/android/inventarios/esperado").param("local", local.nome).comoOperador(token))
            .andExpect(jsonPath("$.temReferencia").value(true))
            .andExpect(jsonPath("$.inventarioReferencia").doesNotExist())
            .andExpect(jsonPath("$.itens.length()").value(2))
    }

    // ---------- Telas ----------

    @Test
    fun `telas de locais e etiquetas com filtros (responsaveis e termo sairam na 1_18_1)`() {
        val unidade = cadastros.salvarUnidade(null, "Unidade $s")
        val local = cadastros.salvarLocal(null, "Sala T $s", unidade.id)
        cadastrar("E-TELA-$s", localId = local.id)

        mvc.perform(get("/admin/locais").with(Perfis.gestor())).andExpect(status().isOk)
            .andExpect(content().string(containsString("Unidade $s"))).andExpect(content().string(containsString("Sala T $s")))
        mvc.perform(get("/admin/responsaveis").with(Perfis.gestor())).andExpect(status().isNotFound)
        mvc.perform(get("/admin/tags-cadastradas").param("local", local.id.toString()).with(Perfis.gestor())).andExpect(status().isOk)
            .andExpect(content().string(containsString("E-TELA-$s")))   // 1.12.0: o responsável saiu da lista
            .andExpect(content().string(containsString("situacao=")).let { content().string(containsString("local=${local.id}")) })
        mvc.perform(get("/admin/etiquetas/${tag("E-TELA-$s").id}/editar").with(Perfis.gestor())).andExpect(status().isOk)
            .andExpect(content().string(containsString("Dar baixa"))).andExpect(content().string(containsString("name=\"localId\"")))
        mvc.perform(get("/admin/cadastro").with(Perfis.gestor())).andExpect(status().isOk)
            .andExpect(content().string(not(containsString("name=\"responsavelId\""))))

        // Cadastrar pela tela e permissão
        mvc.perform(post("/admin/locais").with(Perfis.gestor()).with(csrf()).param("nome", "Sala Web $s").param("unidade", unidade.id.toString()))
            .andExpect(status().is3xxRedirection)
        assertThat(cadastros.localPorNome("Sala Web $s", null, criar = false)?.unidadeId).isEqualTo(unidade.id)
        mvc.perform(post("/admin/locais").with(Perfis.usuario()).with(csrf()).param("nome", "Proibido $s")).andExpect(status().isForbidden)
    }

    // ---------- Importação ----------

    private fun csv(vararg linhas: String) = ("Etiqueta (EPC);Código;Patrimônio;Tipo;Marca;Modelo;Nº série;Unidade;Local;Responsável;Matrícula;Situação\n" +
        linhas.joinToString("\n")).toByteArray()

    @Test
    fun `importacao simula sem gravar e confirma gravando com o que faltar criado`() {
        cadastrar("E-IMP-EXISTE-$s")
        val sessao = MockHttpSession()
        val arquivo = MockMultipartFile("arquivo", "carga.csv", "text/csv", csv(
            "E-IMP-1-$s;TI-1;PI-1-$s;Notebook $s;Dell $s;Latitude $s;SN1;Matriz $s;Sala I $s;Carla $s;C$s;Em uso",
            "E-IMP-EXISTE-$s;;;;;;SN-NOVO;;;;;Extraviado",
            "E-IMP-1-$s;TI-2;;;;;;;;;;",
            "1.23457E+23;TI-3;;;;;;;;;;",
            ";TI-4;;;;;;;;;;"
        ))

        mvc.perform(multipart("/admin/tags-cadastradas/importar").file(arquivo).param("criar", "true").session(sessao).with(Perfis.gestor()).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Conferência da planilha")))
            .andExpect(content().string(containsString("tipo Notebook $s")))
            .andExpect(content().string(containsString("etiqueta repetida na planilha (também na linha 2)")))
            .andExpect(content().string(containsString("o Excel transformou o EPC em número")))
            .andExpect(content().string(containsString("sem a etiqueta (EPC)")))
        assertThat(tags.findByTagReal("E-IMP-1-$s")).isEmpty   // simulação não grava
        assertThat(cadastros.localPorNome("Sala I $s", null, criar = false)).isNull()

        mvc.perform(post("/admin/tags-cadastradas/importar/confirmar").session(sessao).with(Perfis.gestor()).with(csrf()))
            .andExpect(status().isOk).andExpect(content().string(containsString("Importação concluída")))
        val nova = tag("E-IMP-1-$s")
        assertThat(nova.patrimonio).isEqualTo("PI-1-$s")
        assertThat(nova.modeloId).isNotNull()
        assertThat(cadastros.localPorNome("Sala I $s", null, criar = false)!!.id).isEqualTo(nova.localId)
        val existente = tag("E-IMP-EXISTE-$s")
        assertThat(existente.numeroSerie).isEqualTo("SN-NOVO")
        assertThat(existente.codigoInterno).isEqualTo("TI-E-IMP-EXISTE-$s")   // célula vazia não apaga
        assertThat(existente.situacao).isEqualTo(SituacaoBem.EXTRAVIADO)
        // A planilha some da sessão depois de importada
        mvc.perform(post("/admin/tags-cadastradas/importar/confirmar").session(sessao).with(Perfis.gestor()).with(csrf()))
            .andExpect(content().string(containsString("envie de novo")))
    }

    @Test
    fun `sem criar o que falta a linha da erro e a planilha exportada volta pela importacao`() {
        val r = importacao.processar("x.csv", csv("E-SEMCRIAR-$s;TI;;Tipo Inexistente $s;;;;;;;;"), criarFaltantes = false, podeAlterar = true, gravar = true)
        assertThat(r.linhas.single().detalhe).contains("não existe no catálogo")
        assertThat(tags.findByTagReal("E-SEMCRIAR-$s")).isEmpty

        // Exportação (xlsx) → importação: o que veio do cadastro volta igual ("sem mudança")
        val local = cadastros.salvarLocal(null, "Sala X $s", null)
        cadastrar("E-VOLTA-$s", localId = local.id)
        val exportada = GeradorPlanilha.gerar(Planilha("Etiquetas", listOf("Etiqueta (EPC)", "Código", "Patrimônio", "Local", "Situação", "Última leitura"),
            listOf(listOf("E-VOLTA-$s", "TI-E-VOLTA-$s", "P-E-VOLTA-$s", "Sala X $s", "Em uso", null))), FormatoPlanilha.XLSX)
        val volta = importacao.processar("etiquetas.xlsx", exportada, criarFaltantes = false, podeAlterar = true, gravar = false)
        assertThat(volta.linhas.single().acao.name).isEqualTo("SEM_MUDANCA")
    }

    @Test
    fun `envio de planilha so para quem pode cadastrar etiquetas`() {
        val arquivo = MockMultipartFile("arquivo", "carga.csv", "text/csv", csv("E-PERM-$s;TI;;;;;;;;;;"))
        mvc.perform(multipart("/admin/tags-cadastradas/importar").file(arquivo).with(Perfis.usuario()).with(csrf()))
            .andExpect(status().isForbidden)
        // Arquivo para qualquer outra rota continua recusado antes de ler o corpo
        mvc.perform(multipart("/admin/locais").file(arquivo).with(Perfis.gestor()).with(csrf())).andExpect(status().isForbidden)
        mvc.perform(get("/admin/tags-cadastradas/importar/modelo").with(Perfis.gestor())).andExpect(status().isOk)
        mvc.perform(get("/admin/tags-cadastradas").with(Perfis.usuario()))
            .andExpect(content().string(not(containsString("/admin/tags-cadastradas/importar\""))))
    }
}
