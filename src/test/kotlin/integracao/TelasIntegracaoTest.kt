package org.example.integracao

import org.example.model.LeituraAndroidEntity
import org.example.repository.LeituraAndroidRepository
import org.example.request.CadastroRequest
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.SetupAdminRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.RfidService
import org.example.service.UsuarioService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

/**
 * Abre cada tela com dados reais e confere que o template renderiza.
 * Pega erros de Thymeleaf que só aparecem em tempo de execução (expressão inválida, atributo faltando etc.).
 */
class TelasIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var usuarios: UsuarioService
    @Autowired lateinit var leituras: LeituraAndroidRepository

    @BeforeEach
    fun popular() {
        usuarios.criarAdminInicial(SetupAdminRequest("Ana Admin", "admin@x.com", "Kx9mPq2v"))
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest(nome = "Notebook"))
        val marca = catalogo.salvarMarca(MarcaRequest(nome = "Dell", tipoId = tipo.id))
        val modelo = catalogo.salvarModelo(ModeloRequest(nome = "Latitude 5440", marcaId = marca.id))
        rfid.cadastrarTag(CadastroRequest("E2801170AAAA", "TI-001", "PAT-101", "SN1", tipo.id, marca.id, modelo.id))
        rfid.salvarNaoCadastrada("E2004700PENDENTE")
        leituras.save(LeituraAndroidEntity(tag = "E2801170AAAA", codigoInterno = "TI-001", tipo = "Notebook",
            marca = "Dell", modelo = "Latitude 5440", patrimonio = "PAT-101", numeroSerie = "SN1",
            dataHora = LocalDateTime.now().minusMinutes(5)))
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "/dashboard,                     Acesso rápido",
        "/admin,                         Painel administrativo",
        "/admin/tags-cadastradas,        E2801170AAAA",
        "/admin/tags-pendentes,          E2004700PENDENTE",
        "/admin/cadastro,                Cadastrar etiqueta",
        "/admin/cadastro?tag=E2004700X,  E2004700X",
        "/admin/leituras-android,        PAT-101",
        "/admin/catalogo,                Latitude 5440",
        "/admin/catalogo/tipos,          Notebook",
        "/admin/catalogo/marcas,         Dell",
        "/admin/catalogo/modelos,        Latitude 5440",
        "/admin/tipo-equipamento/form,   Novo tipo de equipamento",
        "/admin/dashboard,               Relatórios de leituras",
        "/admin/usuarios,                admin@x.com",
        "/admin/usuarios/novo,           Novo usuário",
        "/status,                        Status do servidor",
        "/perfil,                        Meu perfil",
        "/perfil/senha,                  Alterar senha",
        "/perfil/email,                  Alterar email",
        "/cadastro/form,                 Cadastrar etiqueta"
    )
    fun `tela abre e mostra o conteudo esperado`(url: String, textoEsperado: String) {
        mvc.perform(get(url).with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString(textoEsperado)))
    }

    @Test
    fun `login abre para visitante`() {
        mvc.perform(get("/login"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Entrar")))
            .andExpect(content().string(not(containsString("Email ou senha incorretos"))))
    }

    @Test
    fun `login com erro mostra o aviso`() {
        mvc.perform(get("/login").param("error", ""))
            .andExpect(content().string(containsString("Email ou senha incorretos")))
    }

    @Test
    fun `menu mostra so o que o perfil pode acessar`() {
        mvc.perform(get("/dashboard").with(Perfis.somenteCatalogo()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("/admin/catalogo")))
            .andExpect(content().string(not(containsString("href=\"/admin/cadastro\""))))
            .andExpect(content().string(not(containsString("href=\"/admin/usuarios\""))))
    }

    @Test
    fun `relatorio usa o Chart_js local, sem depender de internet`() {
        mvc.perform(get("/admin/dashboard").with(Perfis.admin()))
            .andExpect(content().string(containsString("/js/chart.umd.min.js")))
            .andExpect(content().string(not(containsString("cdn.jsdelivr.net"))))
    }

    @Test
    fun `arquivos estaticos ficam disponiveis`() {
        listOf("/css/app.css", "/js/app.js", "/js/chart.umd.min.js", "/img/icones.svg", "/img/favicon.svg").forEach {
            mvc.perform(get(it)).andExpect(status().isOk)
        }
    }
}
