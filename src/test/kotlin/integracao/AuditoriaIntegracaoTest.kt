package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.AreaAuditoria
import org.example.model.Role
import org.example.repository.AuditoriaRepository
import org.example.request.CadastroUsuarioRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.AuditoriaService
import org.example.service.CatalogoService
import org.example.service.UsuarioService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** Auditoria de usuários e configurações: o que é registrado, por quem, e a tela só do ADMIN. */
class AuditoriaIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var repo: AuditoriaRepository
    @Autowired lateinit var auditoria: AuditoriaService
    @Autowired lateinit var usuarios: UsuarioService
    @Autowired lateinit var catalogo: CatalogoService

    @Test
    @WithMockUser(username = "ana@x.com", roles = ["ADMIN"])
    fun `cadastro de usuario e do catalogo ficam registrados com quem fez`() {
        usuarios.cadastrar(CadastroUsuarioRequest("Bruno", "Bruno@X.com", "Kx9mPq2v", Role.USER))
        catalogo.salvarTipo(TipoEquipamentoRequest(nome = "Impressora"))

        val registros = repo.findAll()
        val usuario = registros.single { it.acao == "Usuário criado" }
        assertThat(usuario.area).isEqualTo(AreaAuditoria.USUARIOS)
        assertThat(usuario.alvo).isEqualTo("bruno@x.com")
        assertThat(usuario.usuario).isEqualTo("ana@x.com")
        assertThat(usuario.detalhe).startsWith("Perfil ") // o primeiro usuário do banco vira ADMIN

        val tipo = registros.single { it.acao == "Tipo de equipamento cadastrado" }
        assertThat(tipo.area).isEqualTo(AreaAuditoria.CATALOGO)
        assertThat(tipo.alvo).isEqualTo("Impressora")
    }

    @Test
    fun `exportacao pela web fica registrada com o IP`() {
        mvc.perform(get("/admin/leituras-android/exportar").param("formato", "csv").with(Perfis.usuario()))
            .andExpect(status().isOk)

        val exportacao = repo.findAll().single { it.area == AreaAuditoria.EXPORTACAO }
        assertThat(exportacao.acao).isEqualTo("Leituras exportadas")
        assertThat(exportacao.usuario).isEqualTo("user@x.com")
        assertThat(exportacao.origem).startsWith("Web")
    }

    @Test
    fun `tela de auditoria mostra os registros, filtra por area e busca`() {
        auditoria.registrar(AreaAuditoria.CATALOGO, "Marca cadastrada", "Dell")
        auditoria.registrar(AreaAuditoria.SENHAS, "Senha temporária gerada", "carla@x.com")

        mvc.perform(get("/admin/auditoria").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Marca cadastrada")))
            .andExpect(content().string(containsString("carla@x.com")))

        mvc.perform(get("/admin/auditoria").param("area", "SENHAS").with(Perfis.admin()))
            .andExpect(content().string(containsString("carla@x.com")))
            .andExpect(content().string(not(containsString("Marca cadastrada"))))

        mvc.perform(get("/admin/auditoria").param("busca", "dell").with(Perfis.admin()))
            .andExpect(content().string(containsString("Marca cadastrada")))
            .andExpect(content().string(not(containsString("carla@x.com"))))

        // Área inventada na URL: mostra todas, sem erro
        mvc.perform(get("/admin/auditoria").param("area", "NAO_EXISTE").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Marca cadastrada")))
    }

    @Test
    fun `so o ADMIN ve a auditoria`() {
        mvc.perform(get("/admin/auditoria").with(Perfis.gestor())).andExpect(status().isForbidden)
        mvc.perform(get("/dashboard").with(Perfis.gestor()))
            .andExpect(content().string(not(containsString("href=\"/admin/auditoria\""))))
        mvc.perform(get("/dashboard").with(Perfis.admin()))
            .andExpect(content().string(containsString("href=\"/admin/auditoria\"")))
    }
}
