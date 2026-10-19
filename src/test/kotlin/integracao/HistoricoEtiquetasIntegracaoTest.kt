package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.AcaoHistorico
import org.example.model.Acao
import org.example.model.Modulo
import org.example.repository.HistoricoEtiquetaRepository
import org.example.repository.TagRepository
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
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
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** Edição, exclusão e histórico do cadastro de etiquetas (web e coletor). */
class HistoricoEtiquetasIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var historico: HistoricoEtiquetaRepository
    @Autowired lateinit var operadores: OperadorTeste

    private var notebook = 0L
    private var dell = 0L
    private var latitude = 0L
    private var monitor = 0L
    private var lg = 0L
    private var ultrawide = 0L

    @BeforeEach
    fun catalogo() {
        notebook = catalogo.salvarTipo(TipoEquipamentoRequest(nome = "Notebook H")).id
        dell = catalogo.salvarMarca(MarcaRequest(nome = "Dell H", tipoId = notebook)).id
        latitude = catalogo.salvarModelo(ModeloRequest(nome = "Latitude H", marcaId = dell)).id
        monitor = catalogo.salvarTipo(TipoEquipamentoRequest(nome = "Monitor H")).id
        lg = catalogo.salvarMarca(MarcaRequest(nome = "LG H", tipoId = monitor)).id
        ultrawide = catalogo.salvarModelo(ModeloRequest(nome = "UltraWide H", marcaId = lg)).id
    }

    private fun cadastrarPelaWeb(epc: String, patrimonio: String = "PAT-1") {
        mvc.perform(
            post("/admin/cadastro/salvar").with(Perfis.admin()).with(csrf())
                .param("tag", epc).param("codigoInterno", "TI-1").param("patrimonio", patrimonio).param("numeroSerie", "SN-1")
                .param("tipoEquipamentoId", notebook.toString()).param("marcaId", dell.toString()).param("modeloId", latitude.toString())
        ).andExpect(redirectedUrl("/admin/tags-cadastradas"))
    }

    private fun idDe(epc: String) = tags.findByTagReal(epc).get().id

    private fun editar(id: Long, patrimonio: String, tipo: Long = notebook, marca: Long = dell, modelo: Long = latitude, perfil: RequestPostProcessor = Perfis.admin()) =
        mvc.perform(
            post("/admin/etiquetas/$id/editar").with(perfil).with(csrf())
                .param("codigoInterno", "TI-1").param("patrimonio", patrimonio).param("numeroSerie", "SN-1")
                .param("tipoEquipamentoId", tipo.toString()).param("marcaId", marca.toString()).param("modeloId", modelo.toString())
        )

    private fun epc() = "E-HIST-${UUID.randomUUID().toString().take(8)}"

    @Test
    fun `cadastro pela web fica no historico com quem fez e os nomes do catalogo`() {
        val epc = epc()
        cadastrarPelaWeb(epc)

        val linha = historico.findByTagRealOrderByMomentoDescIdDesc(epc).single()
        assertThat(linha.acao).isEqualTo(AcaoHistorico.CADASTRO)
        assertThat(linha.usuario).isEqualTo("admin@x.com")
        assertThat(linha.origem).isEqualTo("Web")
        assertThat(linha.valorNovo).contains("Patrimônio: PAT-1").contains("Tipo: Notebook H").contains("Modelo: Latitude H")
    }

    @Test
    fun `cadastro pelo coletor registra o operador e o coletor`() {
        operadores.criarUsuario(email = "cad-hist@x.com", nome = "Carla Coletora", permissoes = arrayOf(Modulo.LEITURA to Acao.READ, Modulo.RFID to Acao.CREATE))
        val token = operadores.entrar(mvc, "cad-hist@x.com")
        val epc = epc()

        mvc.perform(
            post("/api/android/tags").comoOperador(token).contentType(MediaType.APPLICATION_JSON)
                .content("""{"tag":"$epc","codigoInterno":"TI-9","patrimonio":"PAT-9","numeroSerie":"SN-9"}""")
        ).andExpect(status().isOk)

        val linha = historico.findByTagRealOrderByMomentoDescIdDesc(epc).single()
        assertThat(linha.usuario).isEqualTo("Carla Coletora")
        assertThat(linha.origem).isEqualTo("Coletor coletor-teste")
    }

    @Test
    fun `edicao grava uma linha por campo que mudou com antes e depois`() {
        val epc = epc()
        cadastrarPelaWeb(epc)

        editar(idDe(epc), patrimonio = "PAT-2", tipo = monitor, marca = lg, modelo = ultrawide)
            .andExpect(redirectedUrl("/admin/tags-cadastradas"))
            .andExpect(flash().attribute("msg", containsString("Patrimônio")))

        val alteracoes = historico.findByTagRealOrderByMomentoDescIdDesc(epc).filter { it.acao == AcaoHistorico.ALTERACAO }.associateBy { it.campo }
        assertThat(alteracoes.keys).containsExactlyInAnyOrder("Patrimônio", "Tipo", "Marca", "Modelo")
        assertThat(alteracoes.getValue("Patrimônio").valorAntigo).isEqualTo("PAT-1")
        assertThat(alteracoes.getValue("Patrimônio").valorNovo).isEqualTo("PAT-2")
        assertThat(alteracoes.getValue("Marca").valorAntigo).isEqualTo("Dell H")
        assertThat(alteracoes.getValue("Marca").valorNovo).isEqualTo("LG H")
        assertThat(tags.findByTagReal(epc).get().patrimonio).isEqualTo("PAT-2")
    }

    @Test
    fun `salvar sem mudar nada nao gera linha de alteracao`() {
        val epc = epc()
        cadastrarPelaWeb(epc)

        editar(idDe(epc), patrimonio = "PAT-1").andExpect(flash().attribute("msg", containsString("Nada foi alterado")))

        assertThat(historico.findByTagRealOrderByMomentoDescIdDesc(epc).map { it.acao }).containsExactly(AcaoHistorico.CADASTRO)
    }

    @Test
    fun `edicao com catalogo incoerente e recusada e nada vai para o historico`() {
        val epc = epc()
        cadastrarPelaWeb(epc)

        editar(idDe(epc), patrimonio = "PAT-X", tipo = notebook, marca = lg, modelo = ultrawide)
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("não pertence ao tipo de equipamento")))

        assertThat(tags.findByTagReal(epc).get().patrimonio).isEqualTo("PAT-1")
        assertThat(historico.findByTagRealOrderByMomentoDescIdDesc(epc)).hasSize(1)
    }

    @Test
    fun `exclusao remove a etiqueta e o historico continua com um retrato`() {
        val epc = epc()
        cadastrarPelaWeb(epc)

        mvc.perform(post("/admin/etiquetas/${idDe(epc)}/excluir").with(Perfis.admin()).with(csrf()))
            .andExpect(redirectedUrl("/admin/tags-cadastradas"))

        assertThat(tags.findByTagReal(epc)).isEmpty
        val exclusao = historico.findByTagRealOrderByMomentoDescIdDesc(epc).first()
        assertThat(exclusao.acao).isEqualTo(AcaoHistorico.EXCLUSAO)
        assertThat(exclusao.valorAntigo).contains("Patrimônio: PAT-1")

        mvc.perform(get("/admin/etiquetas/historico").param("tag", epc).with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Excluiu")))
            .andExpect(content().string(containsString("Cadastrou")))
    }

    @Test
    fun `permissoes editar exige RFID Editar e excluir exige RFID Excluir`() {
        val epc = epc()
        cadastrarPelaWeb(epc)
        val id = idDe(epc)

        editar(id, patrimonio = "PAT-U", perfil = Perfis.usuario()).andExpect(status().isForbidden)   // só Ver
        editar(id, patrimonio = "PAT-G", perfil = Perfis.gestor()).andExpect(redirectedUrl("/admin/tags-cadastradas"))
        mvc.perform(post("/admin/etiquetas/$id/excluir").with(Perfis.gestor()).with(csrf())).andExpect(status().isForbidden)
        assertThat(tags.findByTagReal(epc)).isPresent
    }

    @Test
    fun `lista mostra editar e excluir so para quem pode`() {
        cadastrarPelaWeb(epc())

        mvc.perform(get("/admin/tags-cadastradas").with(Perfis.admin()))
            .andExpect(content().string(containsString("/editar")))
            .andExpect(content().string(containsString("/excluir")))
        mvc.perform(get("/admin/tags-cadastradas").with(Perfis.usuario()))
            .andExpect(content().string(not(containsString("/editar"))))
            .andExpect(content().string(not(containsString("/excluir"))))
            .andExpect(content().string(containsString("/admin/etiquetas/historico")))
    }

    @Test
    fun `tela de edicao abre com os dados atuais`() {
        val epc = epc()
        cadastrarPelaWeb(epc, patrimonio = "PAT-TELA")

        mvc.perform(get("/admin/etiquetas/${idDe(epc)}/editar").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("PAT-TELA")))
            .andExpect(content().string(containsString(epc)))
    }
}
