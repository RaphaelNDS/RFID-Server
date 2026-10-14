package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.controller.OperadorAndroidController
import org.example.model.Acao
import org.example.model.Modulo
import org.example.model.Role
import org.example.model.SessaoOperadorEntity
import org.example.repository.LeituraAndroidRepository
import org.example.repository.SessaoOperadorRepository
import org.example.repository.UserRepository
import org.example.request.CadastroRequest
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.RfidService
import org.example.service.SessaoOperadorService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.comoOperador
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** Login de operador no coletor: credenciais, bloqueio, sessão amarrada ao coletor, expiração e permissões. */
class OperadorColetorIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var operadores: OperadorTeste
    @Autowired lateinit var sessoes: SessaoOperadorRepository
    @Autowired lateinit var usuarios: UserRepository
    @Autowired lateinit var leituras: LeituraAndroidRepository
    @Autowired lateinit var rfidService: RfidService
    @Autowired lateinit var catalogo: CatalogoService

    private val chaveLeitura = "chave-de-leitura-0123456789" // coletor-leitura (application-test.yml)

    // O bloqueio de login é em memória e compartilhado entre testes: cada teste usa email e IP próprios
    private val ip = "10.77.${(1..250).random()}.${(1..250).random()}"

    private fun login(email: String, senha: String = OperadorTeste.SENHA, chave: String = CHAVE_COLETOR): ResultActions =
        mvc.perform(
            post("/api/android/login").header("X-Chave-Dispositivo", chave)
                .contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","senha":"$senha"}""")
                .with { it.remoteAddr = ip; it }
        )

    private fun naoCadastrada(): MockHttpServletRequestBuilder =
        post("/api/android/naocadastrada").contentType(MediaType.APPLICATION_JSON).content("""{"tag":"E-${UUID.randomUUID()}"}""")

    private fun isoDe(momento: LocalDateTime) = momento.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()

    // ---------- Login ----------

    @Test
    fun `login devolve a sessao com nome e permissoes`() {
        operadores.criarUsuario(email = "olga@x.com", nome = "Olga")

        login("olga@x.com")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").isNotEmpty)
            .andExpect(jsonPath("$.nome").value("Olga"))
            .andExpect(jsonPath("$.podeLer").value(true))
            .andExpect(jsonPath("$.podeCadastrar").value(false)) // sem RFID/CREATE
            .andExpect(jsonPath("$.senhaFraca").value(false))
            .andExpect(jsonPath("$.expiraEm").isNotEmpty)
    }

    @Test
    fun `pode cadastrar exige a permissao do operador e o coletor liberado`() {
        operadores.criarUsuario(email = "cad@x.com", permissoes = arrayOf(Modulo.LEITURA to Acao.READ, Modulo.RFID to Acao.CREATE))

        login("cad@x.com").andExpect(jsonPath("$.podeCadastrar").value(true))
        login("cad@x.com", chave = chaveLeitura).andExpect(jsonPath("$.podeCadastrar").value(false))
    }

    @Test
    fun `senha errada e email inexistente tem a mesma resposta`() {
        operadores.criarUsuario(email = "existe@x.com")

        login("existe@x.com", senha = "Errada123")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.mensagem").value(SessaoOperadorService.MENSAGEM_CREDENCIAIS))
        login("naoexiste-${UUID.randomUUID()}@x.com")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.mensagem").value(SessaoOperadorService.MENSAGEM_CREDENCIAIS))
    }

    @Test
    fun `apos varias senhas erradas bloqueia ate a senha certa com 429 e Retry-After`() {
        val email = "bloq-${UUID.randomUUID()}@x.com"
        operadores.criarUsuario(email = email)

        repeat(5) { login(email, senha = "Errada123").andExpect(status().isUnauthorized) }

        login(email)
            .andExpect(status().isTooManyRequests)
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.mensagem").value(containsString("Muitas tentativas sem sucesso")))
    }

    @Test
    fun `erros em varias contas bloqueiam so o coletor que errou e nao os outros atras do mesmo IP`() {
        val email = "nat-ok-${UUID.randomUUID()}@x.com"
        operadores.criarUsuario(email = email)

        // 20 erros no coletor-teste, cada um numa conta diferente (nenhuma conta chega ao limite dela)
        repeat(20) { login("nat-${UUID.randomUUID()}@x.com", senha = "Errada123").andExpect(status().isUnauthorized) }

        // O coletor-teste fica bloqueado, mesmo para outra conta com a senha certa
        login(email).andExpect(status().isTooManyRequests)

        // O coletor-leitura, saindo pelo MESMO IP (NAT), continua entrando
        login(email, chave = chaveLeitura).andExpect(status().isOk)
    }

    @Test
    fun `bloqueio por email vale em todos os coletores`() {
        val email = "bloq-todos-${UUID.randomUUID()}@x.com"
        operadores.criarUsuario(email = email)

        repeat(5) { login(email, senha = "Errada123").andExpect(status().isUnauthorized) }

        // A conta bloqueada no coletor-teste também não entra pelo coletor-leitura
        login(email, chave = chaveLeitura).andExpect(status().isTooManyRequests)
    }

    @Test
    fun `usuario sem acesso a Leituras nao entra no coletor`() {
        operadores.criarUsuario(email = "catalogo@x.com", permissoes = arrayOf(Modulo.CATALOGO to Acao.READ))

        login("catalogo@x.com")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.mensagem").value(containsString("não tem permissão para usar o coletor")))
    }

    @Test
    fun `admin entra mesmo sem permissoes explicitas`() {
        operadores.criarUsuario(email = "adm-coletor@x.com", role = Role.ADMIN, permissoes = arrayOf())

        login("adm-coletor@x.com").andExpect(status().isOk).andExpect(jsonPath("$.podeCadastrar").value(true))
    }

    @Test
    fun `banco guarda so o hash do token`() {
        val token = operadores.sessaoPadrao(mvc, "hash@x.com")

        assertThat(sessoes.existsById(SessaoOperadorService.hash(token))).isTrue()
        assertThat(sessoes.existsById(token)).isFalse()
    }

    // ---------- Senha temporária e "Esqueceu a senha?" ----------

    @Test
    fun `senha temporaria nao entra no coletor ate a troca na web`() {
        val usuario = operadores.criarUsuario(email = "temp-${UUID.randomUUID()}@x.com")
        usuario.trocarSenha = true
        usuarios.save(usuario)

        login(usuario.email)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.mensagem").value(SessaoOperadorService.MENSAGEM_SENHA_TEMPORARIA))
        assertThat(sessoes.findAll().none { it.usuarioId == usuario.id }).isTrue()

        // Trocada a senha na web (UsuarioService zera a marca), o coletor volta a aceitar
        usuario.trocarSenha = false
        usuarios.save(usuario)
        login(usuario.email).andExpect(status().isOk)
    }

    @Test
    fun `senha errada com senha temporaria pendente continua sendo credencial incorreta`() {
        val usuario = operadores.criarUsuario(email = "temp-errada-${UUID.randomUUID()}@x.com")
        usuario.trocarSenha = true
        usuarios.save(usuario)

        login(usuario.email, senha = "Errada123")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.mensagem").value(SessaoOperadorService.MENSAGEM_CREDENCIAIS))
    }

    private fun esqueciSenha(email: String, chave: String? = CHAVE_COLETOR): ResultActions =
        mvc.perform(
            post("/api/android/esqueci-senha")
                .apply { chave?.let { header("X-Chave-Dispositivo", it) } }
                .contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email"}""")
                .with { it.remoteAddr = ip; it }
        )

    @Test
    fun `esqueci a senha pelo coletor registra o pedido para o administrador sem operador logado`() {
        val usuario = operadores.criarUsuario(email = "esqueci-${UUID.randomUUID()}@x.com")

        esqueciSenha(usuario.email)
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.mensagem").value(OperadorAndroidController.MENSAGEM_PEDIDO_SENHA))

        assertThat(usuarios.findByEmail(usuario.email)!!.senhaPedidaEm).isNotNull()
    }

    @Test
    fun `esqueci a senha responde igual para email que nao existe`() {
        esqueciSenha("ninguem-${UUID.randomUUID()}@x.com")
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.mensagem").value(OperadorAndroidController.MENSAGEM_PEDIDO_SENHA))
    }

    @Test
    fun `esqueci a senha exige a chave do coletor`() {
        esqueciSenha("alguem@x.com", chave = null).andExpect(status().isUnauthorized)
    }

    // ---------- Sessão ----------

    @Test
    fun `sem operador as acoes do coletor sao recusadas com 401 em JSON`() {
        mvc.perform(naoCadastrada().header("X-Chave-Dispositivo", CHAVE_COLETOR))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.codigo").value("SESSAO_OPERADOR"))
            .andExpect(jsonPath("$.mensagem").value("Operador não identificado: faça login no coletor"))
    }

    @Test
    fun `ping continua sem exigir operador`() {
        mvc.perform(get("/api/android/ping").header("X-Chave-Dispositivo", CHAVE_COLETOR)).andExpect(status().isOk)
    }

    @Test
    fun `sessao criada num coletor nao vale em outro`() {
        val token = operadores.sessaoPadrao(mvc, "um-coletor@x.com")

        mvc.perform(naoCadastrada().comoOperador(token, chaveLeitura))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.mensagem").value(containsString("outro coletor")))
    }

    @Test
    fun `logout encerra a sessao mas acoes feitas antes continuam valendo`() {
        val token = operadores.sessaoPadrao(mvc, "sair@x.com")
        val antesDoLogout = LocalDateTime.now().minusSeconds(1)

        mvc.perform(post("/api/android/logout").comoOperador(token)).andExpect(status().isOk)

        // Depois do "Sair" o token não serve para nada novo, nem por alguns minutos
        mvc.perform(naoCadastrada().comoOperador(token))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.mensagem").value("Sessão do operador encerrada: faça login de novo"))

        // Leitura feita antes do logout, que ficou na fila do coletor: vale
        mvc.perform(naoCadastrada().comoOperador(token).header("X-Momento", isoDe(antesDoLogout)))
            .andExpect(status().isOk)

        assertThat(sessoes.findById(SessaoOperadorService.hash(token)).get().encerradaEm).isNotNull()
    }

    @Test
    fun `sessao expirada recusa agora mas aceita acao feita enquanto valia`() {
        val usuario = operadores.criarUsuario(email = "expirada@x.com")
        val token = "token-de-teste-expirado-0123456789"
        val inicio = LocalDateTime.now().minusHours(13)
        sessoes.save(SessaoOperadorEntity(SessaoOperadorService.hash(token), usuario.id!!, "coletor-teste", inicio, inicio.plusHours(12)))

        mvc.perform(naoCadastrada().comoOperador(token))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.mensagem").value("Sessão do operador expirada: faça login de novo"))

        // Leitura feita offline 2h depois do login, enviada só agora: vale
        mvc.perform(naoCadastrada().comoOperador(token).header("X-Momento", isoDe(inicio.plusHours(2))))
            .andExpect(status().isOk)
    }

    @Test
    fun `novo login no mesmo coletor encerra a sessao anterior`() {
        val primeiro = operadores.sessaoPadrao(mvc, "turno1@x.com")
        operadores.sessaoPadrao(mvc, "turno2@x.com")

        assertThat(sessoes.findById(SessaoOperadorService.hash(primeiro)).get().encerradaEm).isNotNull()
    }

    @Test
    fun `permissao removida vale na hora`() {
        val token = operadores.sessaoPadrao(mvc, "perde@x.com")
        val usuario = usuarios.findByEmailFetchPermissoes("perde@x.com")!!
        usuario.permissoes.clear()
        usuarios.saveAndFlush(usuario)

        mvc.perform(naoCadastrada().comoOperador(token)).andExpect(status().isForbidden)
    }

    @Test
    fun `usuario excluido perde a sessao`() {
        val token = operadores.sessaoPadrao(mvc, "excluido@x.com")
        usuarios.delete(usuarios.findByEmail("excluido@x.com")!!)
        usuarios.flush()

        mvc.perform(naoCadastrada().comoOperador(token)).andExpect(status().isUnauthorized)
    }

    // ---------- Registro do operador ----------

    @Test
    fun `leitura fica registrada com o nome do operador`() {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest(nome = "Notebook")).id
        val marca = catalogo.salvarMarca(MarcaRequest(nome = "Dell", tipoId = tipo)).id
        val modelo = catalogo.salvarModelo(ModeloRequest(nome = "Latitude", marcaId = marca)).id
        rfidService.cadastrarTag(CadastroRequest("E-OP", "TI-1", "PAT-1", "SN-1", tipo, marca, modelo))
        operadores.criarUsuario(email = "leitora@x.com", nome = "Lia Leitora")
        val token = operadores.entrar(mvc, "leitora@x.com")

        mvc.perform(post("/api/android/leitura").comoOperador(token)
            .contentType(MediaType.APPLICATION_JSON).content("""{"tag":"E-OP"}"""))
            .andExpect(status().isOk)

        assertThat(leituras.findAll().single { it.tag == "E-OP" }.operador).isEqualTo("Lia Leitora")
    }

    // ---------- Prazo de sincronização (o X-Momento vem do coletor e não pode estender a sessão para sempre) ----------

    private fun sessaoManual(email: String, criada: LocalDateTime, expira: LocalDateTime, encerrada: LocalDateTime? = null): String {
        val usuario = operadores.criarUsuario(email = email)
        val token = "token-manual-${UUID.randomUUID()}"
        sessoes.save(SessaoOperadorEntity(SessaoOperadorService.hash(token), usuario.id!!, "coletor-teste", criada, expira, encerrada))
        return token
    }

    @Test
    fun `token de sessao encerrada ha mais que o prazo nao serve nem com X-Momento antigo`() {
        val agora = LocalDateTime.now()
        val token = sessaoManual("vazado@x.com", criada = agora.minusHours(100), expira = agora.minusHours(88), encerrada = agora.minusHours(95))

        // X-Momento dentro da sessão original: antes era aceito para sempre
        mvc.perform(naoCadastrada().comoOperador(token).header("X-Momento", isoDe(agora.minusHours(99))))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.mensagem").value("Sessão do operador encerrada: faça login de novo"))
    }

    @Test
    fun `sessao expirada aceita acao atrasada so dentro do prazo de sincronizacao`() {
        val agora = LocalDateTime.now()
        val dentro = sessaoManual("dentro@x.com", criada = agora.minusHours(20), expira = agora.minusHours(8))
        val fora = sessaoManual("fora@x.com", criada = agora.minusHours(90), expira = agora.minusHours(78))

        mvc.perform(naoCadastrada().comoOperador(dentro).header("X-Momento", isoDe(agora.minusHours(10))))
            .andExpect(status().isOk)
        mvc.perform(naoCadastrada().comoOperador(fora).header("X-Momento", isoDe(agora.minusHours(80))))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `acao mais antiga que o prazo e recusada mesmo com a sessao ainda valida`() {
        val agora = LocalDateTime.now()
        val token = sessaoManual("longa@x.com", criada = agora.minusHours(100), expira = agora.plusHours(1))

        mvc.perform(naoCadastrada().comoOperador(token).header("X-Momento", isoDe(agora.minusHours(80))))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.mensagem").value(containsString("Ação antiga demais")))
    }

    @Test
    fun `fila antiga sem operador nao libera inventario nem cadastro`() {
        val muitoAntes = isoDe(LocalDateTime.now().minusDays(30))

        mvc.perform(post("/api/android/inventarios").header("X-Chave-Dispositivo", CHAVE_COLETOR).header("X-Momento", muitoAntes)
            .contentType(MediaType.APPLICATION_JSON).content("""{"id":"${UUID.randomUUID()}","local":"Sala"}"""))
            .andExpect(status().isUnauthorized)
        mvc.perform(get("/api/android/locais").header("X-Chave-Dispositivo", CHAVE_COLETOR).header("X-Momento", muitoAntes))
            .andExpect(status().isUnauthorized)
        mvc.perform(get("/api/android/tags/patrimonio/P1").header("X-Chave-Dispositivo", CHAVE_COLETOR).header("X-Momento", muitoAntes))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `acao guardada no coletor de antes do login de operador ainda e aceita sem operador`() {
        val muitoAntes = LocalDateTime.now().minusDays(30)

        mvc.perform(naoCadastrada().header("X-Chave-Dispositivo", CHAVE_COLETOR).header("X-Momento", isoDe(muitoAntes)))
            .andExpect(status().isOk)
    }
}
