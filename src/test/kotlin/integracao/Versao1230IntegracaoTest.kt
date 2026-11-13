package org.example.integracao

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.request.CadastroRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.DadosCliente
import org.example.service.DadosPedido
import org.example.service.EntregaService
import org.example.service.FotoEnviada
import org.example.service.LocacaoService
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

/** 1.23.0: comprovante de entrega com assinatura e fotos. */
class Versao1230IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var locacao: LocacaoService
    @Autowired lateinit var entregas: EntregaService

    private val s = UUID.randomUUID().toString().take(6).uppercase()

    private fun imagem(formato: String): ByteArray {
        val img = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { drawLine(2, 2, 38, 18); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(img, formato, it) }.toByteArray()
    }

    private val assinatura = EntregaService.PREFIXO_PNG + Base64.getEncoder().encodeToString(imagem("png"))

    /** Pedido com 2 equipamentos na rua. */
    private fun pedidoNaRua(): Long {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Impressora $s")).id
        listOf("EN1-$s", "EN2-$s").forEach {
            rfid.cadastrarTag(CadastroRequest(tag = it, codigoInterno = "TI-$it", patrimonio = "P-$it", tipoEquipamentoId = tipo))
        }
        val c = locacao.salvarCliente(null, DadosCliente("Evento $s"))
        val p = locacao.criarPedido(DadosPedido(c.id, LocalDate.now().plusDays(3)))
        locacao.adicionarLinha(p.id, tipo, null, null, 2, null)
        return p.id.also { assertThat(locacao.registrarSaida(it, listOf("EN1-$s", "EN2-$s")).aceitos).hasSize(2) }
    }

    @Test
    fun `registra com assinatura e fotos e recusa o que nao vale`() {
        val c = locacao.salvarCliente(null, DadosCliente("Vazio $s"))
        val vazio = locacao.criarPedido(DadosPedido(c.id, LocalDate.now().plusDays(3)))
        assertThatThrownBy { entregas.registrar(vazio.id, "Ana", null, null, assinatura, emptyList()) }.hasMessageContaining("registre a saída")

        val p = pedidoNaRua()
        assertThatThrownBy { entregas.registrar(p, " ", null, null, assinatura, emptyList()) }.hasMessageContaining("quem recebeu")
        assertThatThrownBy { entregas.registrar(p, "Ana", null, null, null, emptyList()) }.hasMessageContaining("Falta a assinatura")
        val jpegComoPng = EntregaService.PREFIXO_PNG + Base64.getEncoder().encodeToString(imagem("jpg"))
        assertThatThrownBy { entregas.registrar(p, "Ana", null, null, jpegComoPng, emptyList()) }.hasMessageContaining("Assinatura inválida")
        assertThatThrownBy { entregas.registrar(p, "Ana", null, null, assinatura, listOf(FotoEnviada("nota.pdf", "%PDF-1.4 x".toByteArray()))) }
            .hasMessageContaining("não é JPEG")
        assertThatThrownBy { entregas.registrar(p, "Ana", null, null, assinatura, (1..6).map { FotoEnviada("f$it.jpg", imagem("jpg")) }) }
            .hasMessageContaining("No máximo 5")

        val e = entregas.registrar(p, "Ana Souza", "RG 12.345", "Portaria", assinatura, listOf(FotoEnviada("a.jpg", imagem("jpg")), FotoEnviada("b.png", imagem("png"))))
        assertThat(e.listaEquipamentos).hasSize(2).allMatch { it.startsWith("P-EN") }
        val resumo = entregas.doPedido(p).single()
        assertThat(resumo.fotos).hasSize(2)
        assertThat(entregas.foto(p, e.id, resumo.fotos.first()).conteudo).isNotEmpty()
        assertThatThrownBy { entregas.entrega(vazio.id, e.id) }.isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `tela, envio com fotos, PDF e imagens`() {
        val p = pedidoNaRua()
        mvc.perform(get("/admin/pedidos/$p").with(Perfis.admin()))
            .andExpect(content().string(containsString("Registrar entrega (assinatura e fotos)")))
        mvc.perform(get("/admin/pedidos/$p/entregas/nova").with(Perfis.admin()))
            .andExpect(content().string(containsString("data-assinatura")))
            .andExpect(content().string(containsString("P-EN1-$s")))

        // Sem assinatura: a tela volta com o erro e o nome digitado
        mvc.perform(multipart("/admin/pedidos/$p/entregas").with(Perfis.admin()).with(csrf()).param("recebidoPor", "Bruno"))
            .andExpect(content().string(containsString("Falta a assinatura")))
            .andExpect(content().string(containsString("value=\"Bruno\"")))

        mvc.perform(multipart("/admin/pedidos/$p/entregas").file(MockMultipartFile("fotos", "local.jpg", "image/jpeg", imagem("jpg")))
            .with(Perfis.admin()).with(csrf()).param("recebidoPor", "Bruno Lima").param("assinatura", assinatura))
            .andExpect(flash().attribute("msg", containsString("recebido por Bruno Lima")))
        val e = entregas.doPedido(p).single()

        mvc.perform(get("/admin/pedidos/$p").with(Perfis.usuario()))
            .andExpect(content().string(containsString("recebido por Bruno Lima")))
        mvc.perform(get("/admin/pedidos/$p/entregas/${e.entrega.id}/assinatura.png").with(Perfis.usuario()))
            .andExpect(status().isOk).andExpect(content().contentType("image/png"))
        mvc.perform(get("/admin/pedidos/$p/entregas/${e.entrega.id}/fotos/${e.fotos.single()}").with(Perfis.usuario()))
            .andExpect(status().isOk).andExpect(content().contentType("image/jpeg"))
        val pdf = mvc.perform(get("/admin/pedidos/$p/entregas/${e.entrega.id}/comprovante.pdf").with(Perfis.usuario()))
            .andReturn().response.contentAsByteArray
        val texto = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }
        assertThat(texto).contains("Comprovante de entrega").contains("Bruno Lima").contains("P-EN2-$s").contains("Fotos no local (1)")
    }

    @Test
    fun `envio de fotos so para quem edita na locacao`() {
        val p = pedidoNaRua()
        val soVer = Perfis.logado(80, "Vera Ver", "vera@x.com", "CUSTOM", listOf("LOCACAO_READ"))
        mvc.perform(multipart("/admin/pedidos/$p/entregas").file(MockMultipartFile("fotos", "x.jpg", "image/jpeg", imagem("jpg")))
            .with(soVer).with(csrf()).param("recebidoPor", "X").param("assinatura", assinatura))
            .andExpect(status().isForbidden)
        val expedicao = Perfis.logado(81, "Edu Expedição", "edu@x.com", "EXPEDICAO", listOf("LOCACAO_READ", "LOCACAO_CREATE", "LOCACAO_UPDATE"))
        mvc.perform(multipart("/admin/pedidos/$p/entregas").file(MockMultipartFile("fotos", "x.jpg", "image/jpeg", imagem("jpg")))
            .with(expedicao).with(csrf()).param("recebidoPor", "Carla").param("assinatura", assinatura))
            .andExpect(flash().attribute("msg", containsString("Carla")))
        assertThat(entregas.doPedido(p)).hasSize(1)
    }
}
