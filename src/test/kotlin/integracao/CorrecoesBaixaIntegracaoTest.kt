package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.ErroColetorEntity
import org.example.model.LocalEntity
import org.example.repository.ErroColetorRepository
import org.example.repository.LocalRepository
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.service.ErroColetorService
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.UUID

/** Correções de prioridade baixa da análise de 04/11/2026. */
@ExtendWith(OutputCaptureExtension::class)
class CorrecoesBaixaIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var locais: LocalRepository
    @Autowired lateinit var operadores: OperadorTeste
    @Autowired lateinit var erros: ErroColetorRepository
    @Autowired lateinit var errosService: ErroColetorService

    // ---------------- log ----------------

    @Test
    fun `quebra de linha na mensagem nao cria linha falsa no log`(saida: CapturedOutput) {
        LoggerFactory.getLogger("org.example.teste").warn("Login recusado para '{}'", "x\n2026-11-04 INFO Login realizado: admin@empresa")

        assertThat(saida.out).contains("Login recusado para 'x | 2026-11-04 INFO Login realizado: admin@empresa'")
        assertThat(saida.out).doesNotContain("\n2026-11-04 INFO Login realizado")
    }

    // ---------------- senha atual em "Meu perfil" ----------------

    @Test
    fun `senha atual errada em meu perfil conta como erro de login e bloqueia depois de 5`() {
        val usuario = operadores.criarUsuario(email = "perfil-${UUID.randomUUID()}@x.com")
        val logado = Perfis.logado(usuario.id!!, usuario.nome, usuario.email, "CUSTOM", listOf("LEITURA_READ"))
        fun trocar(atual: String) = mvc.perform(post("/perfil/senha").with(logado).with(csrf())
            .with { it.remoteAddr = "10.77.0.1"; it }
            .param("senhaAtual", atual).param("novaSenha", "Novasenha9x").param("confirmarSenha", "Novasenha9x"))

        repeat(5) { trocar("errada").andExpect(content().string(containsString("Senha atual incorreta"))) }

        // Bloqueado: nem a senha certa passa, igual ao login
        trocar(OperadorTeste.SENHA).andExpect(content().string(containsString("Muitas tentativas")))
    }

    // ---------------- edição da mesma etiqueta ao mesmo tempo ----------------

    @Test
    fun `quem salva por cima da mudanca de outra pessoa recebe aviso e pode salvar de novo de proposito`() {
        rfid.cadastrarTag(CadastroRequest(tag = "E-VERSAO", codigoInterno = "TI-1", patrimonio = "PAT-V1"))
        val tag = tags.findByTagReal("E-VERSAO").get()
        val versaoQuandoAbriu = tag.versao

        // Outra pessoa salva primeiro
        rfid.editarTag(tag.id, CadastroRequest(codigoInterno = "TI-1", patrimonio = "PAT-OUTRA", versao = versaoQuandoAbriu))
        tags.flush()
        assertThat(tags.findByTagReal("E-VERSAO").get().versao).describedAs("versão depois da edição").isEqualTo(versaoQuandoAbriu + 1)

        fun salvar(versao: Long) = mvc.perform(post("/admin/etiquetas/${tag.id}/editar").with(Perfis.admin()).with(csrf())
            .param("codigoInterno", "TI-1").param("patrimonio", "PAT-MINHA").param("versao", "$versao"))

        salvar(versaoQuandoAbriu)
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("alterada por outra pessoa enquanto você editava")))
        assertThat(tags.findByTagReal("E-VERSAO").get().patrimonio).isEqualTo("PAT-OUTRA")

        // Salvar de novo (a tela voltou com a versão atual) grava por cima
        salvar(tags.findByTagReal("E-VERSAO").get().versao).andExpect(redirectedUrl("/admin/tags-cadastradas"))
        assertThat(tags.findByTagReal("E-VERSAO").get().patrimonio).isEqualTo("PAT-MINHA")
    }

    // ---------------- texto longo demais ----------------

    @Test
    fun `campo com mais de 255 caracteres volta ao formulario com mensagem clara`() {
        mvc.perform(post("/admin/cadastro/salvar").with(Perfis.admin()).with(csrf())
            .param("tag", "E-LONGO").param("codigoInterno", "x".repeat(300)))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("passa de 255 caracteres")))
            .andExpect(content().string(not(containsString("Registro duplicado"))))
    }

    // ---------------- nomes únicos sem diferenciar maiúsculas (V17) ----------------

    @Test
    fun `banco recusa dois locais que so diferem por maiusculas`() {
        locais.saveAndFlush(LocalEntity(nome = "Almoxarifado"))

        assertThatThrownBy { locais.saveAndFlush(LocalEntity(nome = "ALMOXARIFADO")) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    // ---------------- API do catálogo ----------------

    @Test
    fun `lista de tipos da API devolve so id e nome`() {
        mvc.perform(post("/api/catalogo/tipo").with(Perfis.admin()).with(csrf())
            .contentType("application/json").content("""{"nome":"Coletor"}"""))
        mvc.perform(get("/api/catalogo/tipos").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.nome == 'Coletor')].nome").value(org.hamcrest.Matchers.hasItem("Coletor")))   // 1.14.0: há os tipos da V30
            .andExpect(jsonPath("$[0].marcas").doesNotExist())
    }

    // ---------------- erros do coletor por versão ----------------

    @Test
    fun `filtro de versao acha os erros antigos mesmo com 200 erros novos de outra versao`() {
        val agora = LocalDateTime.now()
        fun erro(versao: String, minutosAtras: Long) = ErroColetorEntity(
            id = UUID.randomUUID().toString(), coletor = "coletor-teste", momento = agora.minusMinutes(minutosAtras),
            recebidoEm = agora, tipo = "Falha", mensagem = "erro", versaoApp = versao)
        erros.saveAll((1..3).map { erro("1.5.0", 1000L + it) } + (1..210).map { erro("1.6.0", it.toLong()) })

        assertThat(errosService.versoesComErro("coletor-teste")).containsExactly("1.6.0", "1.5.0")
        assertThat(errosService.recentes("coletor-teste", "1.5.0")).hasSize(3)
    }
}
