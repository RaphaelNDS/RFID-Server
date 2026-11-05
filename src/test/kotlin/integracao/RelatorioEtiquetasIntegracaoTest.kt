package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.InventarioEntity
import org.example.model.InventarioItemEntity
import org.example.model.LeituraAndroidEntity
import org.example.model.LocalEntity
import org.example.repository.InventarioItemRepository
import org.example.repository.InventarioRepository
import org.example.repository.LeituraAndroidRepository
import org.example.repository.LocalRepository
import org.example.request.CadastroRequest
import org.example.service.RelatorioEtiquetasService
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Exportação do cadastro e relatório das etiquetas não vistas há N dias. */
class RelatorioEtiquetasIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var relatorio: RelatorioEtiquetasService
    @Autowired lateinit var leituras: LeituraAndroidRepository
    @Autowired lateinit var locais: LocalRepository
    @Autowired lateinit var inventarios: InventarioRepository
    @Autowired lateinit var itens: InventarioItemRepository

    private val agora = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)

    private fun lida(tag: String, diasAtras: Long) =
        leituras.save(LeituraAndroidEntity(tag = tag, dataHora = agora.minusDays(diasAtras)))

    private fun inventariada(tag: String, local: String, diasAtras: Long) {
        val l = locais.findByNomeIgnoreCase(local).orElseGet { locais.save(LocalEntity(nome = local)) }
        val inv = inventarios.save(InventarioEntity(id = UUID.randomUUID().toString(), localId = l.id, dispositivo = "coletor-teste",
            iniciadoEm = agora.minusDays(diasAtras)))
        itens.save(InventarioItemEntity(inventarioId = inv.id, tag = tag, lidoEm = agora.minusDays(diasAtras), cadastrada = true))
    }

    @BeforeEach
    fun cadastrar() {
        listOf("E-RECENTE", "E-ANTIGA", "E-NUNCA", "E-INVENTARIO").forEachIndexed { i, epc ->
            rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-$i", patrimonio = "PAT-$i"))
        }
        lida("E-RECENTE", diasAtras = 5)
        lida("E-ANTIGA", diasAtras = 40)
        lida("E-ANTIGA", diasAtras = 90)
        lida("E-INVENTARIO", diasAtras = 60)
        inventariada("E-INVENTARIO", "Sala 1", diasAtras = 2)
        inventariada("E-ANTIGA", "Depósito", diasAtras = 100)
        inventariada("E-ANTIGA", "Sala 9", diasAtras = 45)
    }

    @Test
    fun `ultima vez vista e a mais recente entre leitura e inventario, com o ultimo local`() {
        val porTag = relatorio.etiquetasComUltimaVez(null).associateBy { it.etiqueta.tag }

        assertThat(porTag["E-INVENTARIO"]!!.ultimaVez).isEqualTo(agora.minusDays(2))
        assertThat(porTag["E-INVENTARIO"]!!.ultimoLocal).isEqualTo("Sala 1")
        assertThat(porTag["E-ANTIGA"]!!.ultimaLeitura).isEqualTo(agora.minusDays(40))
        assertThat(porTag["E-ANTIGA"]!!.ultimoLocal).isEqualTo("Sala 9") // o inventário mais recente
        assertThat(porTag["E-ANTIGA"]!!.diasSemSerVista).isEqualTo(40)
        assertThat(porTag["E-NUNCA"]!!.ultimaVez).isNull()
    }

    @Test
    fun `nao vistas ha 30 dias traz as nunca vistas primeiro e depois as vistas ha mais tempo`() {
        val tags = relatorio.naoVistasTodas(30).map { it.etiqueta.tag }

        assertThat(tags).containsExactly("E-NUNCA", "E-ANTIGA")
    }

    @Test
    fun `tela de nao vistas mostra o filtro e o resultado`() {
        mvc.perform(get("/admin/etiquetas/nao-vistas").param("dias", "30").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("E-NUNCA")))
            .andExpect(content().string(containsString("Nunca vista")))
            .andExpect(content().string(containsString("E-ANTIGA")))
            .andExpect(content().string(containsString("Sala 9")))
            .andExpect(content().string(not(containsString("E-RECENTE"))))
            .andExpect(content().string(containsString("/admin/etiquetas/nao-vistas/exportar?dias=30&amp;formato=xlsx")))
    }

    @Test
    fun `exportacao das nao vistas em CSV`() {
        mvc.perform(get("/admin/etiquetas/nao-vistas/exportar").param("dias", "30").param("formato", "csv").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Disposition", containsString("etiquetas-nao-vistas-30-dias")))
            .andExpect(content().string(containsString("E-NUNCA")))
            .andExpect(content().string(containsString("Sala 9")))
            .andExpect(content().string(not(containsString("E-RECENTE"))))
    }

    @Test
    fun `exportacao do cadastro respeita a busca e sai em Excel`() {
        val csv = mvc.perform(get("/admin/tags-cadastradas/exportar").param("busca", "pat-3").param("formato", "csv").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertThat(csv).contains("E-INVENTARIO", "Sala 1").doesNotContain("E-NUNCA")

        mvc.perform(get("/admin/tags-cadastradas/exportar").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", containsString("spreadsheetml")))
    }

    @Test
    fun `relatorio e exportacao exigem a permissao de etiquetas`() {
        mvc.perform(get("/admin/etiquetas/nao-vistas").with(Perfis.somenteCatalogo())).andExpect(status().isForbidden)
        mvc.perform(get("/admin/tags-cadastradas/exportar").with(Perfis.somenteCatalogo())).andExpect(status().isForbidden)
    }

    @Test
    fun `tela de etiquetas tem os botoes de exportar e o link das nao vistas`() {
        mvc.perform(get("/admin/tags-cadastradas").param("busca", "pat").with(Perfis.usuario()))
            .andExpect(content().string(containsString("/admin/tags-cadastradas/exportar?busca=pat&amp;formato=xlsx")))
            .andExpect(content().string(containsString("/admin/etiquetas/nao-vistas")))
    }
}
