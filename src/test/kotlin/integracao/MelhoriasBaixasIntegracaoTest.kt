package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.repository.UserRepository
import org.example.request.SetupAdminRequest
import org.example.service.UsuarioService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.example.suporte.comoOperador
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin
import org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/** Itens de prioridade baixa da análise de 19/10: monitoramento, regras de segurança, email e sessão do coletor. */
class MelhoriasBaixasIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var usuarios: UsuarioService
    @Autowired lateinit var repo: UserRepository
    @Autowired lateinit var operadores: OperadorTeste
    // Pelo nome: o actuator registra outro RequestMappingHandlerMapping (o dos endpoints dele)
    @Autowired @Qualifier("requestMappingHandlerMapping") lateinit var rotas: RequestMappingHandlerMapping

    // ---------------- monitoramento ----------------

    @Test
    fun `health responde UP sem login e sem detalhes`() {
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist())
            .andExpect(jsonPath("$.details").doesNotExist())
    }

    @Test
    fun `outros endpoints do actuator nao ficam expostos`() {
        mvc.perform(get("/actuator/env").with(Perfis.admin())).andExpect(status().isNotFound)
        mvc.perform(get("/actuator/beans").with(Perfis.admin())).andExpect(status().isNotFound)
    }

    // ---------------- regra de segurança das rotas /admin ----------------

    @Test
    fun `todo controller em admin tem PreAuthorize (ou e gestao de usuarios, so do ADMIN)`() {
        val semRegra = rotas.handlerMethods.entries
            .filter { (info, _) -> info.patternValues.any { it.startsWith("/admin") } }
            .filterNot { (info, _) -> info.patternValues.all { it.startsWith("/admin/usuarios") } }
            .filter { (_, metodo) ->
                !metodo.hasMethodAnnotation(PreAuthorize::class.java) &&
                    !metodo.beanType.isAnnotationPresent(PreAuthorize::class.java)
            }
            .map { (info, metodo) -> "${info.patternValues} -> ${metodo.beanType.simpleName}.${metodo.method.name}" }

        // SecurityConfig só exige login em /admin; sem @PreAuthorize a rota abre para qualquer usuário logado
        assertThat(semRegra).isEmpty()
    }

    @Test
    fun `SSE do dashboard e das leituras exigem a permissao da tela`() {
        mvc.perform(get("/admin/sse").with(Perfis.somenteCatalogo())).andExpect(status().isForbidden)
        mvc.perform(get("/admin/stream/leituras").with(Perfis.somenteCatalogo())).andExpect(status().isForbidden)
        // Sem login: vai para a tela de login
        mvc.perform(get("/admin/sse")).andExpect(status().is3xxRedirection)
        mvc.perform(get("/admin/stream/leituras")).andExpect(status().is3xxRedirection)
    }

    // ---------------- email ----------------

    @Test
    fun `email e gravado em minusculas e o login web nao diferencia maiusculas nem espacos`() {
        usuarios.criarAdminInicial(SetupAdminRequest("Ana Admin", " Ana@Empresa.com ", "Kx9mPq2v"))

        assertThat(repo.findAll().map { it.email }).contains("ana@empresa.com")

        mvc.perform(formLogin("/login").user("ANA@empresa.COM").password("Kx9mPq2v"))
            .andExpect(authenticated().withUsername("ana@empresa.com"))
    }

    @Test
    fun `login no coletor nao diferencia maiusculas no email`() {
        operadores.criarUsuario(email = "olga@x.com")

        val token = operadores.entrar(mvc, " OLGA@X.com ")

        assertThat(token).isNotBlank()
    }

    @Test
    fun `cadastro com email invalido e recusado`() {
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/usuarios/cadastrar")
                .with(Perfis.admin())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .param("nome", "Sem Arroba").param("email", "sem-arroba").param("senha", "Kx9mPq2v").param("role", "USER")
        )
        assertThat(repo.findAll().none { it.email == "sem-arroba" }).isTrue()
    }

    // ---------------- permissão "Usuários" ----------------

    @Test
    fun `tela de cadastro de usuario nao oferece mais a permissao Usuarios`() {
        mvc.perform(get("/admin/usuarios/novo").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("value=\"USUARIO\""))))
    }

    // ---------------- 401 do coletor ----------------

    @Test
    fun `sessao desconhecida responde 401 com o motivo DESCONHECIDA`() {
        mvc.perform(get("/api/android/catalogo").comoOperador("token-que-nao-existe"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.codigo").value("SESSAO_OPERADOR"))
            .andExpect(jsonPath("$.motivo").value("DESCONHECIDA"))
    }

    @Test
    fun `sessao de outro coletor responde 401 com o motivo OUTRO_COLETOR`() {
        val token = operadores.sessaoPadrao(mvc) // aberta no coletor-teste

        mvc.perform(get("/api/android/catalogo").comoOperador(token, chave = "chave-de-leitura-0123456789"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.motivo").value("OUTRO_COLETOR"))
    }

    @Test
    fun `requisicao sem sessao responde 401 com o motivo SEM_OPERADOR`() {
        mvc.perform(get("/api/android/catalogo").header(OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.motivo").value("SEM_OPERADOR"))
    }
}
