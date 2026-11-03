package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.Acao
import org.example.model.Modulo
import org.example.model.Role
import org.example.repository.UserRepository
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.example.suporte.comoOperador
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

/** Desativar e reativar usuário: o login (web e coletor) e as sessões já abertas param na hora. */
class UsuarioDesativadoIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var operadores: OperadorTeste
    @Autowired lateinit var usuarios: UserRepository

    // O bloqueio de login é em memória e compartilhado entre testes: cada teste usa email e IP próprios
    private val ip = "10.66.${(1..250).random()}.${(1..250).random()}"

    private fun entrarNaWeb(email: String): MockHttpSession =
        mvc.perform(post("/login").with(csrf()).with { it.remoteAddr = ip; it }
            .param("username", email).param("password", OperadorTeste.SENHA))
            .andExpect(redirectedUrl("/redirect"))
            .andReturn().request.getSession(false) as MockHttpSession

    private fun loginColetor(email: String) =
        mvc.perform(post("/api/android/login").header(OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR)
            .contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","senha":"${OperadorTeste.SENHA}"}""")
            .with { it.remoteAddr = ip; it })

    private fun idDe(email: String) = usuarios.findByEmail(email)!!.id!!

    @Test
    fun `desativado nao entra na web nem no coletor e reativado volta a entrar`() {
        operadores.criarUsuario(email = "desativa1@x.com")

        mvc.perform(post("/admin/usuarios/${idDe("desativa1@x.com")}/desativar").with(Perfis.admin()).with(csrf()))
            .andExpect(redirectedUrl("/admin/usuarios"))
            .andExpect(flash().attribute("msg", containsString("desativado")))

        // Web: a mesma mensagem de senha errada (não revela que o usuário existe)
        mvc.perform(post("/login").with(csrf()).with { it.remoteAddr = ip; it }
            .param("username", "desativa1@x.com").param("password", OperadorTeste.SENHA))
            .andExpect(redirectedUrl("/login?error"))
        // Coletor: com a senha certa, o motivo de verdade
        loginColetor("desativa1@x.com")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.mensagem").value("Seu usuário foi desativado. Fale com o administrador."))

        mvc.perform(post("/admin/usuarios/${idDe("desativa1@x.com")}/reativar").with(Perfis.admin()).with(csrf()))
            .andExpect(redirectedUrl("/admin/usuarios"))
        loginColetor("desativa1@x.com").andExpect(status().isOk)
    }

    @Test
    fun `desativar derruba na hora a sessao web e a do coletor que estavam abertas`() {
        operadores.criarUsuario(email = "desativa2@x.com", permissoes = arrayOf(Modulo.LEITURA to Acao.READ, Modulo.DASHBOARD to Acao.READ))
        val sessaoWeb = entrarNaWeb("desativa2@x.com")
        val token = operadores.entrar(mvc, "desativa2@x.com")
        mvc.perform(get("/dashboard").session(sessaoWeb)).andExpect(status().isOk)

        mvc.perform(post("/admin/usuarios/${idDe("desativa2@x.com")}/desativar").with(Perfis.admin()).with(csrf()))
            .andExpect(redirectedUrl("/admin/usuarios"))

        // A sessão web aberta antes cai na próxima tela, com aviso no login
        mvc.perform(get("/dashboard").session(sessaoWeb)).andExpect(redirectedUrl("/login?encerrada"))
        // O token do coletor também deixa de valer
        mvc.perform(post("/api/android/naocadastrada").comoOperador(token)
            .contentType(MediaType.APPLICATION_JSON).content("""{"tag":"E-DESATIVADO"}"""))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `admin edita o perfil e as permissoes pela tela`() {
        operadores.criarUsuario(email = "edita@x.com")
        val id = idDe("edita@x.com")

        mvc.perform(get("/admin/usuarios/$id/editar").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("edita@x.com")))

        mvc.perform(post("/admin/usuarios/$id/editar").with(Perfis.admin()).with(csrf())
            .param("nome", "Edite Silva").param("role", "GESTOR"))
            .andExpect(redirectedUrl("/admin/usuarios"))

        val editado = usuarios.findByEmail("edita@x.com")!!
        assertThat(editado.nome).isEqualTo("Edite Silva")
        assertThat(editado.role).isEqualTo(Role.GESTOR)
        assertThat(editado.permissoes.map { it.acao }.toSet()).containsExactlyInAnyOrder(Acao.READ, Acao.CREATE, Acao.UPDATE)
    }

    @Test
    fun `so o admin desativa e edita usuarios`() {
        operadores.criarUsuario(email = "protegido@x.com")
        val id = idDe("protegido@x.com")

        mvc.perform(post("/admin/usuarios/$id/desativar").with(Perfis.gestor()).with(csrf())).andExpect(status().isForbidden)
        mvc.perform(get("/admin/usuarios/$id/editar").with(Perfis.gestor())).andExpect(status().isForbidden)
        assertThat(usuarios.findByEmail("protegido@x.com")!!.ativo).isTrue()
    }

    @Test
    fun `lista mostra a situacao de cada usuario`() {
        operadores.criarUsuario(email = "situacao@x.com").also { it.ativo = false; usuarios.save(it) }

        mvc.perform(get("/admin/usuarios").with(Perfis.admin()))
            .andExpect(content().string(containsString("Desativado")))
            .andExpect(content().string(containsString("Reativar")))
    }
}
