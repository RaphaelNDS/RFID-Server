package org.example.integracao

import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.example.model.FimSessao
import org.example.repository.ColetorRepository
import org.example.repository.SessaoOperadorRepository
import org.example.repository.UserRepository
import org.example.request.CadastroRequest
import org.example.service.ColetorService
import org.example.service.ErroColetorService
import org.example.service.RfidService
import org.example.service.SessaoOperadorService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.example.suporte.comoOperador
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * 1.4.4, sugestões da sessão do app Android: margem de relógio no fim da sessão, diagnóstico do coletor na tela
 * Coletores, erro do tipo RELOGIO e ETag no esperado do inventário.
 */
class Versao144IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var operadores: OperadorTeste
    @Autowired lateinit var usuarios: UserRepository
    @Autowired lateinit var sessoes: SessaoOperadorRepository
    @Autowired lateinit var coletoresRepo: ColetorRepository
    @Autowired lateinit var coletores: ColetorService
    @Autowired lateinit var erros: ErroColetorService
    @Autowired lateinit var rfidService: RfidService

    // Maiúsculo: o servidor grava as etiquetas (EPC) em maiúsculas desde a V18
    private val sufixo = UUID.randomUUID().toString().take(8).uppercase()
    private val outroAdmin = Perfis.admin(id = 999_998)

    @BeforeEach
    fun limpar() {
        coletores.esquecerContatosGravados()
        erros.esquecerContagens()
    }

    private fun iso(momento: LocalDateTime) = momento.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()

    private fun naoCadastrada(token: String, momento: LocalDateTime) =
        mvc.perform(post("/api/android/naocadastrada").comoOperador(token).header("X-Momento", iso(momento))
            .contentType(MediaType.APPLICATION_JSON).content("""{"tag":"E-${UUID.randomUUID().toString().take(12)}"}"""))

    private fun sair(token: String) =
        mvc.perform(post("/api/android/logout").comoOperador(token)).andExpect(status().is2xxSuccessful)

    private fun sessaoDo(token: String) = sessoes.findById(SessaoOperadorService.hash(token)).get()

    // ---------- 1) Margem de relógio no fim da sessão (Sair / outro login) ----------

    @Test
    fun `depois do Sair a acao com o relogio do coletor ate 5 min adiantado ainda vale e depois disso nao`() {
        val email = "margem-sair-$sufixo@x.com"
        operadores.criarUsuario(email = email)
        val token = operadores.entrar(mvc, email)
        sair(token)
        val fim = sessaoDo(token).encerradaEm!!
        assertThat(sessaoDo(token).encerradaPor).isEqualTo(FimSessao.SAIR.name)

        // Feita antes do "Sair" pelo relógio de verdade, mas com o relógio do coletor 1 min adiantado
        naoCadastrada(token, fim.plusMinutes(1)).andExpect(status().isOk)
        // 1.4.6: relógio entre 2 e 5 min adiantado (na 1.4.4 a margem era de 2 min e esta era recusada e perdida)
        naoCadastrada(token, fim.plusMinutes(4)).andExpect(status().isOk)
        // Mais de 5 min no futuro a hora do coletor não vale (vira "agora", depois do "Sair"): recusada como antes
        naoCadastrada(token, fim.plusMinutes(6))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.motivo").value("ENCERRADA"))
        // Sem X-Momento vale a hora do servidor, que não precisa de margem: recusada na hora, como antes
        mvc.perform(post("/api/android/naocadastrada").comoOperador(token)
            .contentType(MediaType.APPLICATION_JSON).content("""{"tag":"E-SEMHORA-$sufixo"}"""))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.motivo").value("ENCERRADA"))
    }

    @Test
    fun `outro login no mesmo coletor tambem tem a margem`() {
        val email = "margem-login-$sufixo@x.com"
        operadores.criarUsuario(email = email)
        val antigo = operadores.entrar(mvc, email)
        operadores.entrar(mvc, email)   // renovação: encerra a sessão anterior do coletor

        val sessao = sessaoDo(antigo)
        assertThat(sessao.encerradaPor).isEqualTo(FimSessao.NOVO_LOGIN.name)
        naoCadastrada(antigo, sessao.encerradaEm!!.plusSeconds(90)).andExpect(status().isOk)
    }

    @Test
    fun `lote usa a mesma margem item por item`() {
        val email = "margem-lote-$sufixo@x.com"
        operadores.criarUsuario(email = email)
        val token = operadores.entrar(mvc, email)
        sair(token)
        val fim = sessaoDo(token).encerradaEm!!

        mvc.perform(post("/api/android/leituras/lote").comoOperador(token).header("X-Momento", iso(fim.minusSeconds(5)))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"itens":[{"tag":"E-LT1-$sufixo","lidoEm":"${iso(fim.plusMinutes(1))}"},
                {"tag":"E-LT2-$sufixo","lidoEm":"${iso(fim.plusMinutes(4))}"},
                {"tag":"E-LT3-$sufixo","lidoEm":"${iso(fim.plusMinutes(7))}"}]}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.resultados[0].status").value("NAO_CADASTRADA"))
            // Relógio entre 2 e 5 min adiantado: aceita desde a 1.4.6
            .andExpect(jsonPath("$.resultados[1].status").value("NAO_CADASTRADA"))
            .andExpect(jsonPath("$.resultados[2].status").value("RECUSADA"))
            .andExpect(jsonPath("$.resultados[2].motivo").value("ENCERRADA"))
    }

    @Test
    fun `sessao encerrada pelo servidor (desativacao) nao tem margem`() {
        val email = "margem-desat-$sufixo@x.com"
        operadores.criarUsuario(email = email)
        val token = operadores.entrar(mvc, email)
        mvc.perform(post("/admin/usuarios/${usuarios.findByEmail(email)!!.id}/desativar").with(outroAdmin).with(csrf()))
            .andExpect(redirectedUrl("/admin/usuarios"))

        val sessao = sessaoDo(token)
        assertThat(sessao.encerradaPor).isEqualTo(FimSessao.ADMIN.name)
        naoCadastrada(token, sessao.encerradaEm!!.plusSeconds(30))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.motivo").value("DESATIVADO"))
    }

    @Test
    fun `sessao encerrada antes da 1_4_4 (sem motivo guardado) continua sem margem`() {
        val email = "margem-antiga-$sufixo@x.com"
        operadores.criarUsuario(email = email)
        val token = operadores.entrar(mvc, email)
        val fim = LocalDateTime.now()
        sessoes.saveAndFlush(sessaoDo(token).apply { encerradaEm = fim; encerradaPor = null })

        naoCadastrada(token, fim.plusSeconds(30))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.motivo").value("ENCERRADA"))
    }

    // ---------- 2) Diagnóstico do coletor na tela Coletores ----------

    private fun pingComDiagnostico(relogio: OffsetDateTime, fila: String? = "7", maisAntiga: String? = null) =
        mvc.perform(post("/api/android/erros").header(OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR)
            .header("X-Versao-App", "1.7.3").header("X-Versao-Codigo", "116")
            .apply { if (fila != null) header("X-Fila-Pendentes", fila) }
            .apply { if (maisAntiga != null) header("X-Fila-Mais-Antiga", maisAntiga) }
            .header("X-Ultima-Sinc-Ok", "2026-11-09T08:00:00-03:00")
            .header("X-Espaco-Livre", "150")
            .header("X-Android", "11")
            .header("X-Modelo", "MC3300 Zebra")
            .header("X-Relogio-Coletor", relogio.toString())
            .contentType(MediaType.APPLICATION_JSON).content("""{"itens":[]}"""))
            .andExpect(status().isAccepted)

    @Test
    fun `cabecalhos de diagnostico ficam guardados e aparecem destacados na tela Coletores`() {
        val agora = OffsetDateTime.now()
        val maisAntiga = agora.minusHours(3)
        // 4 min 30 s: a conta usa a hora de chegada, um pouco depois (a tela arredonda para baixo)
        pingComDiagnostico(relogio = agora.plusMinutes(4).plusSeconds(30), maisAntiga = maisAntiga.toString())

        val c = coletoresRepo.findById("coletor-teste").get()
        assertThat(c.filaPendentes).isEqualTo(7)
        assertThat(c.filaMaisAntiga).isEqualTo(maisAntiga.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime())
        assertThat(c.ultimaSincOk).isEqualTo(LocalDateTime.of(2026, 11, 9, 8, 0)
            .atZone(ZoneId.of("-03:00")).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime())
        assertThat(c.espacoLivreMb).isEqualTo(150)
        assertThat(c.versaoAndroid).isEqualTo("11")
        assertThat(c.modelo).isEqualTo("MC3300 Zebra")
        assertThat(c.desvioRelogioSegundos).isBetween(260L, 275L)   // 4 min 30 s adiantado

        val linha = coletores.listar().single { it.nome == "coletor-teste" }
        assertThat(linha.filaParada).isTrue()
        assertThat(linha.relogioErrado).isTrue()
        assertThat(linha.poucoEspaco).isTrue()

        mvc.perform(get("/admin/coletores").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("7 pendente(s)")))
            .andExpect(content().string(containsString("relógio adiantado 4 min")))
            .andExpect(content().string(containsString("150 MB livres")))
            .andExpect(content().string(containsString("MC3300 Zebra · Android 11")))
            .andExpect(content().string(containsString("Precisam de atenção")))
    }

    /** A gravação do contato é no máximo uma por minuto: recua o último contato gravado para a próxima gravar. */
    private fun passarUmMinuto() {
        coletores.esquecerContatosGravados()
        coletoresRepo.saveAndFlush(coletoresRepo.findById("coletor-teste").get().apply { ultimoContato = ultimoContato.minusMinutes(2) })
    }

    @Test
    fun `fila vazia limpa a mais antiga e cabecalho ausente ou invalido nao apaga o que estava guardado`() {
        val agora = OffsetDateTime.now()
        pingComDiagnostico(relogio = agora, maisAntiga = agora.minusMinutes(10).toString())

        // Um minuto depois (gravação limitada a uma por minuto): fila zerada, sem X-Fila-Mais-Antiga
        passarUmMinuto()
        pingComDiagnostico(relogio = agora, fila = "0")
        coletoresRepo.findById("coletor-teste").get().let {
            assertThat(it.filaPendentes).isZero()
            assertThat(it.filaMaisAntiga).isNull()
            assertThat(coletores.listar().single { l -> l.nome == "coletor-teste" }.relogioErrado).isFalse()
        }

        // Fila que o app não conseguiu ler (sem cabeçalho) ou valor inválido: fica a última conhecida
        passarUmMinuto()
        pingComDiagnostico(relogio = agora, fila = "muitos")
        assertThat(coletoresRepo.findById("coletor-teste").get().filaPendentes).isZero()
    }

    @Test
    fun `valores de fora sao conferidos um a um`() {
        val d = org.example.config.ColetorContatoInterceptor.diagnostico { nome ->
            mapOf(
                "X-Fila-Pendentes" to "-3",
                "X-Espaco-Livre" to "abc",
                "X-Android" to "11\nFALSO",
                "X-Modelo" to "Coletor ?nico",
                "X-Relogio-Coletor" to "1970-01-01T00:00:00Z",
                "X-Ultima-Sinc-Ok" to "ontem"
            )[nome]
        }
        assertThat(d.filaInformada).isFalse()
        assertThat(d.espacoLivreMb).isNull()
        assertThat(d.versaoAndroid).isNull()
        assertThat(d.modelo).isEqualTo("Coletor ?nico")   // o app troca acento por ?
        assertThat(d.relogioColetor).isNull()             // relógio sem data configurada
        assertThat(d.ultimaSincOk).isNull()
    }

    // ---------- 3) Tipo de erro RELOGIO ----------

    private fun erroJson(tipo: String, mensagem: String): String {
        val momento = LocalDateTime.now().minusMinutes(2).atZone(ZoneId.systemDefault()).toOffsetDateTime()
        return """{"id":"${UUID.randomUUID()}","momento":"$momento","tipo":"$tipo","mensagem":"$mensagem","versaoApp":"1.7.3","codigoVersao":116}"""
    }

    @Test
    fun `erro RELOGIO tem rotulo proprio nao aparece como travamento e a tela filtra por tipo`() {
        mvc.perform(post("/api/android/erros").header(OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"itens":[${erroJson("RELOGIO", "Relogio do coletor 5 min adiantado")},${erroJson("FALHA_GERAL", "App travou na leitura")}]}"""))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.novos").value(2))

        mvc.perform(get("/admin/coletores/coletor-teste/erros").param("tipo", "RELOGIO").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Relogio do coletor 5 min adiantado")))
            .andExpect(content().string(not(containsString("App travou na leitura"))))
            .andExpect(content().string(containsString(">Relógio do coletor</span>")))
            .andExpect(content().string(not(containsString(">Travamento</span>"))))
            // As opções do filtro: os dois tipos guardados
            .andExpect(content().string(containsString("<option value=\"RELOGIO\" selected=\"selected\">Relógio do coletor</option>")))
            .andExpect(content().string(containsString("<option value=\"FALHA_GERAL\">Travamento</option>")))

        mvc.perform(get("/admin/coletores/coletor-teste/erros").param("tipo", "FALHA_GERAL").param("versao", "1.7.3").with(Perfis.admin()))
            .andExpect(content().string(containsString("App travou na leitura")))
            .andExpect(content().string(not(containsString("Relogio do coletor 5 min adiantado"))))
    }

    // ---------- 4) ETag no esperado do inventário ----------

    @Test
    fun `esperado responde ETag e 304 sem corpo quando nada mudou e muda com um inventario novo`() {
        val token = operadores.sessaoPadrao(mvc, "etag-$sufixo@x.com")
        val local = "Sala ETag $sufixo"
        fun coletor(url: String, json: String) =
            mvc.perform(post(url).comoOperador(token).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk)
        fun inventario(vararg tags: String) {
            val id = UUID.randomUUID().toString()
            coletor("/api/android/inventarios", """{"id":"$id","local":"$local"}""")
            coletor("/api/android/inventarios/$id/itens", """{"itens":[${tags.joinToString { """{"tag":"$it"}""" }}]}""")
            coletor("/api/android/inventarios/$id/finalizar", "{}")
        }
        fun esperado(ifNoneMatch: String? = null) =
            mvc.perform(get("/api/android/inventarios/esperado").param("local", local).comoOperador(token)
                .apply { if (ifNoneMatch != null) header("If-None-Match", ifNoneMatch) })

        // Mesmo patrimônio nas duas: a ordem tem desempate pela etiqueta, então o ETag é estável
        rfidService.cadastrarTag(CadastroRequest(tag = "E-ET-B-$sufixo", codigoInterno = "TI-B-$sufixo", patrimonio = ""))
        rfidService.cadastrarTag(CadastroRequest(tag = "E-ET-A-$sufixo", codigoInterno = "TI-A-$sufixo", patrimonio = ""))
        inventario("E-ET-B-$sufixo", "E-ET-A-$sufixo")

        val primeira = esperado().andExpect(status().isOk)
            .andExpect(jsonPath("$.itens.length()").value(2))
            .andExpect(jsonPath("$.itens[0].tag").value("E-ET-A-$sufixo"))
            .andReturn().response
        val etag = primeira.getHeader("ETag")!!
        assertThat(etag).matches("\"[0-9a-f]{32}\"")
        assertThat(primeira.contentType).contains("application/json")

        // Nada mudou: 304 sem corpo (também com a forma fraca e numa lista)
        esperado(etag).andExpect(status().isNotModified).andExpect(header().string("ETag", etag))
            .andExpect(content().string(""))
        esperado("\"outro\", W/$etag").andExpect(status().isNotModified)
        // A mesma lista pedida de novo tem o mesmo ETag
        esperado().andExpect(header().string("ETag", etag))

        // Inventário novo finalizado: o ETag muda e a lista volta inteira
        rfidService.cadastrarTag(CadastroRequest(tag = "E-ET-C-$sufixo", codigoInterno = "TI-C-$sufixo", patrimonio = "PAT-C-$sufixo"))
        inventario("E-ET-C-$sufixo")
        // Desde a 1.6.0 o esperado inclui o cadastro: A e B continuam no local (postos lá pelo primeiro inventário)
        val depois = esperado(etag).andExpect(status().isOk).andExpect(jsonPath("$.itens.length()").value(3)).andReturn().response
        assertThat(depois.getHeader("ETag")).isNotEqualTo(etag)
        assertThat(JsonPath.read<List<String>>(depois.contentAsString, "$.itens[*].tag")).contains("E-ET-C-$sufixo")
    }
}
