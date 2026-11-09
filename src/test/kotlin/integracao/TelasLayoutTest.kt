package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.Acao
import org.example.model.Modulo
import org.example.model.Role
import org.example.repository.UserRepository
import org.example.request.CadastroUsuarioRequest
import org.example.service.UsuarioService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

/**
 * Toda tela abre com o layout inteiro (cabeçalho, conteúdo e rodapé). Um template com o começo estragado
 * (ex.: "d!DOCTYPE" no lugar de "<!DOCTYPE") ainda responde 200, mas a página sai sem o layout: só um teste
 * que olha o HTML pega isso.
 */
class TelasLayoutTest : IntegracaoBase() {

    @Autowired lateinit var usuarios: UsuarioService
    @Autowired lateinit var repoUsuarios: UserRepository

    @Test
    fun `telas sem login abrem com o layout`() {
        conferir("/setup", mvc.perform(get("/setup")).andReturn().response)
        usuarios.cadastrar(CadastroUsuarioRequest("Ana Administradora", "ana@empresa.com.br", "Kx9mPq2v", Role.ADMIN))
        listOf("/login", "/esqueci-senha").forEach { conferir(it, mvc.perform(get(it)).andReturn().response) }
    }

    @Test
    fun `telas logadas abrem com o layout, cabecalho e rodape`() {
        usuarios.cadastrar(CadastroUsuarioRequest("Ana Administradora", "ana@empresa.com.br", "Kx9mPq2v", Role.ADMIN))
        usuarios.cadastrar(CadastroUsuarioRequest("Olga Operadora", "olga@empresa.com.br", "Kx9mPq2v", Role.USER))
        val ana = repoUsuarios.findByEmail("ana@empresa.com.br")!!
        val olga = repoUsuarios.findByEmail("olga@empresa.com.br")!!
        val admin = Perfis.logado(ana.id!!, ana.nome, ana.email, "ADMIN",
            Modulo.values().flatMap { m -> Acao.values().map { "${m}_$it" } })

        listOf(
            "/dashboard", "/admin", "/admin/tags-cadastradas", "/admin/tags-pendentes", "/admin/cadastro",
            "/admin/etiquetas/historico", "/admin/etiquetas/nao-vistas", "/admin/leituras-android",
            "/admin/inventarios", "/admin/catalogo", "/admin/dashboard", "/admin/usuarios", "/admin/usuarios/novo",
            "/admin/usuarios/${olga.id}/editar", "/admin/coletores", "/admin/app", "/admin/auditoria", "/status",
            "/perfil", "/perfil/senha", "/perfil/email",
            // 1.6.0: gestão de patrimônio
            "/admin/locais", "/admin/responsaveis", "/admin/tags-cadastradas/importar",
            // 1.6.1: transferências
            "/admin/transferencias", "/admin/transferencias/nova"
        ).forEach { url ->
            val resposta = mvc.perform(get(url).with(admin)).andReturn().response
            conferir(url, resposta)
            val html = resposta.contentAsString
            assertThat(html).describedAs("cabeçalho em $url").contains("class=\"topbar\"")
            assertThat(html).describedAs("rodapé em $url").contains("</footer>")
        }
    }

    private fun conferir(url: String, resposta: org.springframework.mock.web.MockHttpServletResponse) {
        assertThat(resposta.status).describedAs("status de $url").isEqualTo(200)
        val html = resposta.contentAsString.trim()
        assertThat(html).describedAs("começo do HTML de $url").startsWith("<!DOCTYPE html>")
        assertThat(html).describedAs("folha de estilo em $url").contains("/css/app.css")
        assertThat(html).describedAs("fim do HTML de $url").endsWith("</html>")

        // CSP sem 'unsafe-inline': todo <script> embutido leva o nonce desta resposta e nenhum onclick="..." sobrou
        // (sem o nonce o navegador não roda o script e a tela para de funcionar sem erro visível)
        val politica = resposta.getHeader("Content-Security-Policy").orEmpty()
        val scripts = politica.split(';').map { it.trim() }.first { it.startsWith("script-src") }
        assertThat(scripts).describedAs("script-src de $url").doesNotContain("unsafe-inline")
        val nonce = Regex("'nonce-([^']+)'").find(politica)?.groupValues?.get(1)
        assertThat(nonce).describedAs("nonce na CSP de $url").isNotBlank()
        Regex("<script(?![^>]*\\ssrc=)[^>]*>").findAll(html).forEach { tag ->
            assertThat(tag.value).describedAs("script embutido em $url").contains("nonce=\"$nonce\"")
        }
        assertThat(Regex("\\son(click|change|submit|input|load|keydown|keyup|mouseover)=\"").find(html)?.value)
            .describedAs("atributo on...= em $url").isNull()
    }
}
