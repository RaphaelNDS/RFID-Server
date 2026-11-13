package org.example.integracao

import org.example.model.MotivoBaixa
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.comoOperador
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 1.6.2: a situação do bem na resposta do lote de leituras (pedido do app 2.4.0). */
class Versao162IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var operadores: OperadorTeste

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val token by lazy { operadores.sessaoPadrao(mvc, "v162-$s@x.com".lowercase()) }

    @Test
    fun `lote devolve a situacao do bem lido com os nomes da ficha`() {
        rfid.cadastrarTag(CadastroRequest(tag = "E-L1-$s", codigoInterno = "TI-1", patrimonio = "P-L1-$s"))
        rfid.cadastrarTag(CadastroRequest(tag = "E-L2-$s", codigoInterno = "TI-2", patrimonio = "P-L2-$s"))
        rfid.darBaixa(tags.findByTagReal("E-L2-$s").get().id, MotivoBaixa.SUCATA, null, null)
        token   // abre a sessão do operador antes da hora da leitura
        val agora = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS)

        mvc.perform(post("/api/android/leituras/lote").comoOperador(token).contentType(MediaType.APPLICATION_JSON)
            .content("""{"itens":[{"tag":"E-L1-$s","lidoEm":"$agora"},{"tag":"E-L2-$s","lidoEm":"$agora"}]}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.resultados[0].status").value("CADASTRADA"))
            .andExpect(jsonPath("$.resultados[0].dados.situacao").value("DISPONIVEL"))
            .andExpect(jsonPath("$.resultados[0].dados.situacaoRotulo").value("No estoque"))
            .andExpect(jsonPath("$.resultados[1].dados.situacao").value("BAIXADO"))
            .andExpect(jsonPath("$.resultados[1].dados.situacaoRotulo").value("Baixado"))
            .andExpect(jsonPath("$.resultados[1].dados.patrimonio").value("P-L2-$s"))
    }
}
