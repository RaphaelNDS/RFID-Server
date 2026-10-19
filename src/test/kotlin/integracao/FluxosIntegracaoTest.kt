package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.Role
import org.example.repository.UserRepository
import org.example.request.CadastroRequest
import org.example.request.SetupAdminRequest
import org.example.service.CatalogoService
import org.example.service.RfidService
import org.example.service.UsuarioService
import org.example.request.MarcaRequest
import org.example.request.TipoEquipamentoRequest
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

class FluxosIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfidService: RfidService
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var usuarioService: UsuarioService
    @Autowired lateinit var usuarios: UserRepository

    @Nested
    inner class Etiquetas {

        @Test
        fun `etiqueta duplicada pelo formulario volta a tela com a mensagem`() {
            rfidService.cadastrarTag(CadastroRequest(tag = "E1", codigoInterno = "TI-1"))

            mvc.perform(post("/admin/cadastro/salvar").with(Perfis.admin()).with(csrf())
                .param("tag", "E1").param("codigoInterno", "TI-2"))
                .andExpect(status().isOk)
                .andExpect(view().name("cadastro"))
                .andExpect(model().attribute("erro", "Esta etiqueta já está cadastrada"))
                .andExpect(content().string(containsString("Esta etiqueta já está cadastrada")))
        }

        @Test
        fun `etiqueta nova pelo formulario vai para a lista`() {
            mvc.perform(post("/admin/cadastro/salvar").with(Perfis.admin()).with(csrf())
                .param("tag", "E-NOVA").param("codigoInterno", "TI-9").param("patrimonio", "P9"))
                .andExpect(redirectedUrl("/admin/tags-cadastradas"))

            assertThat(rfidService.buscarPorTag("E-NOVA")?.patrimonio).isEqualTo("P9")
        }

        // A API de cadastro pelo coletor tem os próprios testes (CadastroColetorIntegracaoTest);
        // aqui o que se testa é o erro de validação virar 400 com a mensagem em JSON numa API da web.
        @Test
        fun `erro de validacao numa API responde 400 com a mensagem em JSON`() {
            catalogo.salvarTipo(TipoEquipamentoRequest(nome = "Notebook"))

            mvc.perform(post("/api/catalogo/tipo").with(Perfis.admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{"nome":"notebook"}"""))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.mensagem").value("Tipo já cadastrado!"))
        }

        @Test
        fun `JSON quebrado responde 400 explicando`() {
            mvc.perform(post("/api/catalogo/tipo").with(Perfis.admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{"nome":"""))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.mensagem").value(containsString("Corpo da requisição inválido")))
        }
    }

    @Nested
    inner class Catalogo {

        @Test
        fun `excluir tipo em uso responde 409 com o motivo`() {
            val tipo = catalogo.salvarTipo(TipoEquipamentoRequest(nome = "Notebook"))
            catalogo.salvarMarca(MarcaRequest(nome = "Dell", tipoId = tipo.id))

            mvc.perform(delete("/api/catalogo/tipo/${tipo.id}").with(Perfis.admin()).with(csrf()))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.mensagem").value("Não é possível excluir tipo com marcas vinculadas"))
        }

        @Test
        fun `marca sem tipo pela API responde 400 (antes era 500)`() {
            mvc.perform(post("/api/catalogo/marca").with(Perfis.admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{"nome":"HP"}"""))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.mensagem").value("Selecione o tipo de equipamento da marca"))
        }

        @Test
        fun `marcas por tipo devolvem so id e nome`() {
            val tipo = catalogo.salvarTipo(TipoEquipamentoRequest(nome = "Notebook"))
            catalogo.salvarMarca(MarcaRequest(nome = "Dell", tipoId = tipo.id))

            mvc.perform(get("/api/catalogo/marcas").param("tipoId", tipo.id.toString()).with(Perfis.usuario()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].nome").value("Dell"))
                .andExpect(jsonPath("$[0].tipo").doesNotExist())
        }

        @Test
        fun `parametro com tipo errado responde 400`() {
            mvc.perform(get("/api/catalogo/marcas").param("tipoId", "abc").with(Perfis.usuario()))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.mensagem").value("Valor inválido para 'tipoId': abc"))
        }
    }

    @Nested
    inner class Filtros {

        @Test
        fun `data invalida nas leituras mostra pagina de erro 400`() {
            mvc.perform(get("/admin/leituras-android").param("dia", "abc").with(Perfis.admin()))
                .andExpect(status().isBadRequest)
                .andExpect(view().name("error"))
                .andExpect(model().attribute("mensagem", containsString("Data inválida")))
        }

        @Test
        fun `data invalida no relatorio usa o padrao com aviso`() {
            mvc.perform(get("/admin/dashboard").param("inicio", "abc").with(Perfis.admin()))
                .andExpect(status().isOk)
                .andExpect(model().attribute("aviso", containsString("Data inválida")))
        }

        @Test
        fun `endereco inexistente responde 404`() {
            mvc.perform(get("/pagina-que-nao-existe").with(Perfis.admin()))
                .andExpect(status().isNotFound)
        }
    }

    @Nested
    inner class Usuarios {

        @Test
        fun `cadastro de usuario mostra a confirmacao depois do redirect`() {
            usuarioService.criarAdminInicial(SetupAdminRequest("Ana", "adm@x.com", "Kx9mPq2v"))

            mvc.perform(post("/admin/usuarios/cadastrar").with(Perfis.admin()).with(csrf())
                .param("nome", "Bruno").param("email", "bruno@x.com").param("senha", "Kx9mPq2v").param("role", "GESTOR"))
                .andExpect(redirectedUrl("/admin/usuarios/novo"))
                .andExpect(flash().attribute("msg", "Usuário bruno@x.com criado com sucesso!"))

            assertThat(usuarios.findByEmail("bruno@x.com")!!.role).isEqualTo(Role.GESTOR)
        }

        @Test
        fun `perfil personalizado sem nenhuma caixa marcada mostra o erro em vez de erro 500`() {
            // Sem caixas marcadas o navegador não envia "telas"/"permissoes" e o Spring entrega null.
            // Precisa já existir um usuário: o primeiro do sistema vira ADMIN de qualquer jeito.
            usuarioService.criarAdminInicial(SetupAdminRequest("Ana", "adm@x.com", "Kx9mPq2v"))

            mvc.perform(post("/admin/usuarios/cadastrar").with(Perfis.admin()).with(csrf())
                .param("nome", "Carla").param("email", "carla@x.com").param("senha", "Kx9mPq2v").param("role", "CUSTOM"))
                .andExpect(status().isOk)
                .andExpect(model().attribute("erro", "Selecione pelo menos um módulo"))
        }

        @Test
        fun `erro em perfil pronto (sem caixas no formulario) volta a tela em vez de erro 500`() {
            usuarioService.criarAdminInicial(SetupAdminRequest("Ana", "adm@x.com", "Kx9mPq2v"))

            mvc.perform(post("/admin/usuarios/cadastrar").with(Perfis.admin()).with(csrf())
                .param("nome", "Outra").param("email", "adm@x.com").param("senha", "Kx9mPq2v").param("role", "USER"))
                .andExpect(status().isOk)
                .andExpect(model().attribute("erro", "Já existe um usuário com este email"))
        }

        @Test
        fun `email repetido volta ao formulario mantendo o que foi digitado`() {
            usuarioService.criarAdminInicial(SetupAdminRequest("Ana", "adm@x.com", "Kx9mPq2v"))

            mvc.perform(post("/admin/usuarios/cadastrar").with(Perfis.admin()).with(csrf())
                .param("nome", "Outra Ana").param("email", "ADM@x.com").param("senha", "Kx9mPq2v")
                .param("role", "CUSTOM").param("telas", "CATALOGO").param("permissoes", "READ"))
                .andExpect(status().isOk)
                .andExpect(view().name("admin-usuarios-cadastro"))
                .andExpect(model().attribute("erro", "Já existe um usuário com este email"))
                .andExpect(content().string(containsString("value=\"Outra Ana\"")))
        }
    }

    @Nested
    inner class Perfil {

        @Test
        fun `trocar o email e depois a senha na mesma sessao funciona (bug original)`() {
            usuarioService.criarAdminInicial(SetupAdminRequest("Ana", "adm@x.com", "Kx9mPq2v"))
            val id = usuarios.findByEmail("adm@x.com")!!.id!!
            val ana = Perfis.logado(id, "Ana", "adm@x.com", "ADMIN", emptyList())

            mvc.perform(post("/perfil/email").with(ana).with(csrf())
                .param("novoEmail", "ana.nova@x.com").param("senhaAtual", "Kx9mPq2v"))
                .andExpect(redirectedUrl("/perfil?emailAlterado"))

            mvc.perform(post("/perfil/senha").with(ana).with(csrf())
                .param("senhaAtual", "Kx9mPq2v").param("novaSenha", "Zt7wLn4r").param("confirmarSenha", "Zt7wLn4r"))
                .andExpect(redirectedUrl("/perfil?senhaAlterada"))

            assertThat(usuarios.findByEmail("adm@x.com")).isNull()
            assertThat(usuarios.findByEmail("ana.nova@x.com")).isNotNull()
        }

        @Test
        fun `senha atual errada mostra o erro na tela`() {
            usuarioService.criarAdminInicial(SetupAdminRequest("Ana", "adm@x.com", "Kx9mPq2v"))
            val id = usuarios.findByEmail("adm@x.com")!!.id!!

            mvc.perform(post("/perfil/senha").with(Perfis.logado(id, "Ana", "adm@x.com", "ADMIN", emptyList())).with(csrf())
                .param("senhaAtual", "errada").param("novaSenha", "Zt7wLn4r").param("confirmarSenha", "Zt7wLn4r"))
                .andExpect(status().isOk)
                .andExpect(view().name("perfil-senha"))
                .andExpect(model().attribute("erro", "Senha atual incorreta"))
        }
    }
}
