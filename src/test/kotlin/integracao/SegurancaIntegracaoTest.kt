package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.Role
import org.example.repository.TagNaoCadastradaRepository
import org.example.repository.UserRepository
import org.example.request.SetupAdminRequest
import org.example.service.UsuarioService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.example.suporte.OperadorTeste
import org.example.suporte.comoOperador
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

class SegurancaIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var usuarios: UserRepository
    @Autowired lateinit var usuarioService: UsuarioService
    @Autowired lateinit var pendentes: TagNaoCadastradaRepository
    @Autowired lateinit var operadores: OperadorTeste

    private fun criarAdmin() = usuarioService.criarAdminInicial(SetupAdminRequest("Ana", "adm@x.com", "Kx9mPq2v"))

    @Nested
    inner class ConfiguracaoInicial {

        @Test
        fun `com o banco vazio a tela de setup abre`() {
            mvc.perform(get("/setup"))
                .andExpect(status().isOk)
                .andExpect(view().name("setup"))
        }

        @Test
        fun `setup sem token CSRF e bloqueado`() {
            mvc.perform(post("/setup").param("nome", "Ana").param("email", "adm@x.com").param("senha", "Kx9mPq2v"))
                .andExpect(status().isForbidden)

            assertThat(usuarios.count()).isZero()
        }

        @Test
        fun `setup cria o administrador`() {
            mvc.perform(post("/setup").with(csrf()).param("nome", "Ana").param("email", "adm@x.com").param("senha", "Kx9mPq2v"))
                .andExpect(redirectedUrl("/login"))

            val admin = usuarios.findByEmail("adm@x.com")!!
            assertThat(admin.role).isEqualTo(Role.ADMIN)
            assertThat(admin.senha).isNotEqualTo("Kx9mPq2v") // gravada criptografada
        }

        @Test
        fun `depois de configurado ninguem cria outro admin pelo setup`() {
            criarAdmin()

            mvc.perform(get("/setup")).andExpect(redirectedUrl("/login"))
            mvc.perform(post("/setup").with(csrf()).param("nome", "Hacker").param("email", "h@x.com").param("senha", "Kx9mPq2v"))
                .andExpect(redirectedUrl("/login"))

            assertThat(usuarios.count()).isEqualTo(1)
            assertThat(usuarios.findByEmail("h@x.com")).isNull()
        }
    }

    @Nested
    inner class Login {

        @Test
        fun `login correto vai para o inicio`() {
            criarAdmin()

            mvc.perform(formLogin("/login").user("adm@x.com").password("Kx9mPq2v"))
                .andExpect(redirectedUrl("/redirect"))
        }

        @Test
        fun `senha errada volta para o login com erro`() {
            criarAdmin()

            mvc.perform(formLogin("/login").user("adm@x.com").password("errada"))
                .andExpect(redirectedUrl("/login?error"))
        }

        @Test
        fun `sem login as telas mandam para o login`() {
            criarAdmin()

            mvc.perform(get("/dashboard")).andExpect(redirectedUrlPattern("**/login"))
            mvc.perform(get("/admin/catalogo")).andExpect(redirectedUrlPattern("**/login"))
        }

        @Test
        fun `logout volta ao login com aviso`() {
            mvc.perform(post("/logout").with(Perfis.admin()).with(csrf()))
                .andExpect(redirectedUrl("/login?logout"))
        }
    }

    @Nested
    inner class Permissoes {

        @ParameterizedTest(name = "{0} em {1} -> {2}")
        @CsvSource(
            // tela                       ADMIN e GESTOR e USER e CUSTOM(só catálogo/leitura)
            "ADMIN,  /admin,                   200",
            "GESTOR, /admin,                   403",
            "ADMIN,  /admin/usuarios,          200",
            "GESTOR, /admin/usuarios,          403",
            "USER,   /admin/usuarios,          403",
            "GESTOR, /admin/catalogo,          200",
            "USER,   /admin/catalogo,          200",
            "CUSTOM, /admin/catalogo,          200",
            "GESTOR, /admin/cadastro,          200",
            "USER,   /admin/cadastro,          403",
            "CUSTOM, /admin/cadastro,          403",
            "USER,   /admin/tags-cadastradas,  200",
            "CUSTOM, /admin/tags-cadastradas,  403",
            "USER,   /admin/leituras-android,  200",
            "CUSTOM, /admin/leituras-android,  403",
            "GESTOR, /admin/dashboard,         200",
            "CUSTOM, /admin/dashboard,         403"
        )
        fun `acesso as telas segue as permissoes`(perfil: String, url: String, status: Int) {
            mvc.perform(get(url).with(Perfis.porNome(perfil)))
                .andExpect(status().`is`(status))
        }

        @Test
        fun `gestor nao exclui itens do catalogo`() {
            mvc.perform(post("/api/catalogo/tipo").with(Perfis.admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{"nome":"Monitor"}"""))

            mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/catalogo/tipo/1")
                    .with(Perfis.gestor()).with(csrf())
            ).andExpect(status().isForbidden)
        }
    }

    @Nested
    inner class Csrf {

        @Test
        fun `chamada da tela ao catalogo sem token e bloqueada`() {
            mvc.perform(
                post("/api/catalogo/tipo").with(Perfis.admin())
                    .contentType(MediaType.APPLICATION_JSON).content("""{"nome":"Notebook"}""")
            ).andExpect(status().isForbidden)
        }

        @Test
        fun `com o token a chamada funciona`() {
            mvc.perform(
                post("/api/catalogo/tipo").with(Perfis.admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("""{"nome":"Notebook"}""")
            ).andExpect(status().isOk)
        }

        @Test
        fun `formulario de usuario sem token e bloqueado`() {
            mvc.perform(post("/admin/usuarios/cadastrar").with(Perfis.admin())
                .param("nome", "X").param("email", "x@x.com").param("senha", "1").param("role", "USER"))
                .andExpect(status().isForbidden)
        }
    }

    @Nested
    inner class ApiDosColetores {

        @Test
        fun `sem chave responde 401 em JSON, nunca redireciona para o login`() {
            mvc.perform(get("/api/android/ping"))
                .andExpect(status().isUnauthorized)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist("Location"))
        }

        @Test
        fun `chave errada responde 401`() {
            mvc.perform(get("/api/android/ping").header("X-Chave-Dispositivo", "errada"))
                .andExpect(status().isUnauthorized)
        }

        @Test
        fun `usuario logado no navegador nao entra na API dos coletores sem chave`() {
            mvc.perform(get("/api/android/ping").with(Perfis.admin()))
                .andExpect(status().isUnauthorized)
        }

        @Test
        fun `chave certa identifica o coletor`() {
            mvc.perform(get("/api/android/ping").header("X-Chave-Dispositivo", CHAVE_COLETOR))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.dispositivo").value("coletor-teste"))
        }

        @Test
        fun `coletor registra tag nao cadastrada sem precisar de CSRF nem sessao`() {
            mvc.perform(
                post("/api/android/naocadastrada").comoOperador(operadores.sessaoPadrao(mvc))
                    .contentType(MediaType.APPLICATION_JSON).content("""{"tag":"E2DESCONHECIDA"}""")
            )
                .andExpect(status().isOk)
                .andExpect(request().sessionAttributeDoesNotExist("SPRING_SECURITY_CONTEXT"))

            assertThat(pendentes.existsByTag("E2DESCONHECIDA")).isTrue()
        }

        @Test
        fun `consulta de tag inexistente responde 404`() {
            mvc.perform(get("/api/android/tags/NAOEXISTE").comoOperador(operadores.sessaoPadrao(mvc)))
                .andExpect(status().isNotFound)
        }

        @Test
        fun `coletor envia leitura`() {
            mvc.perform(
                post("/api/android/leitura").comoOperador(operadores.sessaoPadrao(mvc))
                    .contentType(MediaType.APPLICATION_JSON).content("""{"tag":"E1"}""")
            ).andExpect(status().isOk)
        }
    }
}
