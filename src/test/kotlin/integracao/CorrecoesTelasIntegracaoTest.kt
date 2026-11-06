package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.Acao
import org.example.model.Modulo
import org.example.model.Role
import org.example.repository.TagRepository
import org.example.repository.UserRepository
import org.example.request.CadastroRequest
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.RecuperacaoSenhaService
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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** Correções das telas (análise de 06/11/2026): formulários, mensagens, voltar para a mesma busca e textos. */
class CorrecoesTelasIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var operadores: OperadorTeste
    @Autowired lateinit var usuarios: UserRepository
    @Autowired lateinit var recuperacao: RecuperacaoSenhaService

    private data class Catalogo(val tipo: Long, val marca: Long, val modelo: Long)

    private fun catalogo(sufixo: String = UUID.randomUUID().toString().take(6)): Catalogo {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Notebook $sufixo"))
        val marca = catalogo.salvarMarca(MarcaRequest("Dell $sufixo", tipo.id))
        val modelo = catalogo.salvarModelo(ModeloRequest("Latitude $sufixo", marca.id))
        return Catalogo(tipo.id!!, marca.id!!, modelo.id!!)
    }

    private fun etiquetaComCatalogo(epc: String): Pair<Long, Catalogo> {
        val c = catalogo()
        rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "CI-$epc", patrimonio = "PAT-$epc",
            tipoEquipamentoId = c.tipo, marcaId = c.marca, modeloId = c.modelo))
        return tags.findByTagReal(epc).get().id!! to c
    }

    // ---------------- 1. editar etiqueta não apaga marca e modelo ----------------

    @Test
    fun `quem edita ou cadastra etiqueta sem o modulo Catalogo consegue as listas de tipos, marcas e modelos`() {
        val c = catalogo()
        val soEditar = Perfis.logado(30, "Edu Editor", "edu@x.com", "CUSTOM", listOf("RFID_READ", "RFID_UPDATE"))
        val soCadastrar = Perfis.logado(31, "Cida Cadastro", "cida@x.com", "CUSTOM", listOf("RFID_READ", "RFID_CREATE"))
        listOf(soEditar, soCadastrar).forEach { quem ->
            mvc.perform(get("/api/catalogo/tipos").with(quem)).andExpect(status().isOk)
            mvc.perform(get("/api/catalogo/marcas").param("tipoId", "${c.tipo}").with(quem))
                .andExpect(status().isOk).andExpect(content().string(containsString("Dell")))
            mvc.perform(get("/api/catalogo/modelos").param("marcaId", "${c.marca}").with(quem))
                .andExpect(status().isOk).andExpect(content().string(containsString("Latitude")))
        }

        // Só ver etiquetas (sem criar/editar) continua sem acesso às listas
        val soVer = Perfis.logado(32, "Vera Ver", "vera@x.com", "CUSTOM", listOf("RFID_READ"))
        mvc.perform(get("/api/catalogo/tipos").with(soVer)).andExpect(status().isForbidden)
        // E o resto do catálogo continua exigindo o módulo Catálogo
        mvc.perform(post("/api/catalogo/tipo").with(soEditar).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("""{"nome":"Impressora"}""")).andExpect(status().isForbidden)
    }

    @Test
    fun `edicao da etiqueta manda marca e modelo atuais em campos ocultos, sem depender dos selects`() {
        val (id, c) = etiquetaComCatalogo("E-OCULTO-1")

        val html = mvc.perform(get("/admin/etiquetas/$id/editar").with(Perfis.admin()))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertThat(html).contains("""<input type="hidden" id="marcaValor" name="marcaId" value="${c.marca}">""")
        assertThat(html).contains("""<input type="hidden" id="modeloValor" name="modeloId" value="${c.modelo}">""")
        // Os selects não têm name: desabilitados enquanto carregam, não seriam enviados (gravava marca/modelo vazios)
        assertThat(html).contains("""<select id="marcaSelect" disabled>""").contains("""<select id="modeloSelect" disabled>""")
        assertThat(html).doesNotContain("""name="marcaId" disabled""")
        assertThat(html).contains("id=\"catalogoErro\"").contains("carregando > 0")

        // Salvar só com os campos ocultos (o que o navegador manda enquanto os selects carregam) mantém marca e modelo
        mvc.perform(post("/admin/etiquetas/$id/editar").with(Perfis.admin()).with(csrf())
            .param("codigoInterno", "CI-E-OCULTO-1").param("patrimonio", "PAT-NOVO")
            .param("tipoEquipamentoId", "${c.tipo}").param("marcaId", "${c.marca}").param("modeloId", "${c.modelo}")
            .param("versao", "${tags.findById(id).get().versao}"))
            .andExpect(redirectedUrl("/admin/tags-cadastradas"))
        val salva = tags.findById(id).get()
        assertThat(salva.patrimonio).isEqualTo("PAT-NOVO")
        assertThat(salva.marcaId).isEqualTo(c.marca)
        assertThat(salva.modeloId).isEqualTo(c.modelo)
    }

    @Test
    fun `cadastro de etiqueta tambem usa os campos ocultos e mantem a marca escolhida quando volta com erro`() {
        val c = catalogo()
        mvc.perform(get("/admin/cadastro").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("""<input type="hidden" id="marcaValor" name="marcaId" value="">""")))

        mvc.perform(post("/admin/cadastro/salvar").with(Perfis.admin()).with(csrf())
            .param("tag", "E-ERRO-CAD").param("codigoInterno", "")
            .param("tipoEquipamentoId", "${c.tipo}").param("marcaId", "${c.marca}").param("modeloId", "${c.modelo}"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("""name="marcaId" value="${c.marca}">""")))
            .andExpect(content().string(containsString("""name="modeloId" value="${c.modelo}">""")))
    }

    // ---------------- 2. editar usuário com erro mantém o que foi escolhido ----------------

    @Test
    fun `erro ao editar usuario volta a tela com o perfil e as permissoes escolhidos`() {
        val usuario = operadores.criarUsuario(email = "edicao-${UUID.randomUUID()}@x.com", role = Role.USER,
            permissoes = arrayOf(Modulo.LEITURA to Acao.READ))

        val html = mvc.perform(post("/admin/usuarios/${usuario.id}/editar").with(Perfis.admin(id = 999_999)).with(csrf())
            .param("nome", "   ").param("role", "CUSTOM")
            .param("telas", "RFID").param("permissoes", "UPDATE"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(html).contains("Informe o nome")
        assertThat(html).contains("""<option value="CUSTOM" selected="selected">""")
        assertThat(html).contains("""value="RFID" checked="checked"""").contains("""value="UPDATE" checked="checked"""")
        assertThat(html).doesNotContain("""value="LEITURA" checked="checked"""")
        assertThat(usuarios.findById(usuario.id!!).get().role).isEqualTo(Role.USER)
    }

    // ---------------- 3. senha temporária dos pedidos pede confirmação e trava ----------------

    @Test
    fun `gerar senha temporaria pelo pedido pede confirmacao e trava o botao no envio`() {
        val usuario = operadores.criarUsuario(email = "pedido-${UUID.randomUUID()}@x.com", nome = "Paula Pedido")
        recuperacao.pedir(usuario.email, "10.66.0.1")

        val html = mvc.perform(get("/admin/usuarios").with(Perfis.admin()))
            .andExpect(status().isOk).andReturn().response.contentAsString

        val pedidos = html.substringBefore("id=\"tabela\"")
        assertThat(pedidos).contains("Gerar uma senha temporária para Paula Pedido? A senha atual deixa de funcionar.")
        assertThat(pedidos).contains("data-enviar-uma-vez").contains("""data-enviando="Gerando…"""")
        // O da lista também trava
        assertThat(html.substringAfter("id=\"tabela\"")).contains("data-enviar-uma-vez")
    }

    // ---------------- 4 e 5. login ----------------

    @Test
    fun `login explica a sessao encerrada pelo administrador e guarda so o email apos errar a senha`() {
        // Com o banco sem usuários o login manda para a configuração inicial
        operadores.criarUsuario(email = "login-${UUID.randomUUID()}@x.com")
        val html = mvc.perform(get("/login").param("encerrada", ""))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertThat(html).contains("Sua sessão foi encerrada pelo administrador (permissões alteradas, senha trocada ou usuário desativado). Entre de novo.")
        // Script com o nonce da CSP, guardando só o email (nunca a senha)
        assertThat(html).containsPattern("<script nonce=\"[^\"]+\">\\s*// Depois de errar a senha")
        assertThat(html).contains("sessionStorage.setItem(CHAVE, email.value.trim())")
        assertThat(html).doesNotContain("senha.value")
    }

    // ---------------- 9. cadastro avisa e volta para Pendentes ----------------

    @Test
    fun `cadastrar etiqueta vinda de Pendentes avisa e volta para Pendentes na mesma busca e pagina`() {
        mvc.perform(get("/admin/cadastro").param("tag", "E-PEND-1").param("origem", "pendentes")
            .param("busca", "E-PEND").param("pagina", "2").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("""<input type="hidden" name="origem" value="pendentes">""")))
            .andExpect(content().string(containsString("""href="/admin/tags-pendentes?busca=E-PEND&amp;pagina=2" class="btn btn-ghost">Cancelar""")))

        mvc.perform(post("/admin/cadastro/salvar").with(Perfis.admin()).with(csrf())
            .param("tag", "e-pend-1 ").param("codigoInterno", "CI-P1")
            .param("origem", "pendentes").param("busca", "E-PEND").param("pagina", "2"))
            .andExpect(redirectedUrl("/admin/tags-pendentes?busca=E-PEND&pagina=2"))
            .andExpect(flash().attribute("msg", "Etiqueta E-PEND-1 cadastrada."))
    }

    @Test
    fun `cadastrar etiqueta sem origem conhecida avisa e vai para as cadastradas`() {
        mvc.perform(post("/admin/cadastro/salvar").with(Perfis.admin()).with(csrf())
            .param("tag", "E-SEM-ORIGEM").param("codigoInterno", "CI-S1").param("origem", "https://outro-site.com"))
            .andExpect(redirectedUrl("/admin/tags-cadastradas"))
            .andExpect(flash().attribute("msg", "Etiqueta E-SEM-ORIGEM cadastrada."))

        mvc.perform(get("/admin/tags-pendentes").with(Perfis.admin()).flashAttr("msg", "Etiqueta X cadastrada."))
            .andExpect(content().string(containsString("Etiqueta X cadastrada.")))
    }

    // ---------------- 10. voltar para a mesma busca e página ----------------

    @Test
    fun `editar etiqueta volta para a lista na mesma busca e pagina, so com parametros validos`() {
        val (id, _) = etiquetaComCatalogo("E-VOLTA-1")

        mvc.perform(get("/admin/tags-cadastradas").param("busca", "VOLTA").with(Perfis.admin()))
            .andExpect(content().string(containsString("/admin/etiquetas/$id/editar?busca=VOLTA")))

        mvc.perform(get("/admin/etiquetas/$id/editar").param("busca", "VOLTA").param("pagina", "3").with(Perfis.admin()))
            .andExpect(content().string(containsString("""<input type="hidden" name="busca" value="VOLTA">""")))
            .andExpect(content().string(containsString("""<input type="hidden" name="pagina" value="3">""")))
            .andExpect(content().string(containsString("""href="/admin/tags-cadastradas?busca=VOLTA&amp;pagina=3" class="btn btn-ghost">Cancelar""")))

        fun salvar(busca: String, pagina: String) = mvc.perform(post("/admin/etiquetas/$id/editar").with(Perfis.admin()).with(csrf())
            .param("codigoInterno", "CI-E-VOLTA-1").param("patrimonio", "PAT-E-VOLTA-1")
            .param("busca", busca).param("pagina", pagina))

        salvar("VOLTA", "3").andExpect(redirectedUrl("/admin/tags-cadastradas?busca=VOLTA&pagina=3"))
        // Página inválida e busca enorme são ignoradas (nada de parâmetro solto na URL)
        salvar("x".repeat(300), "abc").andExpect(redirectedUrl("/admin/tags-cadastradas"))
    }

    @Test
    fun `detalhe do inventario volta para a lista no mesmo local e pagina`() {
        val token = operadores.sessaoPadrao(mvc, email = "inv-${UUID.randomUUID()}@x.com")
        val id = UUID.randomUUID().toString()
        mvc.perform(post("/api/android/inventarios").comoOperador(token).contentType(MediaType.APPLICATION_JSON)
            .content("""{"id":"$id","local":"Sala Voltar","iniciadoEm":"2026-10-13T10:00:00-03:00"}""")).andExpect(status().isOk)

        mvc.perform(get("/admin/inventarios/$id").param("local", "7").param("pagina", "2").with(Perfis.admin()))
            .andExpect(content().string(containsString("""href="/admin/inventarios?local=7&amp;pagina=2" class="btn btn-ghost">""")))
        mvc.perform(get("/admin/inventarios/$id").param("local", "javascript:alert(1)").with(Perfis.admin()))
            .andExpect(content().string(containsString("""href="/admin/inventarios" class="btn btn-ghost">""")))
            .andExpect(content().string(not(containsString("javascript:alert"))))
    }

    // ---------------- 11 e 12. dashboard e catálogo ----------------

    @Test
    fun `relatorios avisam quando a atualizacao em tempo real cai de vez`() {
        mvc.perform(get("/admin/dashboard").with(Perfis.admin()))
            .andExpect(content().string(containsString("Atualização em tempo real interrompida — recarregue a página.")))
            .andExpect(content().string(containsString("evt.readyState === EventSource.CLOSED")))
    }

    @Test
    fun `catalogo trata erro ao carregar tipos e marcas`() {
        val html = mvc.perform(get("/admin/catalogo").with(Perfis.admin())).andReturn().response.contentAsString
        assertThat(html).contains("Não foi possível carregar os tipos de equipamento")
            .contains("Não foi possível carregar as marcas deste tipo")
        assertThat(Regex("\\.then\\(verificarResposta\\)").findAll(html).count()).isGreaterThanOrEqualTo(4)
    }

    // ---------------- 13. publicar APK ----------------

    @Test
    fun `apk recusado devolve as notas e o botao trava no envio`() {
        mvc.perform(multipart("/admin/app")
            .file(MockMultipartFile("apk", "virus.apk", "application/octet-stream", "não sou um apk".toByteArray()))
            .param("notas", "Correção no envio")
            .with(Perfis.admin()).with(csrf()))
            .andExpect(flash().attribute("erro", containsString("não é um APK válido")))
            .andExpect(flash().attribute("notas", "Correção no envio"))

        mvc.perform(get("/admin/app").with(Perfis.admin()).flashAttr("notas", "Correção no envio"))
            .andExpect(content().string(containsString("""value="Correção no envio"""")))
            .andExpect(content().string(containsString("data-enviar-uma-vez")))
            .andExpect(content().string(containsString("""data-enviando="Enviando…"""")))
    }
}
