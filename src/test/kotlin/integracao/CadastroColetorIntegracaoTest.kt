package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.comoOperador
import org.example.model.Acao
import org.example.model.Modulo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** Cadastro de etiqueta pelo coletor: permissão por coletor, catálogo e reenvio sem erro. */
class CadastroColetorIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfidService: RfidService
    @Autowired lateinit var catalogo: CatalogoService

    private var tipoId = 0L
    private var marcaId = 0L
    private var modeloId = 0L

    // coletor-teste está liberado em application-test.yml; coletor-leitura tem chave válida mas não está
    private val chaveLeitura = "chave-de-leitura-0123456789"

    @Autowired lateinit var operadores: OperadorTeste

    // Operador com Leituras e cadastro (RFID/CREATE), logado nos dois coletores
    private lateinit var token: String
    private lateinit var tokenLeitura: String

    @BeforeEach
    fun catalogo() {
        tipoId = catalogo.salvarTipo(TipoEquipamentoRequest(nome = "Notebook")).id
        marcaId = catalogo.salvarMarca(MarcaRequest(nome = "Dell", tipoId = tipoId)).id
        modeloId = catalogo.salvarModelo(ModeloRequest(nome = "Latitude", marcaId = marcaId)).id

        operadores.criarUsuario(email = "cadastrador@x.com", permissoes = arrayOf(Modulo.LEITURA to Acao.READ, Modulo.RFID to Acao.CREATE))
        token = operadores.entrar(mvc, "cadastrador@x.com")
        tokenLeitura = operadores.entrar(mvc, "cadastrador@x.com", chave = chaveLeitura)
    }

    private fun sessaoDa(chave: String) = if (chave == chaveLeitura) tokenLeitura else token

    private fun cadastrar(json: String, chave: String = CHAVE_COLETOR, sessao: String = sessaoDa(chave)): ResultActions =
        mvc.perform(post("/api/android/tags").comoOperador(sessao, chave)
            .contentType(MediaType.APPLICATION_JSON).content(json))

    private fun completo(tag: String = "E-COLETOR", patrimonio: String = "PAT-1") = """
        {"tag":"$tag","codigoInterno":"TI-1","patrimonio":"$patrimonio","numeroSerie":"SN-1",
         "tipoEquipamentoId":$tipoId,"marcaId":$marcaId,"modeloId":$modeloId}
    """

    @Test
    fun `catalogo vem em arvore com a permissao do coletor`() {
        mvc.perform(get("/api/android/catalogo").comoOperador(token))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.podeCadastrar").value(true))
            .andExpect(jsonPath("$.tipos[0].nome").value("Notebook"))
            .andExpect(jsonPath("$.tipos[0].marcas[0].nome").value("Dell"))
            .andExpect(jsonPath("$.tipos[0].marcas[0].modelos[0].nome").value("Latitude"))
            .andExpect(jsonPath("$.tipos[0].marcas[0].modelos[0].id").value(modeloId))

        mvc.perform(get("/api/android/catalogo").comoOperador(tokenLeitura, chaveLeitura))
            .andExpect(jsonPath("$.podeCadastrar").value(false))
    }

    @Test
    fun `coletor liberado cadastra e a etiqueta sai das pendentes`() {
        rfidService.salvarNaoCadastrada("E-COLETOR")

        cadastrar(completo())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.resultado").value("CADASTRADA"))

        val tag = rfidService.buscarPorTag("E-COLETOR")!!
        assertThat(tag.patrimonio).isEqualTo("PAT-1")
        assertThat(tag.modelo).isEqualTo("Latitude")
        assertThat(rfidService.listarNaoCadastradas().map { it.tag }).doesNotContain("E-COLETOR")
    }

    @Test
    fun `reenviar o mesmo cadastro responde sucesso sem duplicar`() {
        cadastrar(completo()).andExpect(status().isOk)

        cadastrar(completo())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.resultado").value("JA_CADASTRADA"))
    }

    @Test
    fun `mesma etiqueta com outros dados e recusada informando o patrimonio`() {
        cadastrar(completo(patrimonio = "PAT-1")).andExpect(status().isOk)

        cadastrar(completo(patrimonio = "PAT-2"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.mensagem").value("Esta etiqueta já está cadastrada (patrimônio PAT-1)"))
    }

    @Test
    fun `coletor fora da lista recebe 403 com a mensagem`() {
        cadastrar(completo(), chave = chaveLeitura)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.mensagem").value("O coletor coletor-leitura não tem permissão para cadastrar etiquetas"))

        assertThat(rfidService.buscarPorTag("E-COLETOR")).isNull()
    }

    @Test
    fun `regras do cadastro da web valem no coletor`() {
        cadastrar("""{"tag":"E1","codigoInterno":"  "}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.mensagem").value("Informe o código interno"))

        cadastrar("""{"tag":"E1","codigoInterno":"TI","marcaId":$marcaId}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.mensagem").value("Selecione o tipo de equipamento da marca Dell"))

        cadastrar("""{"tag":"E1","codigoInterno":"TI","tipoEquipamentoId":$tipoId,"marcaId":$marcaId,"modeloId":999999}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.mensagem").value("Modelo não encontrado"))
    }

    @Test
    fun `espacos nas pontas sao removidos`() {
        cadastrar("""{"tag":"  E-ESP  ","codigoInterno":" TI-9 ","patrimonio":" P9 "}""").andExpect(status().isOk)

        assertThat(rfidService.buscarPorTag("E-ESP")?.patrimonio).isEqualTo("P9")
    }

    @Test
    fun `cadastro pelo coletor exige a chave`() {
        mvc.perform(post("/api/android/tags").contentType(MediaType.APPLICATION_JSON).content(completo()))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `operador sem a permissao de cadastro da web recebe 403 mesmo no coletor liberado`() {
        operadores.criarUsuario(email = "leitor@x.com") // só Leituras
        val semCadastro = operadores.entrar(mvc, "leitor@x.com")

        cadastrar(completo(), sessao = semCadastro)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.mensagem").value("Seu usuário não tem permissão para esta ação"))

        assertThat(rfidService.buscarPorTag("E-COLETOR")).isNull()
    }
}
