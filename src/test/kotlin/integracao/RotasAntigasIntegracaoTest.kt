package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.controller.RotasAntigasColetorController
import org.example.repository.LeituraAndroidRepository
import org.example.repository.TagNaoCadastradaRepository
import org.example.repository.TagRepository
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

/** As rotas antigas dos coletores foram removidas: respondem 410 explicando e não fazem mais nada. */
class RotasAntigasIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var pendentes: TagNaoCadastradaRepository
    @Autowired lateinit var leituras: LeituraAndroidRepository

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "GET,  /api/tags/ping",
        "GET,  /api/tags/E2801170AAAA",
        "GET,  /api/tags/patrimonio/PAT-101",
        "POST, /api/tags/cadastrar",
        "POST, /api/rfid/cadastrar",
        "POST, /api/rfid/ler",
        "POST, /api/rfid/naocadastrada"
    )
    fun `app antigo recebe 410 com a explicacao, sem login e sem redirecionar`(metodo: String, rota: String) {
        mvc.perform(request(HttpMethod.valueOf(metodo), rota)
            .contentType(MediaType.APPLICATION_JSON).content("""{"tag":"E2801170AAAA"}"""))
            .andExpect(status().isGone)
            .andExpect(header().doesNotExist("Location"))
            .andExpect(jsonPath("$.status").value(410))
            .andExpect(jsonPath("$.mensagem").value(RotasAntigasColetorController.MENSAGEM))
            .andExpect(jsonPath("$.caminho").value(rota))
    }

    @Test
    fun `rotas antigas nao gravam nada, mesmo com usuario logado`() {
        mvc.perform(post("/api/tags/cadastrar").with(Perfis.admin())
            .contentType(MediaType.APPLICATION_JSON).content("""{"tag":"E-ANTIGA","codigoInterno":"TI-1"}"""))
            .andExpect(status().isGone)
        mvc.perform(post("/api/rfid/naocadastrada").with(Perfis.admin())
            .contentType(MediaType.APPLICATION_JSON).content("""{"tag":"E-ANTIGA"}"""))
            .andExpect(status().isGone)

        assertThat(tags.existsByTagReal("E-ANTIGA")).isFalse()
        assertThat(pendentes.existsByTag("E-ANTIGA")).isFalse()
    }

    @Test
    fun `leitura pela rota antiga dentro do admin nao existe mais`() {
        mvc.perform(post("/admin/leituras-android/api/android/leitura").with(Perfis.admin()).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("""{"tag":"E1"}"""))
            .andExpect(status().is4xxClientError)

        assertThat(leituras.count()).isZero()
    }

    @Test
    fun `a API atual dos coletores continua exigindo a chave`() {
        mvc.perform(request(HttpMethod.GET, "/api/android/ping"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.mensagem").value(containsString("Chave")))
    }
}
