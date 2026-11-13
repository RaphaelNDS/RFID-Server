package org.example.integracao

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.Acao
import org.example.model.AreaAuditoria
import org.example.model.Modulo
import org.example.model.MotivoBaixa
import org.example.model.OrigemMovimento
import org.example.model.SituacaoBem
import org.example.model.TipoMovimento
import org.example.repository.AuditoriaRepository
import org.example.repository.HistoricoEtiquetaRepository
import org.example.repository.MovimentoItemRepository
import org.example.repository.MovimentoRepository
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.service.MovimentoService
import org.example.service.NovoMovimento
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.example.suporte.comoOperador
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** 2.0.0: saída e entrada de equipamentos pela web e pelo coletor, com as recusas, o histórico, a auditoria e o PDF. */
class MovimentosIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var service: MovimentoService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var movimentos: MovimentoRepository
    @Autowired lateinit var itens: MovimentoItemRepository
    @Autowired lateinit var historico: HistoricoEtiquetaRepository
    @Autowired lateinit var auditoria: AuditoriaRepository
    @Autowired lateinit var operadores: OperadorTeste

    private val s = UUID.randomUUID().toString().take(6).uppercase()

    private fun tag(epc: String) = tags.findByTagReal(epc).get()

    private fun cadastrar(epc: String, situacao: String? = null) =
        rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-$epc", patrimonio = "P-$epc", situacao = situacao))

    private fun saida(vararg ids: String, destino: String = "Obra $s") =
        service.registrar(NovoMovimento(TipoMovimento.SAIDA, ids.toList(), destino, registradoPor = "teste"), tudoOuNada = true)

    /** Operador logado no coletor-teste com as permissões pedidas. */
    private fun operador(vararg permissoes: Pair<Modulo, Acao>): String {
        val email = "mov-${UUID.randomUUID().toString().take(8)}@x.com"
        operadores.criarUsuario(email = email, permissoes = arrayOf(Modulo.LEITURA to Acao.READ, *permissoes))
        return operadores.entrar(mvc, email)
    }

    private fun movimentarPeloColetor(token: String, json: String) =
        mvc.perform(post("/api/android/movimentos").comoOperador(token).contentType(MediaType.APPLICATION_JSON).content(json))

    // ---------------- Web ----------------

    @Test
    fun `saida pela web por patrimonio e EPC deixa fora do estoque, com historico, auditoria e comprovante`() {
        cadastrar("E-S1-$s")
        cadastrar("E-S2-$s")

        val url = mvc.perform(post("/admin/movimentos/saida").with(Perfis.gestor()).with(csrf())
            .param("lista", "P-E-S1-$s\ne-s2-$s\n\nP-E-S1-$s").param("destino", "  Obra   Centro $s ").param("observacao", "Nota 123"))
            .andExpect(status().is3xxRedirection).andExpect(redirectedUrlPattern("/admin/movimentos/*"))
            .andExpect(flash().attribute("msg", containsString("2 equipamento(s) fora do estoque")))
            .andReturn().response.redirectedUrl!!

        assertThat(tag("E-S1-$s").situacao).isEqualTo(SituacaoBem.FORA)
        assertThat(tag("E-S2-$s").situacao).isEqualTo(SituacaoBem.FORA)
        val id = url.substringAfterLast("/").toLong()
        val movimento = movimentos.findById(id).get()
        assertThat(movimento.tipo).isEqualTo(TipoMovimento.SAIDA)
        assertThat(movimento.destino).isEqualTo("Obra Centro $s")
        assertThat(movimento.origem).isEqualTo(OrigemMovimento.WEB)
        assertThat(movimento.registradoPor).isEqualTo("gestor@x.com")
        assertThat(itens.findAllByMovimentoIdOrderByIdAsc(id).map { it.tagReal }).containsExactly("E-S1-$s", "E-S2-$s")

        // Histórico da etiqueta: a situação mudou, com o número da saída e o destino
        val linha = historico.findByTagRealOrderByMomentoDescIdDesc("E-S1-$s").first { it.campo == "Situação" }
        assertThat(linha.valorAntigo).isEqualTo("No estoque")
        assertThat(linha.valorNovo).isEqualTo("Fora do estoque")
        assertThat(linha.origem).contains("Saída nº $id", "Obra Centro $s")
        assertThat(auditoria.findAll().any { it.area == AreaAuditoria.MOVIMENTOS && it.alvo == "Nº $id" && it.detalhe!!.contains("P-E-S1-$s") }).isTrue()

        // Detalhe, lista (com filtro), edição da etiqueta e comprovante em PDF
        mvc.perform(get(url).with(Perfis.usuario())).andExpect(status().isOk)
            .andExpect(content().string(containsString("Saída nº $id"))).andExpect(content().string(containsString("Obra Centro $s")))
        mvc.perform(get("/admin/movimentos").param("busca", "p-e-s2-$s").param("tipo", "SAIDA").with(Perfis.usuario())).andExpect(status().isOk)
            .andExpect(content().string(containsString("/admin/movimentos/$id")))
        mvc.perform(get("/admin/movimentos").param("busca", "p-e-s2-$s").param("tipo", "ENTRADA").with(Perfis.usuario()))
            .andExpect(content().string(not(containsString("/admin/movimentos/$id\""))))
        mvc.perform(get("/admin/etiquetas/${tag("E-S1-$s").id}/editar").with(Perfis.gestor()))
            .andExpect(content().string(containsString("fora do estoque"))).andExpect(content().string(containsString("Obra Centro $s")))
        val pdf = mvc.perform(get("/admin/movimentos/$id/comprovante.pdf").with(Perfis.usuario()))
            .andExpect(status().isOk).andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andReturn().response.contentAsByteArray
        val texto = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }.replace(Regex("\r?\n"), " ")
        assertThat(texto).contains("Comprovante de saída de equipamentos", "Nº $id", "Obra Centro $s", "P-E-S1-$s", "Nota 123", "Recebido por")

        // Início: os números do estoque e a última movimentação
        mvc.perform(get("/dashboard").with(Perfis.gestor())).andExpect(status().isOk)
            .andExpect(content().string(containsString("Fora do estoque")))
            .andExpect(content().string(containsString("Saída nº $id")))
    }

    @Test
    fun `entrada traz de volta so o que esta fora, e as recusas nao gravam nada na web`() {
        cadastrar("E-E1-$s")
        cadastrar("E-E2-$s")
        cadastrar("E-E3-$s")
        saida("E-E1-$s", "E-E2-$s")

        // Um no estoque e um que não existe na lista: nada é gravado, a tela volta com os motivos e a lista digitada
        mvc.perform(post("/admin/movimentos/entrada").with(Perfis.gestor()).with(csrf()).param("lista", "E-E1-$s\nE-E3-$s\nNAO-EXISTE-$s"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Nada foi registrado")))
            .andExpect(content().string(containsString("Já está no estoque")))
            .andExpect(content().string(containsString("Não cadastrado")))
            .andExpect(content().string(containsString("NAO-EXISTE-$s")))
        assertThat(tag("E-E1-$s").situacao).isEqualTo(SituacaoBem.FORA)

        mvc.perform(post("/admin/movimentos/entrada").with(Perfis.gestor()).with(csrf()).param("lista", "P-E-E1-$s;P-E-E2-$s"))
            .andExpect(status().is3xxRedirection).andExpect(flash().attribute("msg", containsString("de volta ao estoque")))
        assertThat(tag("E-E1-$s").situacao).isEqualTo(SituacaoBem.DISPONIVEL)
        assertThat(tag("E-E2-$s").situacao).isEqualTo(SituacaoBem.DISPONIVEL)
        assertThat(historico.findByTagRealOrderByMomentoDescIdDesc("E-E2-$s").first { it.campo == "Situação" }.origem).contains("Entrada nº")
    }

    @Test
    fun `saida recusa o que ja esta fora, o baixado, o extraviado e a falta de destino`() {
        cadastrar("E-R1-$s")
        cadastrar("E-R2-$s")
        cadastrar("E-R3-$s", situacao = "EXTRAVIADO")
        cadastrar("E-R4-$s")
        rfid.darBaixa(tag("E-R2-$s").id, MotivoBaixa.SUCATA, null, null)
        val primeira = saida("E-R1-$s").movimento!!

        val r = saida("E-R1-$s", "E-R2-$s", "E-R3-$s", "E-R4-$s")
        assertThat(r.movimento).isNull()   // web: tudo ou nada
        assertThat(r.itens.associate { it.identificador to it.motivo }).containsEntry("E-R4-$s", null)
        assertThat(r.recusados.first { it.identificador == "E-R1-$s" }.motivo).contains("Já está fora do estoque", "saída nº ${primeira.id}")
        assertThat(r.recusados.first { it.identificador == "E-R2-$s" }.motivo).contains("Baixado")
        assertThat(r.recusados.first { it.identificador == "E-R3-$s" }.motivo).contains("Extraviado")
        assertThat(tag("E-R4-$s").situacao).isEqualTo(SituacaoBem.DISPONIVEL)

        assertThatThrownBy { saida("E-R4-$s", destino = "  ") }.hasMessageContaining("Informe o destino")
        assertThatThrownBy { service.registrar(NovoMovimento(TipoMovimento.ENTRADA, listOf(" ", ""), registradoPor = "t"), true) }
            .hasMessageContaining("ao menos um equipamento")
        mvc.perform(post("/admin/movimentos/saida").with(Perfis.gestor()).with(csrf()).param("lista", "E-R4-$s"))
            .andExpect(status().isOk).andExpect(content().string(containsString("Informe o destino")))
            .andExpect(content().string(containsString("E-R4-$s")))

        // Fora do estoque não muda de situação pela edição nem pela importação, e sai do esperado do inventário
        val fora = tag("E-R1-$s")
        rfid.editarTag(fora.id, CadastroRequest(codigoInterno = fora.codigoInterno, patrimonio = fora.patrimonio, situacao = "DISPONIVEL"))
        assertThat(tag("E-R1-$s").situacao).isEqualTo(SituacaoBem.FORA)
        assertThatThrownBy { rfid.editarTag(tag("E-R4-$s").id, CadastroRequest(codigoInterno = "X", situacao = "FORA")) }
            .hasMessageContaining("Movimentações")
    }

    @Test
    fun `permissoes das telas de movimentacao e menu`() {
        mvc.perform(get("/admin/movimentos").with(Perfis.somenteCatalogo())).andExpect(status().isForbidden)
        mvc.perform(get("/admin/movimentos/saida").with(Perfis.usuario())).andExpect(status().isForbidden)
        mvc.perform(post("/admin/movimentos/saida").with(Perfis.usuario()).with(csrf()).param("lista", "X").param("destino", "Y"))
            .andExpect(status().isForbidden)
        mvc.perform(get("/admin/movimentos").with(Perfis.usuario())).andExpect(status().isOk)
            .andExpect(content().string(not(containsString("/admin/movimentos/saida"))))
        mvc.perform(get("/admin/movimentos").with(Perfis.gestor())).andExpect(status().isOk)
            .andExpect(content().string(containsString("/admin/movimentos/saida")))
        // Menu: Movimentações para quem vê; nada da locação
        mvc.perform(get("/dashboard").with(Perfis.usuario())).andExpect(status().isOk)
            .andExpect(content().string(containsString("href=\"/admin/movimentos\"")))
            .andExpect(content().string(not(containsString("/admin/pedidos"))))
            .andExpect(content().string(not(containsString("/admin/transferencias"))))
        mvc.perform(get("/dashboard").with(Perfis.somenteCatalogo()))
            .andExpect(content().string(not(containsString("href=\"/admin/movimentos\""))))

        // Busca da tela: só os que podem ir
        cadastrar("E-C1-$s")
        cadastrar("E-C2-$s")
        saida("E-C2-$s")
        mvc.perform(get("/admin/movimentos/candidatos").param("tipo", "SAIDA").param("busca", "E-C").with(Perfis.gestor()))
            .andExpect(status().isOk).andExpect(content().string(containsString("E-C1-$s"))).andExpect(content().string(not(containsString("E-C2-$s"))))
        mvc.perform(get("/admin/movimentos/candidatos").param("tipo", "ENTRADA").param("busca", "E-C").with(Perfis.gestor()))
            .andExpect(content().string(containsString("E-C2-$s"))).andExpect(content().string(not(containsString("E-C1-$s"))))
    }

    @Test
    fun `rotas da versao completa nao existem mais`() {
        listOf("/admin/pedidos", "/admin/clientes", "/admin/manutencoes", "/admin/transferencias", "/admin/disponibilidade",
            "/admin/relatorios-locacao", "/admin/acessorios").forEach { url ->
            mvc.perform(get(url).with(Perfis.admin())).andExpect(status().isNotFound)
        }
    }

    // ---------------- Coletor ----------------

    @Test
    fun `coletor registra a saida e a entrada com o resultado de cada EPC`() {
        cadastrar("E-K1-$s")
        cadastrar("E-K2-$s")
        cadastrar("E-K3-$s")
        rfid.darBaixa(tag("E-K3-$s").id, MotivoBaixa.PERDA, null, null)
        val token = operador(Modulo.MOVIMENTO to Acao.CREATE)

        movimentarPeloColetor(token, """{"tipo":"SAIDA","destino":"Cliente $s","observacao":"pelo coletor","epcs":["e-k1-$s","E-K1-$s","E-K3-$s","NAO-$s"]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tipo").value("SAIDA"))
            .andExpect(jsonPath("$.aceitos").value(1))
            .andExpect(jsonPath("$.recusados").value(2))
            .andExpect(jsonPath("$.movimento").isNumber)
            .andExpect(jsonPath("$.resultados[0].epc").value("e-k1-$s"))
            .andExpect(jsonPath("$.resultados[0].aceito").value(true))
            .andExpect(jsonPath("$.resultados[0].patrimonio").value("P-E-K1-$s"))
            .andExpect(jsonPath("$.resultados[1].aceito").value(false))
            .andExpect(jsonPath("$.resultados[1].motivo").value(containsString("Baixado")))
            .andExpect(jsonPath("$.resultados[2].motivo").value("Etiqueta não cadastrada"))
        assertThat(tag("E-K1-$s").situacao).isEqualTo(SituacaoBem.FORA)
        val movimento = movimentos.findAll().first { it.destino == "Cliente $s" }
        assertThat(movimento.origem).isEqualTo(OrigemMovimento.COLETOR)
        assertThat(movimento.registradoPor).isEqualTo("Olga Operadora (coletor-teste)")
        assertThat(historico.findByTagRealOrderByMomentoDescIdDesc("E-K1-$s").first { it.campo == "Situação" }.origem)
            .startsWith("Coletor coletor-teste").contains("Saída nº ${movimento.id}")

        // Ficha do bem no coletor: fora do estoque, para onde foi e quando
        mvc.perform(get("/api/android/tags/E-K1-$s").comoOperador(token)).andExpect(status().isOk)
            .andExpect(jsonPath("$.situacao").value("FORA"))
            .andExpect(jsonPath("$.situacaoRotulo").value("Fora do estoque"))
            .andExpect(jsonPath("$.destino").value("Cliente $s"))
            .andExpect(jsonPath("$.saidaEm").isNotEmpty)

        // De novo (reenvio): recusado como já fora, nada novo é gravado
        movimentarPeloColetor(token, """{"tipo":"saida","destino":"Cliente $s","epcs":["E-K1-$s"]}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.movimento").doesNotExist())
            .andExpect(jsonPath("$.resultados[0].motivo").value(containsString("Já está fora do estoque")))

        // Entrada: E-K1 volta; E-K2 já estava no estoque
        movimentarPeloColetor(token, """{"tipo":"ENTRADA","epcs":["E-K1-$s","E-K2-$s"]}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.aceitos").value(1))
            .andExpect(jsonPath("$.resultados[1].motivo").value("Já está no estoque"))
        assertThat(tag("E-K1-$s").situacao).isEqualTo(SituacaoBem.DISPONIVEL)
        mvc.perform(get("/api/android/tags/E-K1-$s").comoOperador(token)).andExpect(jsonPath("$.destino").doesNotExist())
    }

    @Test
    fun `coletor recebe 400 no pedido invalido e 403 sem a permissao`() {
        val token = operador(Modulo.MOVIMENTO to Acao.CREATE)
        movimentarPeloColetor(token, """{"tipo":"EMPRESTIMO","epcs":["X"]}""")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.mensagem").value(containsString("SAIDA ou ENTRADA")))
            .andExpect(jsonPath("$.caminho").value("/api/android/movimentos"))
        movimentarPeloColetor(token, """{"tipo":"SAIDA","epcs":["X"]}""")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.mensagem").value(containsString("destino")))
        movimentarPeloColetor(token, """{"tipo":"ENTRADA","epcs":[]}""")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.mensagem").value(containsString("ao menos um")))

        // Só com Leituras: 403, e o login diz que não pode movimentar
        val semPermissao = operador()
        movimentarPeloColetor(semPermissao, """{"tipo":"ENTRADA","epcs":["X"]}""").andExpect(status().isForbidden)
    }

    @Test
    fun `login do coletor diz se o operador pode movimentar e a rota de transferencia saiu`() {
        val email = "login-mov-$s@x.com".lowercase()
        operadores.criarUsuario(email = email, permissoes = arrayOf(Modulo.LEITURA to Acao.READ, Modulo.MOVIMENTO to Acao.CREATE))
        mvc.perform(post("/api/android/login").header(OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR)
            .contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","senha":"${OperadorTeste.SENHA}"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.podeMovimentar").value(true))
            .andExpect(jsonPath("$.podeTransferir").doesNotExist())
            .andExpect(jsonPath("$.podeMovimentarLocacao").doesNotExist())

        val token = operadores.entrar(mvc, email)
        cadastrar("E-TR-$s")
        mvc.perform(post("/api/android/tags/E-TR-$s/transferencia").comoOperador(token).contentType(MediaType.APPLICATION_JSON)
            .content("""{"local":"Sala"}""")).andExpect(status().isNotFound)
    }
}
