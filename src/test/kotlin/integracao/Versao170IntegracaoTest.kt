package org.example.integracao

import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.example.model.Acao
import org.example.model.MotivoBaixa
import org.example.model.Modulo
import org.example.model.SituacaoBem
import org.example.repository.HistoricoEtiquetaRepository
import org.example.repository.TagRepository
import org.example.repository.TransferenciaItemRepository
import org.example.repository.TransferenciaRepository
import org.example.request.CadastroRequest
import org.example.service.PatrimonioCadastrosService
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
import java.util.UUID

/** 1.7.0: transferência pelo coletor (o bem lido no inventário vai para o local do inventário). */
class Versao170IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var cadastros: PatrimonioCadastrosService
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var historico: HistoricoEtiquetaRepository
    @Autowired lateinit var guias: TransferenciaRepository
    @Autowired lateinit var itensGuia: TransferenciaItemRepository
    @Autowired lateinit var operadores: OperadorTeste

    private val s = UUID.randomUUID().toString().take(6).uppercase()

    /** Operador com Leituras e RFID · Editar (pode transferir). */
    private val token by lazy {
        val email = "v170-$s@x.com".lowercase()
        operadores.criarUsuario(email = email, nome = "Olga $s", permissoes = arrayOf(Modulo.LEITURA to Acao.READ, Modulo.RFID to Acao.UPDATE))
        operadores.entrar(mvc, email)
    }

    private fun tag(epc: String) = tags.findByTagReal(epc).get()

    private fun transferir(epc: String, corpo: String, sessao: String = token) =
        mvc.perform(post("/api/android/tags/$epc/transferencia").comoOperador(sessao).contentType(MediaType.APPLICATION_JSON).content(corpo))

    @Test
    fun `coletor transfere o bem para o local do inventario com guia historico e reenvio sem duplicar`() {
        val origem = cadastros.salvarLocal(null, "Sala Origem $s", null)
        val destino = cadastros.salvarLocal(null, "Sala Destino $s", null)
        val r = cadastros.salvarResponsavel(null, "Rita $s", null, null, null)
        rfid.cadastrarTag(CadastroRequest(tag = "E-COL1-$s", codigoInterno = "TI-1", patrimonio = "P-COL1-$s",
            localId = origem.id, responsavelId = r.id, situacao = "EXTRAVIADO"))
        val antes = guias.count()

        // Nome do local como o coletor manda (maiúsculas e espaços diferentes) e a etiqueta em minúsculas
        val resposta = transferir("e-col1-$s", """{"local":"  sala   destino $s ","observacao":"achado no armário"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.local").value("Sala Destino $s"))
            .andExpect(jsonPath("$.situacao").value("DISPONIVEL"))       // extraviado achado volta Disponível
            .andExpect(jsonPath("$.situacaoRotulo").value("Disponível"))
            .andExpect(jsonPath("$.jaEstava").value(false))
            .andReturn().response.contentAsString
        val numero = (JsonPath.read<Number>(resposta, "$.transferencia")).toLong()

        assertThat(tag("E-COL1-$s").localId).isEqualTo(destino.id)
        assertThat(tag("E-COL1-$s").responsavelId).isEqualTo(r.id)   // o coletor só muda o local
        val guia = guias.findById(numero).get()
        assertThat(guia.registradaPor).isEqualTo("Olga $s")
        assertThat(guia.motivo).isEqualTo("Pelo coletor coletor-teste: achado no armário")
        assertThat(itensGuia.findAllByTransferenciaIdOrderByIdAsc(numero).single().localAnterior).isEqualTo("Sala Origem $s")
        assertThat(historico.findByTagRealOrderByMomentoDescIdDesc("E-COL1-$s").first { it.campo == "Local" }.origem)
            .isEqualTo("Coletor coletor-teste · Transferência nº $numero")

        // Reenvio (a rede caiu antes da resposta): 200, já estava, sem guia nova
        transferir("E-COL1-$s", """{"local":"Sala Destino $s"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.jaEstava").value(true))
            .andExpect(jsonPath("$.transferencia").doesNotExist())
        assertThat(guias.count()).isEqualTo(antes + 1)
        assertThat(tag("E-COL1-$s").situacao).isEqualTo(SituacaoBem.DISPONIVEL)
    }

    @Test
    fun `recusas da transferencia pelo coletor`() {
        val sala = cadastros.salvarLocal(null, "Sala R $s", null)
        cadastros.salvarLocal(null, "Fechada $s", null).let { cadastros.salvarLocal(it.id, it.nome, null, ativo = false) }
        rfid.cadastrarTag(CadastroRequest(tag = "E-COLB-$s", codigoInterno = "TI-B", patrimonio = "P-COLB-$s"))
        rfid.darBaixa(tag("E-COLB-$s").id, MotivoBaixa.SUCATA, null, null)
        rfid.cadastrarTag(CadastroRequest(tag = "E-COLOK-$s", codigoInterno = "TI-OK", patrimonio = "P-COLOK-$s"))

        transferir("E-NAO-EXISTE-$s", """{"local":"${sala.nome}"}""").andExpect(status().isNotFound)
            .andExpect(jsonPath("$.mensagem").value("Etiqueta E-NAO-EXISTE-$s não cadastrada"))
        transferir("E-COLB-$s", """{"local":"${sala.nome}"}""").andExpect(status().isConflict)
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("baixado")))
        transferir("E-COLOK-$s", """{"local":"Fechada $s"}""").andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("desativado")))
        transferir("E-COLOK-$s", """{"local":"Inexistente $s"}""").andExpect(status().isBadRequest)
        transferir("E-COLOK-$s", """{}""").andExpect(status().isBadRequest)
        assertThat(tag("E-COLOK-$s").localId).isNull()

        // Operador só com Leituras: 403, e o login diz que ele não pode transferir
        val email = "so-leitura-$s@x.com".lowercase()
        operadores.criarUsuario(email = email)
        val soLeitura = operadores.entrar(mvc, email)
        transferir("E-COLOK-$s", """{"local":"${sala.nome}"}""", soLeitura).andExpect(status().isForbidden)
        assertThat(tag("E-COLOK-$s").localId).isNull()
    }

    @Test
    fun `login diz se o operador pode transferir`() {
        fun login(email: String) = mvc.perform(post("/api/android/login").header(OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR)
            .contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","senha":"${OperadorTeste.SENHA}"}"""))
        val pode = "pode-$s@x.com".lowercase()
        operadores.criarUsuario(email = pode, permissoes = arrayOf(Modulo.LEITURA to Acao.READ, Modulo.RFID to Acao.UPDATE))
        login(pode).andExpect(status().isOk).andExpect(jsonPath("$.podeTransferir").value(true))
        val naoPode = "nao-pode-$s@x.com".lowercase()
        operadores.criarUsuario(email = naoPode)
        login(naoPode).andExpect(status().isOk).andExpect(jsonPath("$.podeTransferir").value(false))
    }
}
