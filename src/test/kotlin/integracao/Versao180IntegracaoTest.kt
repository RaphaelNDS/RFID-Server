package org.example.integracao

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.example.model.AreaAuditoria
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
import org.example.service.RfidService
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

/** 1.8.0: o laudo do inventário em PDF (a guia de transferência saiu na 2.0.0; o comprovante da movimentação tem teste próprio). */
class Versao180IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var cadastros: PatrimonioCadastrosService
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var tipos: TipoEquipamentoRepository
    @Autowired lateinit var historico: HistoricoEtiquetaRepository
    @Autowired lateinit var auditoria: AuditoriaRepository
    @Autowired lateinit var importacao: ImportacaoEtiquetasService
    @Autowired lateinit var operadores: OperadorTeste

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val token by lazy { operadores.sessaoPadrao(mvc, "v180-$s@x.com".lowercase()) }
    private val hoje = LocalDate.now()

    private fun tag(epc: String) = tags.findByTagReal(epc).get()

    private fun tipo(nome: String): Long = catalogo.salvarTipo(TipoEquipamentoRequest(nome)).id

    private fun cadastrar(epc: String, tipoId: Long? = null, localId: Long? = null) =
        rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-$epc", patrimonio = "P-$epc", tipoEquipamentoId = tipoId, localId = localId))

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
    fun `laudo do inventario em PDF traz os faltantes com justificativa`() {
        val tipoId = tipo("Monitor $s")
        val local = cadastros.salvarLocal(null, "Sala L $s", null)
        cadastrar("E-LD1-$s", tipoId = tipoId, localId = local.id)
        cadastrar("E-LD2-$s", tipoId = tipoId, localId = local.id)
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
}
