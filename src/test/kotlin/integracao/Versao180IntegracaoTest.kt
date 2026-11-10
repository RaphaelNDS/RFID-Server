package org.example.integracao

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.AreaAuditoria
import org.example.model.MotivoBaixa
import org.example.repository.AuditoriaRepository
import org.example.repository.HistoricoEtiquetaRepository
import org.example.repository.TagRepository
import org.example.repository.TipoEquipamentoRepository
import org.example.request.CadastroRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.AcaoLinha
import org.example.service.CatalogoService
import org.example.service.FiltroValores
import org.example.service.ImportacaoEtiquetasService
import org.example.service.PatrimonioCadastrosService
import org.example.service.PedidoTransferencia
import org.example.service.RfidService
import org.example.service.TransferenciaService
import org.example.service.ValorPatrimonialService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.example.suporte.comoOperador
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** 1.8.0: valor e depreciação do bem (regra no tipo) e os PDFs (laudo, valores, guia e termo). */
class Versao180IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var cadastros: PatrimonioCadastrosService
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var tipos: TipoEquipamentoRepository
    @Autowired lateinit var historico: HistoricoEtiquetaRepository
    @Autowired lateinit var auditoria: AuditoriaRepository
    @Autowired lateinit var valores: ValorPatrimonialService
    @Autowired lateinit var transferencias: TransferenciaService
    @Autowired lateinit var importacao: ImportacaoEtiquetasService
    @Autowired lateinit var operadores: OperadorTeste

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val token by lazy { operadores.sessaoPadrao(mvc, "v180-$s@x.com".lowercase()) }
    private val hoje = LocalDate.now()

    private fun tag(epc: String) = tags.findByTagReal(epc).get()

    /** Tipo com 60 meses e residual de 10%, pela tela (Catálogo Editar). */
    private fun tipoComRegra(nome: String = "Notebook $s"): Long {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest(nome))
        mvc.perform(post("/admin/valores/tipos/${tipo.id}").with(Perfis.gestor()).with(csrf())
            .param("vidaUtil", "60").param("residual", "10,0"))
            .andExpect(status().is3xxRedirection)
            .andExpect(flash().attribute("msg", containsString("60 meses, residual 10%")))
        return tipo.id
    }

    private fun cadastrar(epc: String, tipoId: Long? = null, localId: Long? = null, responsavelId: Long? = null,
                          valor: String? = null, data: LocalDate? = null) =
        rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-$epc", patrimonio = "P-$epc", tipoEquipamentoId = tipoId,
            localId = localId, responsavelId = responsavelId, valorAquisicao = valor, dataAquisicao = data?.toString(), notaFiscal = "NF-$epc"))

    private fun coletor(url: String, json: String) =
        mvc.perform(post(url).comoOperador(token).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk)

    private fun inventario(local: String, vararg epcs: String): String {
        val id = UUID.randomUUID().toString()
        coletor("/api/android/inventarios", """{"id":"$id","local":"$local"}""")
        coletor("/api/android/inventarios/$id/itens", """{"itens":[${epcs.joinToString { """{"tag":"$it"}""" }}]}""")
        coletor("/api/android/inventarios/$id/finalizar", "{}")
        return id
    }

    /** Baixa o PDF com o perfil, confere que é PDF e devolve o texto dele. */
    private fun textoDoPdf(url: String, perfil: org.springframework.test.web.servlet.request.RequestPostProcessor = Perfis.admin()): String {
        val resposta = mvc.perform(get(url).with(perfil))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition", containsString("inline")))
            .andReturn().response.contentAsByteArray
        assertThat(String(resposta.copyOf(5), Charsets.US_ASCII)).isEqualTo("%PDF-")
        // Sem as quebras de linha: o texto de uma coluna estreita quebra no meio ("P-E-1-" e "ABC")
        return PDDocument.load(resposta).use { PDFTextStripper().getText(it) }.replace(Regex("\r?\n"), "")
    }

    @Test
    fun `regra do tipo e valor do bem dao o valor contabil, gravado no historico`() {
        val tipoId = tipoComRegra()
        assertThat(tipos.findById(tipoId).get().vidaUtilMeses).isEqualTo(60)
        assertThat(auditoria.findAll().any { it.area == AreaAuditoria.CATALOGO && it.acao == "Depreciação do tipo alterada" && it.alvo == "Notebook $s" }).isTrue()

        // R$ 6.000 comprado há 12 meses: 5.400 depreciáveis / 60 = 90 por mês → 1.080 → contábil 4.920
        cadastrar("E-VL1-$s", tipoId = tipoId, valor = "6.000,00", data = hoje.minusMonths(12))
        val d = valores.depreciacaoDe(tag("E-VL1-$s"))!!
        assertThat(d.mesesDepreciados).isEqualTo(12)
        assertThat(d.valorContabil).isEqualByComparingTo("4920.00")
        assertThat(tag("E-VL1-$s").notaFiscal).isEqualTo("NF-E-VL1-$s")

        // Edição pela tela: valor digitado como o brasileiro escreve; a mudança vai para o histórico
        val id = tag("E-VL1-$s").id
        mvc.perform(post("/admin/etiquetas/$id/editar").with(Perfis.admin()).with(csrf())
            .param("codigoInterno", "TI-E-VL1-$s").param("patrimonio", "P-E-VL1-$s").param("tipoEquipamentoId", "$tipoId")
            .param("valorAquisicao", "R$ 7.200,50").param("dataAquisicao", hoje.minusMonths(12).toString()).param("notaFiscal", "NF 999"))
            .andExpect(status().is3xxRedirection)
        assertThat(tag("E-VL1-$s").valorAquisicao).isEqualByComparingTo("7200.50")
        assertThat(historico.findByTagRealOrderByMomentoDescIdDesc("E-VL1-$s").map { it.campo })
            .contains("Valor de aquisição", "Nota fiscal")

        // A tela de edição mostra o valor contábil de hoje
        mvc.perform(get("/admin/etiquetas/$id/editar").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Valor contábil hoje")))
            .andExpect(content().string(containsString("7.200,50")))
    }

    @Test
    fun `valor invalido, data futura e regra invalida sao recusados sem mudar nada`() {
        assertThatThrownBy { cadastrar("E-VL2-$s", valor = "-5") }.hasMessageContaining("negativo")
        assertThatThrownBy { cadastrar("E-VL2-$s", valor = "10", data = hoje.plusDays(1)) }.hasMessageContaining("no futuro")
        assertThat(tags.findByTagReal("E-VL2-$s")).isEmpty

        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Mesa $s"))
        mvc.perform(post("/admin/valores/tipos/${tipo.id}").with(Perfis.gestor()).with(csrf()).param("vidaUtil", "0"))
            .andExpect(flash().attribute("erro", containsString("Vida útil inválida")))
        mvc.perform(post("/admin/valores/tipos/${tipo.id}").with(Perfis.gestor()).with(csrf()).param("vidaUtil", "").param("residual", "5"))
            .andExpect(flash().attribute("erro", containsString("Informe a vida útil")))
        assertThat(tipos.findById(tipo.id).get().vidaUtilMeses).isNull()

        // Mudar a regra é do Catálogo Editar: quem só vê não muda
        mvc.perform(post("/admin/valores/tipos/${tipo.id}").with(Perfis.usuario()).with(csrf()).param("vidaUtil", "12"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `relatorio de valores soma por tipo e por local, deixa os baixados de fora e sai em PDF`() {
        val tipoId = tipoComRegra("Coletor $s")
        val local = cadastros.salvarLocal(null, "Sala V $s", null)
        cadastrar("E-VR1-$s", tipoId = tipoId, localId = local.id, valor = "1000", data = hoje)
        cadastrar("E-VR2-$s", tipoId = tipoId, localId = local.id, valor = "2000", data = hoje)
        cadastrar("E-VR3-$s", tipoId = tipoId, localId = local.id)              // sem valor
        cadastrar("E-VR4-$s", tipoId = tipoId, localId = local.id, valor = "500", data = hoje)
        rfid.darBaixa(tag("E-VR4-$s").id, MotivoBaixa.SUCATA, hoje, null)

        val r = valores.relatorio(FiltroValores(localId = local.id))
        assertThat(r.total.bens).isEqualTo(3)
        assertThat(r.total.comValor).isEqualTo(2)
        assertThat(r.total.valorAquisicao).isEqualByComparingTo("3000")
        assertThat(r.porTipo.single().nome).isEqualTo("Coletor $s")
        assertThat(valores.relatorio(FiltroValores(localId = local.id, incluirBaixados = true)).total.bens).isEqualTo(4)

        mvc.perform(get("/admin/valores").param("local", "${local.id}").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("R$ 3.000,00")))
            .andExpect(content().string(containsString("1 sem valor de aquisição")))

        val texto = textoDoPdf("/admin/valores/pdf?local=${local.id}", Perfis.usuario())
        assertThat(texto).contains("Bens com valor contábil", "Local Sala V $s", "R$ 3.000,00", "P-E-VR1-$s", "sem valor de aquisição")
        assertThat(texto).doesNotContain("P-E-VR4-$s")
        assertThat(auditoria.findAll().any { it.area == AreaAuditoria.EXPORTACAO && it.acao == "Bens com valor contábil em PDF" }).isTrue()

        // Sem RFID Ver não vê os valores
        mvc.perform(get("/admin/valores/pdf").with(Perfis.somenteCatalogo())).andExpect(status().isForbidden)
        mvc.perform(get("/admin/valores").with(Perfis.somenteCatalogo())).andExpect(status().isForbidden)
    }

    @Test
    fun `laudo do inventario em PDF traz os faltantes com justificativa e valor contabil`() {
        val tipoId = tipoComRegra("Monitor $s")
        val local = cadastros.salvarLocal(null, "Sala L $s", null)
        cadastrar("E-LD1-$s", tipoId = tipoId, localId = local.id, valor = "800", data = hoje)
        cadastrar("E-LD2-$s", tipoId = tipoId, localId = local.id, valor = "1.500,00", data = hoje)
        cadastrar("E-LD3-$s", tipoId = tipoId, localId = local.id)
        val id = inventario("Sala L $s", "E-LD1-$s", "E-NOVA-$s")
        coletor("/api/android/inventarios/$id/justificativas", """[{"tag":"E-LD2-$s","motivo":"EMPRESTADO","observacao":"com o João"}]""")

        val texto = textoDoPdf("/admin/inventarios/$id/laudo.pdf")
        assertThat(texto).contains("Laudo de inventário", "Sala L $s", "Bens que faltaram (2)", "P-E-LD2-$s", "com o João",
            "R$ 1.500,00", "sem valor", "Anexo: etiquetas lidas (2)", "E-NOVA-$s", "Responsável pela contagem")
        // O que foi lido não está entre os que faltaram (aparece só no anexo)
        assertThat(texto.substringBefore("Anexo")).doesNotContain("P-E-LD1-$s")
        assertThat(auditoria.findAll().any { it.area == AreaAuditoria.EXPORTACAO && it.acao == "Laudo do inventário em PDF" }).isTrue()

        // A tela do inventário tem o botão; sem Leituras, o laudo é recusado
        mvc.perform(get("/admin/inventarios/$id").with(Perfis.admin())).andExpect(content().string(containsString("/laudo.pdf")))
        mvc.perform(get("/admin/inventarios/$id/laudo.pdf").with(Perfis.somenteCatalogo())).andExpect(status().isForbidden)
    }

    @Test
    fun `guia de transferencia e termo de responsabilidade em PDF`() {
        val origem = cadastros.salvarLocal(null, "Sala G1 $s", null)
        val destino = cadastros.salvarLocal(null, "Sala G2 $s", null)
        val rita = cadastros.salvarResponsavel(null, "Rita Gonçalves $s", "TI", "M-$s", null)
        cadastrar("E-GT1-$s", localId = origem.id, responsavelId = rita.id)
        val guia = transferencias.transferir(PedidoTransferencia(listOf("E-GT1-$s"), destino.id, null, motivo = "Mudança de sala"))

        val textoGuia = textoDoPdf("/admin/transferencias/${guia.id}/guia.pdf")
        assertThat(textoGuia).contains("Guia de transferência de bens", "Guia nº ${guia.id}", "Sala G2 $s", "Sala G1 $s", "Mudança de sala", "Recebido por")

        val textoTermo = textoDoPdf("/admin/responsaveis/${rita.id}/termo.pdf")
        assertThat(textoTermo).contains("Termo de responsabilidade pela guarda de bens", "Rita Gonçalves $s", "M-$s", "P-E-GT1-$s", "Total: 1 bem(ns).")

        // As telas apontam para os PDFs
        mvc.perform(get("/admin/transferencias/${guia.id}").with(Perfis.admin())).andExpect(content().string(containsString("/guia.pdf")))
        mvc.perform(get("/admin/responsaveis/${rita.id}/termo").with(Perfis.admin())).andExpect(content().string(containsString("/termo.pdf")))
        // Termo pede Catálogo Ver e RFID Ver
        mvc.perform(get("/admin/responsaveis/${rita.id}/termo.pdf").with(Perfis.somenteCatalogo())).andExpect(status().isForbidden)
    }

    @Test
    fun `importacao le valor, data e nota fiscal e nao acusa mudanca quando so a grafia muda`() {
        val csv = """
            Etiqueta (EPC);Código;Valor de aquisição;Data de aquisição;Nota fiscal
            E-IM1-$s;TI-IM1;1234,5;15/01/2026;NF 1
            E-IM2-$s;TI-IM2;abc;;
            E-IM3-$s;TI-IM3;10;${hoje.plusDays(2).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))};
        """.trimIndent().toByteArray()
        val r = importacao.processar("bens.csv", csv, criarFaltantes = false, podeAlterar = true, gravar = true)
        assertThat(r.linhas.map { it.acao }).containsExactly(AcaoLinha.NOVA, AcaoLinha.ERRO, AcaoLinha.ERRO)
        assertThat(r.linhas[1].detalhe).contains("valor de aquisição inválido")
        assertThat(r.linhas[2].detalhe).contains("no futuro")
        assertThat(tag("E-IM1-$s").valorAquisicao).isEqualByComparingTo(BigDecimal("1234.50"))
        assertThat(tag("E-IM1-$s").dataAquisicao).isEqualTo(LocalDate.of(2026, 1, 15))
        assertThat(tag("E-IM1-$s").notaFiscal).isEqualTo("NF 1")

        // Mesmo valor escrito de outro jeito ("1.234,50"): sem mudança
        val deNovo = "Etiqueta (EPC);Código;Valor de aquisição;Data de aquisição\nE-IM1-$s;TI-IM1;1.234,50;2026-01-15".toByteArray()
        assertThat(importacao.processar("bens.csv", deNovo, false, true, gravar = false).linhas.single().acao).isEqualTo(AcaoLinha.SEM_MUDANCA)
    }
}
