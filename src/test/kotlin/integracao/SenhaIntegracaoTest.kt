package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.Role
import org.example.model.UsuarioEntity
import org.example.repository.UserRepository
import org.example.request.SetupAdminRequest
import org.example.service.UsuarioService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

class SenhaIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var usuarios: UserRepository
    @Autowired lateinit var usuarioService: UsuarioService
    @Autowired lateinit var encoder: PasswordEncoder

    @Test
    fun `setup com senha fraca volta ao formulario com o motivo e os dados digitados`() {
        mvc.perform(post("/setup").with(csrf()).param("nome", "Ana").param("email", "ana@x.com").param("senha", "123"))
            .andExpect(status().isOk)
            .andExpect(view().name("setup"))
            .andExpect(content().string(containsString("Senha fraca")))
            .andExpect(content().string(containsString("value=\"ana@x.com\"")))

        assertThat(usuarios.count()).isZero()
    }

    @Test
    fun `formularios mostram as regras de senha`() {
        mvc.perform(get("/setup")).andExpect(content().string(containsString("Mínimo de 8 caracteres")))
        mvc.perform(get("/perfil/senha").with(Perfis.admin())).andExpect(content().string(containsString("Mínimo de 8 caracteres")))
        mvc.perform(get("/admin/usuarios/novo").with(Perfis.admin())).andExpect(content().string(containsString("Mínimo de 8 caracteres")))
    }

    @Test
    fun `admin nao cadastra usuario com senha fraca`() {
        mvc.perform(post("/admin/usuarios/cadastrar").with(Perfis.admin()).with(csrf())
            .param("nome", "Bruno").param("email", "bruno@x.com").param("senha", "bruno123").param("role", "USER"))
            .andExpect(status().isOk)
            .andExpect(model().attribute("erro", containsString("não pode conter o seu email")))

        assertThat(usuarios.findByEmail("bruno@x.com")).isNull()
    }

    @Test
    fun `usuario antigo com senha fraca entra e e levado para a troca de senha`() {
        // Usuário criado antes da regra existir (gravado direto, sem passar pela política)
        usuarios.save(UsuarioEntity(nome = "Antigo", email = "antigo@x.com", senha = encoder.encode("123"), role = Role.USER))

        mvc.perform(post("/login").with(csrf()).with { it.remoteAddr = "10.9.0.1"; it }
            .param("username", "antigo@x.com").param("password", "123"))
            .andExpect(redirectedUrl("/perfil/senha?fraca"))
    }

    @Test
    fun `com senha fraca o resto do sistema fica bloqueado ate trocar a senha`() {
        usuarios.save(UsuarioEntity(nome = "Antigo", email = "antigo@x.com", senha = encoder.encode("123"), role = Role.USER))

        val login = mvc.perform(post("/login").with(csrf()).with { it.remoteAddr = "10.9.0.3"; it }
            .param("username", "antigo@x.com").param("password", "123"))
            .andExpect(redirectedUrl("/perfil/senha?fraca"))
            .andReturn()
        val sessao = login.request.getSession(false) as MockHttpSession

        // Antes dava para sair da tela de troca e usar o sistema normalmente
        mvc.perform(get("/dashboard").session(sessao)).andExpect(redirectedUrl("/perfil/senha?fraca"))

        // A tela de troca abre com o aviso de senha fraca (e não o de senha temporária)
        mvc.perform(get("/perfil/senha").param("fraca", "").session(sessao))
            .andExpect(status().isOk)
            .andExpect(model().attribute("trocaObrigatoria", false))

        // Trocou: o sistema volta a ficar liberado
        mvc.perform(post("/perfil/senha").session(sessao).with(csrf())
            .param("senhaAtual", "123").param("novaSenha", "Kx9mPq2v").param("confirmarSenha", "Kx9mPq2v"))
            .andExpect(redirectedUrl("/perfil?senhaAlterada"))
        mvc.perform(get("/dashboard").session(sessao)).andExpect(status().isOk)
    }

    @Test
    fun `usuario com senha boa entra normalmente`() {
        usuarioService.criarAdminInicial(SetupAdminRequest("Ana", "ana@x.com", "Kx9mPq2v"))

        mvc.perform(post("/login").with(csrf()).with { it.remoteAddr = "10.9.0.2"; it }
            .param("username", "ana@x.com").param("password", "Kx9mPq2v"))
            .andExpect(redirectedUrl("/redirect"))
    }

    @Test
    fun `tela de troca explica por que o usuario foi trazido ate ela`() {
        mvc.perform(get("/perfil/senha").param("fraca", "").with(Perfis.usuario()))
            .andExpect(content().string(containsString("não atende às regras de segurança")))
    }

    @Test
    fun `troca para senha fraca mostra o motivo`() {
        usuarioService.criarAdminInicial(SetupAdminRequest("Ana", "ana@x.com", "Kx9mPq2v"))
        val id = usuarios.findByEmail("ana@x.com")!!.id!!

        mvc.perform(post("/perfil/senha").with(Perfis.logado(id, "Ana", "ana@x.com", "ADMIN", emptyList())).with(csrf())
            .param("senhaAtual", "Kx9mPq2v").param("novaSenha", "senha123").param("confirmarSenha", "senha123"))
            .andExpect(status().isOk)
            .andExpect(model().attribute("erro", containsString("muito comum")))
    }
}
