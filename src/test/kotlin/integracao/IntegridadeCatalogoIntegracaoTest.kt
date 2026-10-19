package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.TagEntity
import org.example.repository.ModeloRepository
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

/** Etiquetas x catálogo: verificação no service + chaves estrangeiras no banco (migração V2). */
class IntegridadeCatalogoIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var modelos: ModeloRepository

    private var tipoId = 0L
    private var marcaId = 0L
    private var modeloId = 0L
    private var outroTipoId = 0L

    @BeforeEach
    fun popular() {
        tipoId = catalogo.salvarTipo(TipoEquipamentoRequest(nome = "Notebook")).id
        marcaId = catalogo.salvarMarca(MarcaRequest(nome = "Dell", tipoId = tipoId)).id
        modeloId = catalogo.salvarModelo(ModeloRequest(nome = "Latitude", marcaId = marcaId)).id
        outroTipoId = catalogo.salvarTipo(TipoEquipamentoRequest(nome = "Monitor")).id
        rfid.cadastrarTag(CadastroRequest("E1", "TI-1", "P1", "S1", tipoId, marcaId, modeloId))
    }

    @Test
    fun `excluir modelo em uso pela tela responde 409 explicando`() {
        mvc.perform(delete("/api/catalogo/modelo/$modeloId").with(Perfis.admin()).with(csrf()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("1 etiqueta(s) usam este modelo")))

        assertThat(modelos.existsById(modeloId)).isTrue()
    }

    @Test
    fun `o proprio banco impede excluir modelo em uso, mesmo pulando o service`() {
        assertThatThrownBy {
            modelos.deleteById(modeloId)
            modelos.flush()
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `o banco impede etiqueta apontando para modelo inexistente`() {
        assertThatThrownBy {
            tags.saveAndFlush(TagEntity(tagHash = "h-x", tagReal = "E-X", modeloId = 999_999))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `modelo sem etiquetas continua podendo ser excluido`() {
        val livre = catalogo.salvarModelo(ModeloRequest(nome = "Vostro", marcaId = marcaId)).id

        mvc.perform(delete("/api/catalogo/modelo/$livre").with(Perfis.admin()).with(csrf()))
            .andExpect(status().isOk)

        assertThat(modelos.existsById(livre)).isFalse()
    }

    @Test
    fun `cadastro com marca de outro tipo volta ao formulario com a mensagem`() {
        mvc.perform(post("/admin/cadastro/salvar").with(Perfis.admin()).with(csrf())
            .param("tag", "E2").param("codigoInterno", "TI-2")
            .param("tipoEquipamentoId", outroTipoId.toString()).param("marcaId", marcaId.toString()))
            .andExpect(status().isOk)
            .andExpect(view().name("cadastro"))
            .andExpect(model().attribute("erro", "A marca Dell não pertence ao tipo de equipamento Monitor"))

        assertThat(tags.existsByTagReal("E2")).isFalse()
    }

    @Test
    fun `listagem mostra os nomes do catalogo`() {
        val etiqueta = rfid.listarParaAdmin().single { it.tag == "E1" }

        assertThat(etiqueta.tipoNome).isEqualTo("Notebook")
        assertThat(etiqueta.marcaNome).isEqualTo("Dell")
        assertThat(etiqueta.modeloNome).isEqualTo("Latitude")
    }
}
