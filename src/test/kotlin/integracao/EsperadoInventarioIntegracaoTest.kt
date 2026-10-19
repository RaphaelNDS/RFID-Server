package org.example.integracao

import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.comoOperador
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** GET /api/android/inventarios/esperado: o que foi encontrado no último inventário finalizado do local. */
class EsperadoInventarioIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfidService: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var operadores: OperadorTeste

    private val token by lazy { operadores.sessaoPadrao(mvc) }

    // Local único por teste: o banco é compartilhado entre as classes de teste
    private val local = "Sala ${UUID.randomUUID().toString().take(8)}"

    private fun coletor(url: String, json: String) =
        mvc.perform(post(url).comoOperador(token).contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isOk)

    /** Abre, lê [tags] e finaliza um inventário no [noLocal], finalizado na hora informada. */
    private fun inventarioFinalizado(noLocal: String, finalizadoEm: String, vararg tags: String): String {
        val id = UUID.randomUUID().toString()
        coletor("/api/android/inventarios", """{"id":"$id","local":"$noLocal","iniciadoEm":"2026-10-01T09:00:00-03:00"}""")
        coletor("/api/android/inventarios/$id/itens", """{"itens":[${tags.joinToString { """{"tag":"$it"}""" }}]}""")
        coletor("/api/android/inventarios/$id/finalizar", """{"finalizadoEm":"$finalizadoEm"}""")
        return id
    }

    private fun esperado(noLocal: String): ResultActions =
        mvc.perform(get("/api/android/inventarios/esperado").param("local", noLocal).comoOperador(token))

    @Test
    fun `local sem inventario finalizado nao tem lista e diz isso explicitamente`() {
        esperado(local)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.temReferencia").value(false))
            .andExpect(jsonPath("$.itens.length()").value(0))

        // Inventário só aberto (não finalizado) também não serve de referência
        val id = UUID.randomUUID().toString()
        coletor("/api/android/inventarios", """{"id":"$id","local":"$local"}""")
        esperado(local).andExpect(jsonPath("$.temReferencia").value(false))
    }

    @Test
    fun `esperado sao as cadastradas do ultimo inventario finalizado com quem fez e quando`() {
        rfidService.cadastrarTag(CadastroRequest(tag = "E-ESP-1", codigoInterno = "TI-1", patrimonio = "PAT-B"))
        rfidService.cadastrarTag(CadastroRequest(tag = "E-ESP-2", codigoInterno = "TI-2", patrimonio = "PAT-A"))
        val id = inventarioFinalizado(local, "2026-10-10T10:30:00-03:00", "E-ESP-1", "E-ESP-2", "E-DESCONHECIDA")

        // Mesma regra de grafia do inventário: espaços extras e maiúsculas não importam
        esperado("  ${local.uppercase()}  ")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.temReferencia").value(true))
            .andExpect(jsonPath("$.local").value(local))
            .andExpect(jsonPath("$.inventarioReferencia").value(id))
            .andExpect(jsonPath("$.operador").value("Olga Operadora"))
            .andExpect(jsonPath("$.finalizadoEm").value(containsString("2026-10-10")))
            .andExpect(jsonPath("$.itens.length()").value(2))   // a não cadastrada fica de fora
            .andExpect(jsonPath("$.itens[0].patrimonio").value("PAT-A"))   // ordenado por patrimônio
            .andExpect(jsonPath("$.itens[0].tag").value("E-ESP-2"))
            .andExpect(jsonPath("$.itens[1].patrimonio").value("PAT-B"))
    }

    @Test
    fun `vale o inventario finalizado mais recente do local`() {
        rfidService.cadastrarTag(CadastroRequest(tag = "E-VELHA", codigoInterno = "TI-1", patrimonio = "PAT-1"))
        rfidService.cadastrarTag(CadastroRequest(tag = "E-RECENTE", codigoInterno = "TI-2", patrimonio = "PAT-2"))
        inventarioFinalizado(local, "2026-10-01T10:00:00-03:00", "E-VELHA")
        val recente = inventarioFinalizado(local, "2026-10-12T10:00:00-03:00", "E-RECENTE")

        esperado(local)
            .andExpect(jsonPath("$.inventarioReferencia").value(recente))
            .andExpect(jsonPath("$.itens.length()").value(1))
            .andExpect(jsonPath("$.itens[0].tag").value("E-RECENTE"))
    }

    @Test
    fun `usa o cadastro de agora e deixa de fora a etiqueta excluida depois`() {
        rfidService.cadastrarTag(CadastroRequest(tag = "E-MUDOU", codigoInterno = "TI-1", patrimonio = "PAT-ANTIGO"))
        rfidService.cadastrarTag(CadastroRequest(tag = "E-EXCLUIDA", codigoInterno = "TI-2", patrimonio = "PAT-X"))
        inventarioFinalizado(local, "2026-10-10T10:00:00-03:00", "E-MUDOU", "E-EXCLUIDA")

        tags.findByTagReal("E-MUDOU").get().apply { patrimonio = "PAT-NOVO" }.also { tags.save(it) }
        tags.delete(tags.findByTagReal("E-EXCLUIDA").get())

        esperado(local)
            .andExpect(jsonPath("$.itens.length()").value(1))
            .andExpect(jsonPath("$.itens[0].tag").value("E-MUDOU"))
            .andExpect(jsonPath("$.itens[0].patrimonio").value("PAT-NOVO"))
            .andExpect(jsonPath("$.itens[0].tipo").value(nullValue()))
    }

    @Test
    fun `local em branco e recusado`() {
        esperado("   ").andExpect(status().isBadRequest)
    }

    @Test
    fun `esperado exige operador logado`() {
        mvc.perform(get("/api/android/inventarios/esperado").param("local", local).header(OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR))
            .andExpect(status().isUnauthorized)
    }
}
