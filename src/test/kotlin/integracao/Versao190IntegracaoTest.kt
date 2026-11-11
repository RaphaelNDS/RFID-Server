package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.TipoAnexo
import org.example.repository.AnexoBemConteudoRepository
import org.example.repository.AnexoBemRepository
import org.example.repository.HistoricoEtiquetaRepository
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** 1.9.0: fotos e anexos do bem. */
class Versao190IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var anexos: AnexoBemRepository
    @Autowired lateinit var conteudos: AnexoBemConteudoRepository
    @Autowired lateinit var historico: HistoricoEtiquetaRepository
    @Autowired lateinit var em: jakarta.persistence.EntityManager

    private val s = UUID.randomUUID().toString().take(6).uppercase()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(200) { it.toByte() }
    private val pdf = "%PDF-1.4\n%fim".toByteArray()

    private fun cadastrar(epc: String): Long {
        rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-$epc", patrimonio = "P-$epc"))
        return tags.findByTagReal(epc).get().id
    }

    private fun enviar(id: Long, nome: String, bytes: ByteArray, perfil: org.springframework.test.web.servlet.request.RequestPostProcessor = Perfis.gestor(),
                       contentType: String = "application/octet-stream", descricao: String? = null) =
        mvc.perform(multipart("/admin/etiquetas/$id/anexos").file(MockMultipartFile("arquivo", nome, contentType, bytes))
            .apply { if (descricao != null) param("descricao", descricao) }
            .with(perfil).with(csrf()))

    @Test
    fun `foto e PDF enviados aparecem na edicao, abrem com o tipo certo e ficam no historico`() {
        val id = cadastrar("E-AN1-$s")

        enviar(id, "C:\\fotos\\notebook frente.png", png, descricao = "Frente do equipamento")
            .andExpect(status().is3xxRedirection)
            .andExpect(flash().attribute("msg", containsString("notebook frente.png")))
        // O navegador diz "image/jpeg", mas o arquivo é PDF: vale o que os bytes dizem, e a extensão segue o tipo real
        enviar(id, "nota.jpg", pdf, contentType = "image/jpeg")
            .andExpect(status().is3xxRedirection)

        val lista = anexos.findAllByTagIdOrderByIdAsc(id)
        assertThat(lista.map { it.nome }).containsExactly("notebook frente.png", "nota.pdf")
        assertThat(lista.map { it.tipo }).containsExactly(TipoAnexo.PNG, TipoAnexo.PDF)
        assertThat(lista[0].descricao).isEqualTo("Frente do equipamento")
        assertThat(lista[0].enviadoPor).isEqualTo("gestor@x.com")

        mvc.perform(get("/admin/etiquetas/$id/editar").with(Perfis.gestor()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Fotos e anexos")))
            .andExpect(content().string(containsString("notebook frente.png")))
            .andExpect(content().string(containsString("Frente do equipamento")))

        // Quem só vê (RFID · Ver) abre o arquivo
        val foto = mvc.perform(get("/admin/etiquetas/$id/anexos/${lista[0].id}").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "image/png"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Disposition", containsString("inline")))
            .andReturn().response.contentAsByteArray
        assertThat(foto).isEqualTo(png)
        mvc.perform(get("/admin/etiquetas/$id/anexos/${lista[1].id}").param("baixar", "true").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(header().string("Content-Disposition", containsString("attachment")))

        val linhas = historico.findByTagRealOrderByMomentoDescIdDesc("E-AN1-$s").filter { it.campo == "Anexo" }
        assertThat(linhas.map { it.valorNovo }).containsExactlyInAnyOrder("notebook frente.png (208 bytes)", "nota.pdf (13 bytes)")
        assertThat(linhas.all { it.usuario == "gestor@x.com" }).isTrue()
    }

    @Test
    fun `arquivo de outro tipo, vazio ou grande demais e recusado`() {
        val id = cadastrar("E-AN2-$s")

        enviar(id, "pagina.html", "<html><script>alert(1)</script>".toByteArray(), contentType = "image/png")
            .andExpect(status().is3xxRedirection)
            .andExpect(flash().attribute("erro", containsString("Tipo de arquivo não aceito")))
        enviar(id, "vazio.png", ByteArray(0))
            .andExpect(flash().attribute("erro", containsString("Escolha o arquivo")))
        enviar(id, "grande.png", png + ByteArray(10 * 1024 * 1024))
            .andExpect(flash().attribute("erro", containsString("o máximo é 10 MB")))

        assertThat(anexos.countByTagId(id)).isZero()
    }

    @Test
    fun `quem so ve nao envia nem exclui, e o anexo de um bem nao abre pelo outro`() {
        val id = cadastrar("E-AN3-$s")
        val outro = cadastrar("E-AN4-$s")
        enviar(id, "foto.png", png).andExpect(status().is3xxRedirection)
        val anexo = anexos.findAllByTagIdOrderByIdAsc(id).single()

        // Recusado no EnvioArquivoFilter, antes de ler o arquivo
        enviar(id, "foto.png", png, perfil = Perfis.usuario()).andExpect(status().isForbidden)
        mvc.perform(post("/admin/etiquetas/$id/anexos/${anexo.id}/excluir").with(Perfis.usuario()).with(csrf()))
            .andExpect(status().isForbidden)
        mvc.perform(get("/admin/etiquetas/$outro/anexos/${anexo.id}").with(Perfis.admin()))
            .andExpect(status().isNotFound)
        mvc.perform(post("/admin/etiquetas/$outro/anexos/${anexo.id}/excluir").with(Perfis.admin()).with(csrf()))
            .andExpect(status().isNotFound)

        assertThat(anexos.countByTagId(id)).isEqualTo(1)
    }

    @Test
    fun `excluir o anexo apaga o arquivo e guarda o nome no historico, e excluir a etiqueta apaga os anexos`() {
        val id = cadastrar("E-AN5-$s")
        enviar(id, "a.png", png).andExpect(status().is3xxRedirection)
        enviar(id, "b.pdf", pdf).andExpect(status().is3xxRedirection)
        val (a, b) = anexos.findAllByTagIdOrderByIdAsc(id)

        mvc.perform(post("/admin/etiquetas/$id/anexos/${a.id}/excluir").with(Perfis.gestor()).with(csrf()))
            .andExpect(status().is3xxRedirection)
            .andExpect(flash().attribute("msg", containsString("Anexo a.png excluído")))
        assertThat(anexos.findById(a.id)).isEmpty
        assertThat(conteudos.findById(a.id)).isEmpty
        assertThat(historico.findByTagRealOrderByMomentoDescIdDesc("E-AN5-$s").any { it.campo == "Anexo" && it.valorAntigo == "a.png (208 bytes)" && it.valorNovo == null }).isTrue()

        rfid.excluirTag(id)
        // A exclusão em cascata é do banco (on delete cascade): grava e esquece o cache do Hibernate para conferir nele
        em.flush()
        em.clear()
        assertThat(anexos.findById(b.id)).isEmpty
        assertThat(conteudos.findById(b.id)).isEmpty
    }

    @Test
    fun `limite de anexos por bem`() {
        val id = cadastrar("E-AN6-$s")
        repeat(20) { enviar(id, "f$it.png", png).andExpect(flash().attributeExists("msg")) }
        enviar(id, "f21.png", png).andExpect(flash().attribute("erro", containsString("já tem 20 anexos")))
        assertThat(anexos.countByTagId(id)).isEqualTo(20)
        // Com o máximo, a tela não mostra mais o envio
        mvc.perform(get("/admin/etiquetas/$id/editar").with(Perfis.gestor()))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("name=\"arquivo\""))))
    }
}
