package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.repository.ErroColetorRepository
import org.example.service.ErroColetorService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** POST /api/android/erros e a tela de erros do coletor. */
class ErrosColetorIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var repo: ErroColetorRepository
    @Autowired lateinit var service: ErroColetorService

    @BeforeEach
    fun limpar() = service.esquecerContagens()

    private fun erro(
        id: String = UUID.randomUUID().toString(), tipo: String = "FALHA_GERAL", mensagem: String = "NullPointerException na leitura",
        detalhe: String? = "java.lang.NullPointerException\n\tat com.example.X.y(X.kt:10)", versao: String = "1.4.0", ocorrencias: Int = 1
    ): String {
        val momento = LocalDateTime.now().minusMinutes(5).atZone(ZoneId.systemDefault()).toOffsetDateTime()
        val det = detalhe?.let { "\"" + it.replace("\n", "\\n").replace("\t", "\\t") + "\"" } ?: "null"
        return """{"id":"$id","momento":"$momento","tipo":"$tipo","mensagem":"$mensagem","detalhe":$det,
                   "ocorrencias":$ocorrencias,"versaoApp":"$versao","codigoVersao":15,"operador":"Olga"}"""
    }

    private fun enviar(vararg itens: String): ResultActions =
        mvc.perform(
            post("/api/android/erros").header(OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR)
                .contentType(MediaType.APPLICATION_JSON).content("""{"itens":[${itens.joinToString(",")}]}""")
        )

    @Test
    fun `coletor envia erros sem operador logado e eles ficam gravados`() {
        val id = UUID.randomUUID().toString()

        enviar(erro(id = id), erro(tipo = "ERRO_SERVIDOR", mensagem = "HTTP 500 ao enviar lote", detalhe = null, ocorrencias = 7))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.novos").value(2))

        val gravado = repo.findById(id).get()
        assertThat(gravado.coletor).isEqualTo("coletor-teste")
        assertThat(gravado.tipo).isEqualTo("FALHA_GERAL")
        assertThat(gravado.detalhe).contains("X.kt:10")
        assertThat(gravado.recebidoEm).isAfter(gravado.momento)
        assertThat(repo.findAll().single { it.tipo == "ERRO_SERVIDOR" }.ocorrencias).isEqualTo(7)
    }

    @Test
    fun `reenvio do mesmo erro nao duplica`() {
        val item = erro()
        enviar(item)
        enviar(item).andExpect(jsonPath("$.novos").value(0))

        assertThat(repo.count()).isEqualTo(1)
    }

    @Test
    fun `dados sensiveis sao mascarados e id invalido e ignorado`() {
        enviar(
            erro(mensagem = "falha com X-Sessao-Operador: tokenSecreto senha=Kx9mPq2v"),
            erro(id = "nao-e-uuid")
        ).andExpect(jsonPath("$.novos").value(1))

        val mensagem = repo.findAll().single().mensagem
        assertThat(mensagem).doesNotContain("tokenSecreto").doesNotContain("Kx9mPq2v")
        assertThat(mensagem).contains("X-Sessao-Operador: ***").contains("senha=***")
    }

    @Test
    fun `mais de 50 por envio e recusado`() {
        enviar(*Array(51) { erro() }).andExpect(status().isBadRequest)
        assertThat(repo.count()).isZero()
    }

    @Test
    fun `acima do limite por dia os erros sao descartados com 202`() {
        // application-test.yml: rfid.coletores.erros-por-dia = 60
        enviar(*Array(50) { erro() }).andExpect(jsonPath("$.descartados").value(0))
        enviar(*Array(20) { erro() })
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.novos").value(10))
            .andExpect(jsonPath("$.descartados").value(10))

        assertThat(repo.count()).isEqualTo(60)
    }

    @Test
    fun `envio de erros exige a chave do coletor`() {
        mvc.perform(post("/api/android/erros").contentType(MediaType.APPLICATION_JSON).content("""{"itens":[]}"""))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `tela Coletores mostra os erros das ultimas 24 h com link e a tela do coletor filtra por versao`() {
        enviar(erro(mensagem = "Erro na versao velha", versao = "1.3.0"), erro(mensagem = "Erro na versao nova", versao = "1.4.0"))

        mvc.perform(get("/admin/coletores").with(Perfis.admin()))
            .andExpect(content().string(containsString("/admin/coletores/coletor-teste/erros")))
            .andExpect(content().string(containsString("2 erro(s)")))

        mvc.perform(get("/admin/coletores/coletor-teste/erros").param("versao", "1.4.0").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Erro na versao nova")))
            .andExpect(content().string(not(containsString("Erro na versao velha"))))
            .andExpect(content().string(containsString("Travamento")))
    }

    @Test
    fun `texto do erro nao vira HTML na tela`() {
        enviar(erro(mensagem = "<script>alert(1)</script>"))

        mvc.perform(get("/admin/coletores/coletor-teste/erros").with(Perfis.admin()))
            .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
            .andExpect(content().string(containsString("&lt;script&gt;")))
    }

    @Test
    fun `tela de erros e so para ADMIN`() {
        mvc.perform(get("/admin/coletores/coletor-teste/erros").with(Perfis.gestor())).andExpect(status().isForbidden)
    }
}
