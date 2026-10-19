package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.StatusInventario
import org.example.repository.InventarioItemRepository
import org.example.repository.InventarioRepository
import org.example.repository.LocalRepository
import org.example.request.CadastroRequest
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.comoOperador
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** Inventário por local: API do coletor (com a chave) e telas de acompanhamento. */
class InventarioIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfidService: RfidService
    @Autowired lateinit var inventarios: InventarioRepository
    @Autowired lateinit var itens: InventarioItemRepository
    @Autowired lateinit var locais: LocalRepository
    @Autowired lateinit var operadores: OperadorTeste

    private val token by lazy { operadores.sessaoPadrao(mvc) }

    private fun coletor(url: String, json: String? = null): ResultActions {
        val req = post(url).comoOperador(token)
        if (json != null) req.contentType(MediaType.APPLICATION_JSON).content(json)
        return mvc.perform(req)
    }

    private fun abrir(id: String = UUID.randomUUID().toString(), local: String = "Sala 101", iniciadoEm: String? = null): String {
        val hora = iniciadoEm?.let { ""","iniciadoEm":"$it"""" } ?: ""
        coletor("/api/android/inventarios", """{"id":"$id","local":"$local"$hora}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.status").value("ABERTO"))
        return id
    }

    private fun enviarItens(id: String, vararg tags: String): ResultActions =
        coletor("/api/android/inventarios/$id/itens", """{"itens":[${tags.joinToString { """{"tag":"$it"}""" }}]}""")

    @Test
    fun `fluxo completo abre le e finaliza registrando quem fez e o que foi lido`() {
        rfidService.cadastrarTag(CadastroRequest(tag = "E-CAD", codigoInterno = "TI-1", patrimonio = "PAT-1"))

        val id = abrir(local = "Sala 101", iniciadoEm = "2026-10-13T10:00:00-03:00")
        enviarItens(id, "E-CAD", "E-NOVA")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.recebidos").value(2))
            .andExpect(jsonPath("$.novos").value(2))
        coletor("/api/android/inventarios/$id/finalizar", """{"finalizadoEm":"2026-10-13T10:30:00-03:00"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("FINALIZADO"))

        val inventario = inventarios.findById(id).get()
        assertThat(inventario.dispositivo).isEqualTo("coletor-teste")
        assertThat(inventario.operador).isEqualTo("Olga Operadora")
        assertThat(itens.findByInventarioIdOrderByLidoEmAsc(id).map { it.operador }).containsOnly("Olga Operadora")
        assertThat(inventario.status).isEqualTo(StatusInventario.FINALIZADO)
        // Hora do coletor convertida para o fuso do servidor
        val esperado = java.time.OffsetDateTime.parse("2026-10-13T10:00:00-03:00").atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        assertThat(inventario.iniciadoEm).isEqualTo(esperado)

        val lidos = itens.findByInventarioIdOrderByLidoEmAsc(id).associateBy { it.tag }
        assertThat(lidos.getValue("E-CAD").cadastrada).isTrue()
        assertThat(lidos.getValue("E-CAD").patrimonio).isEqualTo("PAT-1")
        assertThat(lidos.getValue("E-NOVA").cadastrada).isFalse()
    }

    @Test
    fun `reenviar abertura itens e finalizacao nao duplica nada`() {
        val id = abrir()
        abrir(id) // o coletor não recebeu a resposta e reenviou

        enviarItens(id, "E1", "E2")
        enviarItens(id, "E2", "E3", "E3")
            .andExpect(jsonPath("$.recebidos").value(3))
            .andExpect(jsonPath("$.novos").value(1))

        coletor("/api/android/inventarios/$id/finalizar").andExpect(status().isOk)
        coletor("/api/android/inventarios/$id/finalizar").andExpect(status().isOk)

        assertThat(inventarios.count()).isEqualTo(1)
        assertThat(itens.tagsDoInventario(id)).containsExactlyInAnyOrder("E1", "E2", "E3")
    }

    @Test
    fun `mesmo local com grafia diferente vira um so e aparece na lista de locais`() {
        abrir(local = "Sala 101")
        abrir(local = "  sala   101 ")
        abrir(local = "Almoxarifado")

        assertThat(locais.count()).isEqualTo(2)
        mvc.perform(get("/api/android/locais").comoOperador(token))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0]").value("Almoxarifado"))
            .andExpect(jsonPath("$[1]").value("Sala 101"))
    }

    @Test
    fun `data no futuro usa a hora do recebimento`() {
        val amanha = LocalDateTime.now().plusDays(1).atZone(ZoneId.systemDefault()).toOffsetDateTime()

        val id = abrir(iniciadoEm = amanha.toString())

        assertThat(inventarios.findById(id).get().iniciadoEm).isBefore(LocalDateTime.now().plusMinutes(1))
    }

    @Test
    fun `dados invalidos sao recusados com mensagem`() {
        coletor("/api/android/inventarios", """{"id":"nao-e-uuid","local":"Sala"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.mensagem").value(containsString("UUID")))

        coletor("/api/android/inventarios", """{"id":"${UUID.randomUUID()}","local":"   "}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.mensagem").value("Informe o local do inventário"))

        enviarItens(UUID.randomUUID().toString(), "E1").andExpect(status().isNotFound)
    }

    @Test
    fun `api do inventario exige a chave do coletor`() {
        mvc.perform(post("/api/android/inventarios").contentType(MediaType.APPLICATION_JSON)
            .content("""{"id":"${UUID.randomUUID()}","local":"Sala"}"""))
            .andExpect(status().isUnauthorized)

        mvc.perform(get("/api/android/locais")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `tela lista os inventarios e mostra o detalhe com as etiquetas`() {
        rfidService.cadastrarTag(CadastroRequest(tag = "E-CAD", codigoInterno = "TI-1", patrimonio = "PAT-77"))
        val id = abrir(local = "Almoxarifado")
        enviarItens(id, "E-CAD", "E-NOVA")

        mvc.perform(get("/admin/inventarios").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(view().name("inventarios"))
            .andExpect(content().string(containsString("Almoxarifado")))
            .andExpect(content().string(containsString("Em andamento")))

        mvc.perform(get("/admin/inventarios/$id").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("PAT-77")))
            .andExpect(content().string(containsString("E-NOVA")))
            .andExpect(content().string(containsString("Não cadastrada")))
    }

    @Test
    fun `tela de inventarios exige a permissao de leituras`() {
        mvc.perform(get("/admin/inventarios").with(Perfis.somenteCatalogo()))
            .andExpect(status().isForbidden)

        mvc.perform(get("/dashboard").with(Perfis.somenteCatalogo()))
            .andExpect(content().string(not(containsString("href=\"/admin/inventarios\""))))
    }
}
