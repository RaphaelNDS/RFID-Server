package org.example.integracao

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.example.config.InteracaoWebFilter
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.mock.web.MockMultipartFile
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import javax.sql.DataSource

/** Correções de 06/11/2026: limites de entrada, prazos do banco, agendador e sessão web com SSE. */
@ExtendWith(OutputCaptureExtension::class)
class LimitesSegurancaIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var flyway: Flyway
    @Autowired lateinit var agendador: ThreadPoolTaskScheduler
    @Autowired lateinit var ambiente: Environment

    // ---------------- email gigante no login ----------------

    @Test
    fun `email gigante no login e recusado como falha comum e nao vai inteiro para o log`(saida: CapturedOutput) {
        val gigante = "x".repeat(300) + "@ataque.com"

        mvc.perform(post("/login").with(csrf()).with { it.remoteAddr = "10.66.0.1"; it }
            .param("username", gigante).param("password", "qualquer"))
            .andExpect(redirectedUrl("/login?error"))

        assertThat(saida.out).contains("email com ${gigante.length} caracteres")
        assertThat(saida.out).doesNotContain("x".repeat(256))
    }

    // ---------------- prazos do banco ----------------

    @Test
    fun `pool do banco tem prazos de conexao, de resposta e de comando`() {
        val pool = dataSource.unwrap(HikariDataSource::class.java)

        assertThat(pool.connectionTimeout).isEqualTo(5_000)
        assertThat(pool.keepaliveTime).isEqualTo(120_000)
        assertThat(pool.dataSourceProperties)
            .containsEntry("socketTimeout", "60")
            .containsEntry("connectTimeout", "5")
            .containsEntry("tcpKeepAlive", "true")

        // O limite por comando chega mesmo ao PostgreSQL
        val limite = dataSource.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery("show statement_timeout").use { it.next(); it.getString(1) } }
        }
        assertThat(limite).isEqualTo("1min")
    }

    @Test
    fun `migracoes usam conexao propria sem o limite de 60 s por comando`() {
        val dsMigracoes = flyway.configuration.dataSource

        assertThat(dsMigracoes).isNotInstanceOf(HikariDataSource::class.java)
        val limite = dsMigracoes.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery("show statement_timeout").use { it.next(); it.getString(1) } }
        }
        assertThat(limite).isEqualTo("0")
    }

    // ---------------- agendador ----------------

    @Test
    fun `tarefas agendadas tem 3 threads e nao esperam umas pelas outras`() {
        assertThat(agendador.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(3)
    }

    // ---------------- corpo das telas ----------------

    @Test
    fun `JSON acima de 1 MB nas telas e recusado com 413 antes de desserializar`() {
        val grande = """{"nome":"${"a".repeat(1_100_000)}"}"""

        mvc.perform(
            post("/api/catalogo/tipos").with(Perfis.admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(grande)
        ).andExpect(status().isPayloadTooLarge)
    }

    @Test
    fun `envio de arquivo nao passa pelo limite de 1 MB das telas`() {
        val apk = MockMultipartFile("apk", "grande.apk", "application/vnd.android.package-archive", ByteArray(1_500_000))

        val resposta = mvc.perform(multipart("/admin/app").file(apk).with(Perfis.admin()).with(csrf())).andReturn()

        // O APK inválido é recusado pela validação do próprio envio, e não pelo limite de 1 MB
        assertThat(resposta.response.status).isNotEqualTo(413)
    }

    // ---------------- sessão web e SSE ----------------

    @Test
    fun `sessao web dura 30 min sem uso`() {
        assertThat(ambiente.getProperty("server.servlet.session.timeout", Duration::class.java))
            .isEqualTo(Duration.ofMinutes(30))
    }

    @Test
    fun `requisicao normal marca o uso da sessao e o SSE nao`() {
        val sessao = MockHttpSession()

        mvc.perform(get("/perfil").session(sessao).with(Perfis.admin()))
        val depoisDaTela = sessao.getAttribute(InteracaoWebFilter.ULTIMA_INTERACAO) as Long?
        assertThat(depoisDaTela).isNotNull()

        // Uso antigo (há 10 min): a conexão em tempo real não o atualiza
        val antigo = depoisDaTela!! - Duration.ofMinutes(10).toMillis()
        sessao.setAttribute(InteracaoWebFilter.ULTIMA_INTERACAO, antigo)
        mvc.perform(get("/admin/sse").session(sessao).with(Perfis.admin()).accept(MediaType.TEXT_EVENT_STREAM))

        assertThat(sessao.getAttribute(InteracaoWebFilter.ULTIMA_INTERACAO)).isEqualTo(antigo)
    }

    @Test
    fun `SSE reaberto numa aba esquecida ha mais de 30 min encerra a sessao`() {
        val sessao = MockHttpSession()
        sessao.setAttribute(InteracaoWebFilter.ULTIMA_INTERACAO, System.currentTimeMillis() - Duration.ofMinutes(31).toMillis())

        mvc.perform(get("/admin/sse").session(sessao).with(Perfis.admin()).accept(MediaType.TEXT_EVENT_STREAM))

        assertThat(sessao.isInvalid).isTrue()
    }
}
