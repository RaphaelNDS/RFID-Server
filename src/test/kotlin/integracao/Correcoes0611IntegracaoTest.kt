package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.repository.InventarioItemRepository
import org.example.repository.LeituraAndroidRepository
import org.example.repository.UserRepository
import org.example.request.CadastroRequest
import org.example.service.MotivoSessaoInvalida
import org.example.service.RfidService
import org.example.service.SessaoOperadorService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.example.suporte.comoOperador
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.DisabledException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/** Correções de 06/11 no coletor e no login: desativado offline, horas fora da sessão, patrimônio com "/", senha vencida e login web. */
class Correcoes0611IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var operadores: OperadorTeste
    @Autowired lateinit var usuarios: UserRepository
    @Autowired lateinit var leituras: LeituraAndroidRepository
    @Autowired lateinit var itensInventario: InventarioItemRepository
    @Autowired lateinit var rfidService: RfidService
    @Autowired lateinit var provedorLoginWeb: DaoAuthenticationProvider

    // O bloqueio de login é em memória e compartilhado entre testes: cada teste usa email e IP próprios
    private val ip = "10.61.${(1..250).random()}.${(1..250).random()}"
    private val sufixo = UUID.randomUUID().toString().take(8)

    // ADMIN com id que não colide com os usuários criados no teste
    private val outroAdmin = Perfis.admin(id = 999_999)

    private fun isoDe(momento: LocalDateTime) = momento.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()

    private fun json(url: String, corpo: String, token: String? = null) =
        post(url).contentType(MediaType.APPLICATION_JSON).content(corpo)
            .apply { if (token != null) comoOperador(token) else header(OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR) }

    private fun desativar(email: String) =
        mvc.perform(post("/admin/usuarios/${usuarios.findByEmail(email)!!.id}/desativar").with(outroAdmin).with(csrf()))
            .andExpect(redirectedUrl("/admin/usuarios"))

    // ---------- Operador desativado: vale o que foi feito antes da desativação ----------

    @Test
    fun `acao offline feita antes da desativacao e aceita e a de depois e recusada como desativado`() {
        val email = "desat-offline-$sufixo@x.com"
        operadores.criarUsuario(email = email)
        val token = operadores.entrar(mvc, email)
        val antes = LocalDateTime.now().minusSeconds(1)

        desativar(email)

        // Feita antes da desativação e guardada na fila do coletor: vale
        mvc.perform(json("/api/android/naocadastrada", """{"tag":"E-ANTES-$sufixo"}""", token).header("X-Momento", isoDe(antes)))
            .andExpect(status().isOk)
        // Feita depois: recusada com o motivo certo
        mvc.perform(json("/api/android/naocadastrada", """{"tag":"E-DEPOIS-$sufixo"}""", token))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.motivo").value("DESATIVADO"))
            .andExpect(jsonPath("$.mensagem").value(SessaoOperadorService.MENSAGEM_DESATIVADO))

        // Lote: item por item, pela hora da leitura
        mvc.perform(json("/api/android/leituras/lote",
            """{"itens":[{"tag":"E-LOTE-ANTES-$sufixo","lidoEm":"${isoDe(antes)}"},{"tag":"E-LOTE-DEPOIS-$sufixo","lidoEm":"${isoDe(LocalDateTime.now())}"}]}""",
            token).header("X-Momento", isoDe(antes)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.resultados[0].status").value("NAO_CADASTRADA"))
            .andExpect(jsonPath("$.resultados[1].status").value("RECUSADA"))
            .andExpect(jsonPath("$.resultados[1].motivo").value(MotivoSessaoInvalida.DESATIVADO.name))

        // Login novo continua recusado
        mvc.perform(post("/api/android/login").header(OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR).with { it.remoteAddr = ip; it }
            .contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","senha":"${OperadorTeste.SENHA}"}"""))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.mensagem").value(SessaoOperadorService.MENSAGEM_DESATIVADO))
    }

    @Test
    fun `desativado com sessao ainda aberta (desativacao fora do sistema) tem tudo recusado`() {
        val email = "desat-banco-$sufixo@x.com"
        operadores.criarUsuario(email = email)
        val token = operadores.entrar(mvc, email)
        val antes = LocalDateTime.now().minusSeconds(1)
        // Desativado direto no banco: a sessão não foi encerrada, então não se sabe quando ele foi desativado
        usuarios.saveAndFlush(usuarios.findByEmail(email)!!.apply { ativo = false })

        mvc.perform(json("/api/android/naocadastrada", """{"tag":"E-BANCO-$sufixo"}""", token).header("X-Momento", isoDe(antes)))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.motivo").value("DESATIVADO"))
    }

    // ---------- Horas do coletor fora da sessão (1.4.2) ----------

    private fun cadastrada(tag: String) = rfidService.cadastrarTag(CadastroRequest(tag = tag, codigoInterno = "TI-$tag", patrimonio = "PAT-$tag"))

    @Test
    fun `leitura avulsa com a hora fora da sessao responde 409 e nao grava`() {
        val tag = "E-FORA-$sufixo".uppercase()
        cadastrada(tag)
        val token = operadores.sessaoPadrao(mvc, "fora-$sufixo@x.com")

        mvc.perform(json("/api/android/leitura", """{"tag":"$tag","lidoEm":"2025-01-10T10:00:00-03:00"}""", token))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.motivo").value(MotivoSessaoInvalida.EXPIRADA.name))

        assertThat(leituras.findAll().none { it.tag == tag }).isTrue()

        // Dentro da sessão grava normalmente
        mvc.perform(json("/api/android/leitura", """{"tag":"$tag","lidoEm":"${OffsetDateTime.now()}"}""", token))
            .andExpect(status().isOk)
        assertThat(leituras.findAll().count { it.tag == tag }).isEqualTo(1)
    }

    @Test
    fun `item de inventario com a hora fora da sessao e gravado com a hora do recebimento`() {
        val token = operadores.sessaoPadrao(mvc, "inv-fora-$sufixo@x.com")
        val id = UUID.randomUUID().toString()
        mvc.perform(json("/api/android/inventarios", """{"id":"$id","local":"Sala $sufixo"}""", token)).andExpect(status().isOk)
        val antesDoEnvio = LocalDateTime.now().minusSeconds(1)

        mvc.perform(json("/api/android/inventarios/$id/itens",
            """{"itens":[{"tag":"E-INV-FORA-$sufixo","lidoEm":"2025-01-10T10:00:00-03:00"}]}""", token))
            .andExpect(status().isOk)

        val item = itensInventario.findByInventarioIdOrderByLidoEmAsc(id).single()
        assertThat(item.lidoEm).isAfter(antesDoEnvio) // não a data de 2025 que veio do coletor
    }

    @Test
    fun `fila antiga sem operador nao confere a hora do corpo contra sessao nenhuma`() {
        val tag = "E-FILA-$sufixo".uppercase()
        cadastrada(tag)
        val muitoAntes = LocalDateTime.now().minusDays(30)

        // Sem X-Sessao-Operador (token vazio em conferirHorasDoColetor): a leitura antiga é gravada com a hora dela
        mvc.perform(json("/api/android/leitura", """{"tag":"$tag","lidoEm":"${isoDe(muitoAntes)}"}""").header("X-Momento", isoDe(muitoAntes)))
            .andExpect(status().isOk)

        val gravada = leituras.findAll().single { it.tag == tag }
        assertThat(gravada.dataHora.toLocalDate()).isEqualTo(muitoAntes.toLocalDate())
        assertThat(gravada.operador).isNull()
    }

    // ---------- Localizar por patrimônio com "/" ----------

    private fun localizar(valor: String, token: String): ResultActions =
        mvc.perform(get("/api/android/tags/patrimonio").param("valor", valor).comoOperador(token))

    @Test
    fun `localizar pelo parametro aceita patrimonio com barra e a rota antiga continua`() {
        val tag = "E-BARRA-$sufixo".uppercase()
        rfidService.cadastrarTag(CadastroRequest(tag = tag, codigoInterno = "TI-B-$sufixo", patrimonio = "0123/2019"))
        rfidService.cadastrarTag(CadastroRequest(tag = "$tag-2", codigoInterno = "TI-C-$sufixo", patrimonio = "PAT-$sufixo"))
        val token = operadores.sessaoPadrao(mvc, "barra-$sufixo@x.com")

        localizar("0123/2019", token).andExpect(status().isOk).andExpect(jsonPath("$.epc").value(tag))
        localizar("  0123/2019  ", token).andExpect(status().isOk).andExpect(jsonPath("$.epc").value(tag))
        localizar("9999/2019", token).andExpect(status().isNotFound)
        localizar("  ", token).andExpect(status().isBadRequest)

        // Rota antiga, para os apps que ainda não atualizaram
        mvc.perform(get("/api/android/tags/patrimonio/PAT-$sufixo").comoOperador(token))
            .andExpect(status().isOk).andExpect(jsonPath("$.epc").value("$tag-2"))
    }

    @Test
    fun `localizar pelo parametro exige operador mesmo na fila antiga`() {
        mvc.perform(get("/api/android/tags/patrimonio").param("valor", "0123/2019")
            .header(OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR).header("X-Momento", isoDe(LocalDateTime.now().minusDays(30))))
            .andExpect(status().isUnauthorized)
    }

    // ---------- Senha temporária vencida no coletor ----------

    @Test
    fun `senha temporaria vencida no coletor manda pedir outra ao administrador`() {
        val usuario = operadores.criarUsuario(email = "vencida-$sufixo@x.com")
        usuario.trocarSenha = true
        usuario.senhaTemporariaEm = LocalDateTime.now().minusDays(3) // prazo padrão: 48 h
        usuarios.save(usuario)

        mvc.perform(post("/api/android/login").header(OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR).with { it.remoteAddr = ip; it }
            .contentType(MediaType.APPLICATION_JSON).content("""{"email":"${usuario.email}","senha":"${OperadorTeste.SENHA}"}"""))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.mensagem").value(SessaoOperadorService.MENSAGEM_SENHA_TEMPORARIA_VENCIDA))
            .andExpect(jsonPath("$.mensagem").value(containsString("administrador")))
    }

    // ---------- Login web de desativado: "ativo" conferido depois da senha ----------

    @Test
    fun `login web de desativado confere a senha antes e so com a senha certa diz desativado`() {
        val email = "desat-web-$sufixo@x.com"
        operadores.criarUsuario(email = email).also { it.ativo = false; usuarios.save(it) }

        // Senha errada: a mesma resposta de qualquer senha errada (passou pelo BCrypt, não revela a conta desativada)
        assertThatThrownBy { provedorLoginWeb.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, "Errada123")) }
            .isInstanceOf(BadCredentialsException::class.java)
        // Senha certa: só agora o motivo de verdade
        assertThatThrownBy { provedorLoginWeb.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, OperadorTeste.SENHA)) }
            .isInstanceOf(DisabledException::class.java)

        // Pela tela, os dois continuam recusados com a mesma resposta
        listOf("Errada123", OperadorTeste.SENHA).forEach { senha ->
            mvc.perform(post("/login").with(csrf()).with { it.remoteAddr = ip; it }.param("username", email).param("password", senha))
                .andExpect(redirectedUrl("/login?error"))
        }
    }

    @Test
    fun `login web de usuario ativo continua funcionando`() {
        val email = "ativo-web-$sufixo@x.com"
        operadores.criarUsuario(email = email)

        val autenticado = provedorLoginWeb.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, OperadorTeste.SENHA))
        assertThat(autenticado.isAuthenticated).isTrue()
    }
}
