package org.example.integracao

import org.example.request.CadastroRequest
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.comoOperador
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * A API dos coletores de ponta a ponta FORA da transação do teste (1.5.2). Sem o "open in view" em /api/android, um
 * dado lazy lido fora da transação dos serviços daria LazyInitializationException no coletor de verdade. Os outros
 * testes não pegariam isso: a transação deles mantém a sessão do JPA aberta durante a requisição inteira.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApiColetorSemTransacaoIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var operadores: OperadorTeste
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var jdbc: JdbcTemplate

    private val sufixo = UUID.randomUUID().toString().take(8).uppercase()
    private val email = "semtx-$sufixo@x.com".lowercase()
    private val tag = "E-SEMTX-$sufixo"
    private val local = "Sala SemTx $sufixo"
    private val inventario = UUID.randomUUID().toString()
    private val ids = mutableMapOf<String, Long>()

    @AfterEach
    fun apagar() {
        jdbc.update("delete from inventario_justificativas where inventario_id = ?", inventario)
        jdbc.update("delete from inventario_itens where inventario_id = ?", inventario)
        jdbc.update("delete from inventarios where id = ?", inventario)
        jdbc.update("delete from locais where nome = ?", local)
        jdbc.update("delete from leituras_android where tag like ?", "$tag%")
        jdbc.update("delete from tags_nao_cadastradas where tag like ?", "$tag%")
        jdbc.update("delete from historico_etiquetas where tag_real like ?", "$tag%")
        jdbc.update("delete from tags where tag_real like ?", "$tag%")
        ids["modelo"]?.let { jdbc.update("delete from modelo where id = ?", it) }
        ids["marca"]?.let { jdbc.update("delete from marca where id = ?", it) }
        ids["tipo"]?.let { jdbc.update("delete from tipo_equipamento where id = ?", it) }
        jdbc.update("delete from erros_coletor where mensagem like ?", "%$sufixo%")
        // O cadastro do catálogo fica na auditoria: fora da transação, sem isto ficaria para os outros testes
        jdbc.update("delete from auditoria where alvo like ? or detalhe like ?", "%$sufixo%", "%$sufixo%")
        jdbc.update("delete from permissoes where usuario_id in (select id from usuarios where email = ?)", email)
        jdbc.update("delete from usuarios where email = ?", email)
        jdbc.update("delete from coletores where nome = ?", "coletor-teste")
    }

    private fun json(url: String, corpo: String, token: String) =
        mvc.perform(post(url).comoOperador(token).contentType(MediaType.APPLICATION_JSON).content(corpo))

    @Test
    fun `rotas do coletor funcionam sem a sessao do JPA na requisicao`() {
        // Catálogo com tipo, marca e modelo: o modelo aponta para a marca e a marca para o tipo (relações lazy)
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest(nome = "Notebook $sufixo")).also { ids["tipo"] = it.id }
        val marca = catalogo.salvarMarca(MarcaRequest(nome = "Dell $sufixo", tipoId = tipo.id)).also { ids["marca"] = it.id }
        val modelo = catalogo.salvarModelo(ModeloRequest(nome = "Latitude $sufixo", marcaId = marca.id)).also { ids["modelo"] = it.id }
        rfid.cadastrarTag(CadastroRequest(tag = tag, codigoInterno = "TI-$sufixo", patrimonio = "PAT-$sufixo",
            tipoEquipamentoId = tipo.id, marcaId = marca.id, modeloId = modelo.id))
        operadores.criarUsuario(email = email, permissoes = arrayOf(
            org.example.model.Modulo.LEITURA to org.example.model.Acao.READ, org.example.model.Modulo.RFID to org.example.model.Acao.CREATE))
        val token = operadores.entrar(mvc, email)

        mvc.perform(get("/api/android/ping").comoOperador(token)).andExpect(status().isOk)
        mvc.perform(get("/api/android/catalogo").comoOperador(token)).andExpect(status().isOk)
        mvc.perform(get("/api/android/tags/$tag").comoOperador(token))
            .andExpect(status().isOk).andExpect(jsonPath("$.modelo").value("Latitude $sufixo"))
        mvc.perform(get("/api/android/tags/patrimonio").param("valor", "PAT-$sufixo").comoOperador(token)).andExpect(status().isOk)
        json("/api/android/leitura", """{"tag":"$tag"}""", token).andExpect(status().isOk)
        json("/api/android/leituras/lote", """{"itens":[{"tag":"$tag"},{"tag":"$tag-NOVA"}]}""", token)
            .andExpect(status().isOk).andExpect(jsonPath("$.resultados[0].status").value("CADASTRADA"))
        json("/api/android/naocadastrada", """{"tag":"$tag-OUTRA"}""", token).andExpect(status().isOk)

        json("/api/android/inventarios", """{"id":"$inventario","local":"$local"}""", token).andExpect(status().isOk)
        mvc.perform(get("/api/android/locais").comoOperador(token)).andExpect(status().isOk)
        json("/api/android/inventarios/$inventario/itens", """{"itens":[{"tag":"$tag"}]}""", token).andExpect(status().isOk)
        json("/api/android/inventarios/$inventario/finalizar", "{}", token).andExpect(status().isOk)
        mvc.perform(get("/api/android/inventarios/esperado").param("local", local).comoOperador(token))
            .andExpect(status().isOk).andExpect(jsonPath("$.itens[0].modelo").value("Latitude $sufixo"))
        json("/api/android/inventarios/$inventario/justificativas", """[{"tag":"$tag-X","motivo":"OUTRO"}]""", token).andExpect(status().isOk)

        mvc.perform(post("/api/android/erros").header(OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR).contentType(MediaType.APPLICATION_JSON)
            .content("""{"itens":[{"id":"${UUID.randomUUID()}","tipo":"LEITOR_RFID","mensagem":"teste $sufixo"}]}"""))
            .andExpect(status().isAccepted)
        mvc.perform(get("/api/android/atualizacao").comoOperador(token)).andExpect(status().is2xxSuccessful)
        mvc.perform(post("/api/android/logout").comoOperador(token)).andExpect(status().isOk)
    }
}
