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
import org.example.service.ImportacaoEtiquetasService
import org.example.service.PatrimonioCadastrosService
import org.example.service.PedidoTransferencia
import org.example.service.RfidService
import org.example.service.TransferenciaService
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

/** 1.8.0: os PDFs (laudo, guia e termo). O valor patrimonial saiu na 1.12.0 (os dados de valor ficam no banco). */
class Versao180IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var cadastros: PatrimonioCadastrosService
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var tipos: TipoEquipamentoRepository
    @Autowired lateinit var historico: HistoricoEtiquetaRepository
    @Autowired lateinit var auditoria: AuditoriaRepository
    @Autowired lateinit var transferencias: TransferenciaService
    @Autowired lateinit var importacao: ImportacaoEtiquetasService
    @Autowired lateinit var operadores: OperadorTeste

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val token by lazy { operadores.sessaoPadrao(mvc, "v180-$s@x.com".lowercase()) }
    private val hoje = LocalDate.now()

    private fun tag(epc: String) = tags.findByTagReal(epc).get()

    private fun tipo(nome: String): Long = catalogo.salvarTipo(TipoEquipamentoRequest(nome)).id

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

    /**
     * 1.12.0: o valor patrimonial saiu do sistema e valor, data e nota fiscal saíram da tela, mas continuam no banco:
     * a edição os manda de volta como estão, e salvar não os apaga.
     */
    @Test
    fun `valor, data e nota fiscal continuam no banco e a edicao nao os apaga`() {
        cadastrar("E-VL1-$s", valor = "6.000,00", data = hoje.minusMonths(12))
        val id = tag("E-VL1-$s").id
        val tela = mvc.perform(get("/admin/etiquetas/$id/editar").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Valor de aquisição"))))
            .andReturn().response.contentAsString
        assertThat(tela).contains("type=\"hidden\" name=\"valorAquisicao\" value=\"6.000,00\"", "name=\"notaFiscal\" value=\"NF-E-VL1-$s\"")

        // Salvar como a tela manda (com os ocultos): nada some
        mvc.perform(post("/admin/etiquetas/$id/editar").with(Perfis.admin()).with(csrf())
            .param("codigoInterno", "TI-NOVO-$s").param("patrimonio", "P-E-VL1-$s")
            .param("valorAquisicao", "6.000,00").param("dataAquisicao", hoje.minusMonths(12).toString()).param("notaFiscal", "NF-E-VL1-$s"))
            .andExpect(status().is3xxRedirection)
        val t = tag("E-VL1-$s")
        assertThat(t.codigoInterno).isEqualTo("TI-NOVO-$s")
        assertThat(t.valorAquisicao).isEqualByComparingTo("6000.00")
        assertThat(t.notaFiscal).isEqualTo("NF-E-VL1-$s")
        assertThat(historico.findByTagRealOrderByMomentoDescIdDesc("E-VL1-$s").map { it.campo }).doesNotContain("Valor de aquisição", "Nota fiscal")

        // A tela e o PDF de valores não existem mais
        mvc.perform(get("/admin/valores").with(Perfis.admin())).andExpect(status().isNotFound)
        mvc.perform(get("/admin/valores/pdf").with(Perfis.admin())).andExpect(status().isNotFound)
    }

    @Test
    fun `laudo do inventario em PDF traz os faltantes com justificativa`() {
        val tipoId = tipo("Monitor $s")
        val local = cadastros.salvarLocal(null, "Sala L $s", null)
        cadastrar("E-LD1-$s", tipoId = tipoId, localId = local.id, valor = "800", data = hoje)
        cadastrar("E-LD2-$s", tipoId = tipoId, localId = local.id, valor = "1.500,00", data = hoje)
        cadastrar("E-LD3-$s", tipoId = tipoId, localId = local.id)
        val id = inventario("Sala L $s", "E-LD1-$s", "E-NOVA-$s")
        coletor("/api/android/inventarios/$id/justificativas", """[{"tag":"E-LD2-$s","motivo":"EMPRESTADO","observacao":"com o João"}]""")

        val texto = textoDoPdf("/admin/inventarios/$id/laudo.pdf")
        assertThat(texto).contains("Laudo de inventário", "Sala L $s", "Bens que faltaram (2)", "P-E-LD2-$s", "com o João",
            "Anexo: etiquetas lidas (2)", "E-NOVA-$s", "Responsável pela contagem")
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
