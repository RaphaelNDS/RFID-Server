package org.example.integracao

import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.example.request.CadastroRequest
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.comoOperador
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * O mesmo lote enviado duas vezes AO MESMO TEMPO (o coletor reenvia quando a resposta demora): o índice único
 * (tag, lido_em_coletor) recusa a segunda gravação no banco de verdade, ela conta como repetida e o resto do lote
 * continua sendo gravado.
 *
 * Fora da transação dos outros testes de integração (lá uma recusa do banco abortaria a transação inteira): grava
 * de verdade e apaga o que criou no fim.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LoteSimultaneoIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var operadores: OperadorTeste
    @Autowired lateinit var jdbc: JdbcTemplate

    private val prefixo = "E-SIMULT-${UUID.randomUUID().toString().take(8)}-"
    private val email = "simultaneo-${UUID.randomUUID()}@x.com"

    @AfterEach
    fun apagar() {
        jdbc.update("delete from leituras_android where tag like ?", "$prefixo%")
        jdbc.update("delete from historico_etiquetas where tag_real like ?", "$prefixo%")
        jdbc.update("delete from tags where tag_real like ?", "$prefixo%")
        jdbc.update("delete from tags_nao_cadastradas where tag like ?", "$prefixo%")
        jdbc.update("delete from permissoes where usuario_id in (select id from usuarios where email = ?)", email)
        jdbc.update("delete from usuarios where email = ?", email) // as sessões saem junto (on delete cascade)
        // O login do operador grava o contato do coletor; nos outros testes isso é desfeito com a transação
        jdbc.update("delete from coletores where nome = ?", "coletor-teste")
    }

    @Test
    fun `mesmo lote enviado duas vezes ao mesmo tempo grava cada leitura uma vez so e nenhum item da erro`() {
        val quantidade = 200
        (0 until quantidade).forEach { rfid.cadastrarTag(CadastroRequest(tag = "$prefixo$it", codigoInterno = "TI-$it")) }
        operadores.criarUsuario(email = email)
        val token = operadores.entrar(mvc, email)

        val lida = LocalDateTime.now().minusMinutes(3).truncatedTo(ChronoUnit.MILLIS)
        val iso = lida.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
        val corpo = """{"itens":[${(0 until quantidade).joinToString(",") { """{"tag":"$prefixo$it","lidoEm":"$iso"}""" }}]}"""

        val largada = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val respostas = (1..2).map {
            executor.submit<String> {
                largada.await()
                mvc.perform(post("/api/android/leituras/lote").comoOperador(token)
                    .contentType(MediaType.APPLICATION_JSON).content(corpo))
                    .andReturn().response.contentAsString
            }
        }
        largada.countDown()
        val corpos = respostas.map { it.get(60, TimeUnit.SECONDS) }
        executor.shutdown()

        // Cada leitura gravada uma vez só, mesmo com os dois envios disputando
        val gravadas = jdbc.queryForObject("select count(*) from leituras_android where tag like ?", Long::class.java, "$prefixo%")
        assertThat(gravadas).isEqualTo(quantidade.toLong())

        // Nos dois envios, todo item volta CADASTRADA (gravada ou reconhecida como reenvio), nenhum ERRO
        corpos.forEach { json ->
            val status: List<String> = JsonPath.read(json, "$.resultados[*].status")
            assertThat(status).hasSize(quantidade).containsOnly("CADASTRADA")
        }
    }
}
