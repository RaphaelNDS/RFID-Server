package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.Acao
import org.example.model.Modulo
import org.example.model.SituacaoBem
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.example.suporte.comoOperador
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * 2.1.0: o sistema simples sem as Movimentações. As rotas web e do coletor saíram, a ficha e o login do coletor não
 * têm mais os campos delas, a situação "Fora do estoque" não existe mais, e a tela Início mostra o resumo novo, sem
 * nenhum link quebrado no menu.
 */
class Versao210IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var operadores: OperadorTeste

    private val s = UUID.randomUUID().toString().take(6).uppercase()

    @Test
    fun `rotas das movimentacoes sairam, na web e no coletor`() {
        listOf("/admin/movimentos", "/admin/movimentos/saida", "/admin/movimentos/entrada", "/admin/movimentos/1",
            "/admin/movimentos/1/comprovante.pdf").forEach { url ->
            mvc.perform(get(url).with(Perfis.admin())).andExpect(status().isNotFound)
        }
        mvc.perform(post("/admin/movimentos/saida").with(Perfis.admin()).with(csrf()).param("lista", "X").param("destino", "Y"))
            .andExpect(status().isNotFound)

        val email = "v210-$s@x.com".lowercase()
        operadores.criarUsuario(email = email, permissoes = arrayOf(Modulo.LEITURA to Acao.READ, Modulo.RFID to Acao.CREATE))
        val token = operadores.entrar(mvc, email)
        mvc.perform(post("/api/android/movimentos").comoOperador(token).contentType(MediaType.APPLICATION_JSON)
            .content("""{"tipo":"SAIDA","destino":"Obra","epcs":["X"]}""")).andExpect(status().isNotFound)
    }

    @Test
    fun `login e ficha do coletor sem os campos das movimentacoes`() {
        val email = "v210-login-$s@x.com".lowercase()
        operadores.criarUsuario(email = email, permissoes = arrayOf(Modulo.LEITURA to Acao.READ, Modulo.RFID to Acao.READ))
        mvc.perform(post("/api/android/login").header(OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR)
            .contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","senha":"${OperadorTeste.SENHA}"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.podeLer").value(true))
            .andExpect(jsonPath("$.podeMovimentar").doesNotExist())

        rfid.cadastrarTag(CadastroRequest(tag = "E-F-$s", codigoInterno = "TI-$s", patrimonio = "P-$s"))
        val token = operadores.entrar(mvc, email)
        mvc.perform(get("/api/android/tags/E-F-$s").comoOperador(token)).andExpect(status().isOk)
            .andExpect(jsonPath("$.situacao").value("DISPONIVEL"))
            .andExpect(jsonPath("$.situacaoRotulo").value("No estoque"))
            .andExpect(jsonPath("$.destino").doesNotExist())
            .andExpect(jsonPath("$.saidaEm").doesNotExist())
    }

    @Test
    fun `fora do estoque nao e mais uma situacao, e a planilha antiga com ela volta para o estoque`() {
        assertThat(SituacaoBem.entries).containsExactly(SituacaoBem.DISPONIVEL, SituacaoBem.EXTRAVIADO, SituacaoBem.BAIXADO)

        rfid.cadastrarTag(CadastroRequest(tag = "E-X-$s", codigoInterno = "TI-X-$s", situacao = "EXTRAVIADO"))
        val tag = tags.findByTagReal("E-X-$s").get()
        assertThat(tag.situacao).isEqualTo(SituacaoBem.EXTRAVIADO)

        // Edição: o "Fora do estoque" de uma tela ou planilha antiga vira "No estoque"
        mvc.perform(post("/admin/etiquetas/${tag.id}/editar").with(Perfis.gestor()).with(csrf())
            .param("tag", "E-X-$s").param("codigoInterno", "TI-X-$s").param("situacao", "FORA"))
            .andExpect(status().is3xxRedirection)
        assertThat(tags.findByTagReal("E-X-$s").get().situacao).isEqualTo(SituacaoBem.DISPONIVEL)

        // A edição não fala mais em movimentações
        mvc.perform(get("/admin/etiquetas/${tag.id}/editar").with(Perfis.gestor())).andExpect(status().isOk)
            .andExpect(content().string(not(containsString("Movimenta"))))
            .andExpect(content().string(not(containsString("/admin/movimentos"))))
            .andExpect(content().string(not(containsString("Fora do estoque"))))
    }

    @Test
    fun `inicio mostra o resumo novo e todo link do menu e dos atalhos abre`() {
        rfid.cadastrarTag(CadastroRequest(tag = "E-D-$s", codigoInterno = "TI-D-$s"))

        val html = mvc.perform(get("/dashboard").with(Perfis.admin())).andExpect(status().isOk)
            .andExpect(content().string(containsString("Pendentes")))
            .andExpect(content().string(containsString("Coletores ativos")))
            .andExpect(content().string(containsString("Cadastradas")))
            .andExpect(content().string(not(containsString("Movimenta"))))
            .andExpect(content().string(not(containsString("/admin/movimentos"))))
            .andExpect(content().string(not(containsString("Fora do estoque"))))
            .andReturn().response.contentAsString

        // Usuário comum: sem o card dos coletores (a tela Coletores é só do ADMIN)
        mvc.perform(get("/dashboard").with(Perfis.usuario())).andExpect(status().isOk)
            .andExpect(content().string(containsString("Pendentes")))
            .andExpect(content().string(not(containsString("Coletores ativos"))))

        val links = Regex("""href="(/[^"#?]*)""").findAll(html).map { it.groupValues[1] }
            .filter { !it.startsWith("/img/") && !it.startsWith("/css/") && !it.startsWith("/js/") }
            .toSet()
        assertThat(links).contains("/admin/tags-cadastradas", "/admin/tags-pendentes", "/admin/cadastro", "/admin/leituras-android",
            "/admin/inventarios", "/admin/locais", "/admin/catalogo", "/admin/dashboard", "/admin/coletores", "/admin/app",
            "/admin/usuarios", "/admin/auditoria", "/status")
        links.forEach { url ->
            val resposta = mvc.perform(get(url).with(Perfis.admin())).andReturn().response
            assertThat(resposta.status).describedAs("link $url do Início").isIn(200, 302)
        }
    }
}
