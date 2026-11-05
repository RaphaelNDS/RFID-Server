package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.TagNaoCadastradaEntity
import org.example.repository.TagNaoCadastradaRepository
import org.example.request.CadastroRequest
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

/** Telas de etiquetas e pendentes: busca no banco (não só na página) e paginação de 50 em 50. */
class EtiquetasPaginadasIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var naoCadastradas: TagNaoCadastradaRepository

    private var notebook = 0L
    private var dell = 0L
    private var latitude = 0L
    private var monitor = 0L
    private var lg = 0L
    private var ultrawide = 0L

    @BeforeEach
    fun catalogo() {
        notebook = catalogo.salvarTipo(TipoEquipamentoRequest(nome = "Notebook")).id
        dell = catalogo.salvarMarca(MarcaRequest(nome = "Dell", tipoId = notebook)).id
        latitude = catalogo.salvarModelo(ModeloRequest(nome = "Latitude", marcaId = dell)).id
        monitor = catalogo.salvarTipo(TipoEquipamentoRequest(nome = "Monitor")).id
        lg = catalogo.salvarMarca(MarcaRequest(nome = "LG", tipoId = monitor)).id
        ultrawide = catalogo.salvarModelo(ModeloRequest(nome = "UltraWide", marcaId = lg)).id
    }

    private fun etiqueta(epc: String, patrimonio: String, eMonitor: Boolean = false) {
        val tipo = if (eMonitor) monitor else notebook
        rfid.cadastrarTag(CadastroRequest(epc, "TI-$epc", patrimonio, "SN-$epc", tipo,
            if (eMonitor) lg else dell, if (eMonitor) ultrawide else latitude))
    }

    @Test
    fun `busca pelo nome da marca acha as etiquetas daquela marca`() {
        etiqueta("E-NB-1", "PAT-1")
        etiqueta("E-MON-1", "PAT-2", eMonitor = true)

        val pagina = rfid.listarParaAdmin("lg", 0)

        assertThat(pagina.content.map { it.tag }).containsExactly("E-MON-1")
    }

    @Test
    fun `busca por patrimonio e por parte do EPC, sem diferenciar maiusculas`() {
        etiqueta("E2801170AAAA", "PAT-77")
        etiqueta("E2801170BBBB", "PAT-88")

        assertThat(rfid.listarParaAdmin("pat-88", 0).content.map { it.tag }).containsExactly("E2801170BBBB")
        assertThat(rfid.listarParaAdmin("aaaa", 0).content.map { it.tag }).containsExactly("E2801170AAAA")
        assertThat(rfid.listarParaAdmin("", 0).totalElements).isEqualTo(2)
    }

    @Test
    fun `porcentagem e sublinhado na busca sao texto, nao curinga`() {
        etiqueta("E-PCT", "10%OFF")
        etiqueta("E-OUTRA", "10XOFF")

        assertThat(rfid.listarParaAdmin("10%", 0).content.map { it.tag }).containsExactly("E-PCT")
        assertThat(rfid.listarParaAdmin("_", 0).content).isEmpty()
    }

    @Test
    fun `tela de etiquetas pagina de 50 em 50 e o link da proxima pagina leva a busca`() {
        (1..55).forEach { etiqueta("E-PAG-$it", "PAT-$it") }

        mvc.perform(get("/admin/tags-cadastradas").param("busca", "e-pag").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Página 1 de 2")))
            .andExpect(content().string(containsString("55 etiqueta(s)")))
            .andExpect(content().string(containsString("/admin/tags-cadastradas?busca=e-pag&amp;pagina=2")))
            // Mais recentes primeiro: a 55 está na 1ª página, a 1 só na 2ª
            .andExpect(content().string(containsString("E-PAG-55<")))
            .andExpect(content().string(not(containsString("E-PAG-1<"))))

        mvc.perform(get("/admin/tags-cadastradas").param("busca", "e-pag").param("pagina", "2").with(Perfis.admin()))
            .andExpect(content().string(containsString("Página 2 de 2")))
            .andExpect(content().string(containsString("E-PAG-1<")))
    }

    @Test
    fun `pagina alem da ultima mostra a ultima`() {
        etiqueta("E-UNICA", "PAT-U")

        mvc.perform(get("/admin/tags-cadastradas").param("pagina", "99").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("E-UNICA")))
    }

    @Test
    fun `busca sem resultado mostra o aviso de nada encontrado`() {
        etiqueta("E-QUALQUER", "PAT-Q")

        mvc.perform(get("/admin/tags-cadastradas").param("busca", "nao-existe").with(Perfis.admin()))
            .andExpect(content().string(containsString("Nada encontrado")))
            .andExpect(content().string(not(containsString("Nenhuma etiqueta cadastrada"))))
    }

    @Test
    fun `pendentes saem da mais recente para a mais antiga e a busca filtra pelo EPC`() {
        val agora = LocalDateTime.now()
        naoCadastradas.saveAll(listOf(
            TagNaoCadastradaEntity(tag = "ANTIGA", dataHora = agora.minusDays(2)),
            TagNaoCadastradaEntity(tag = "NOVA", dataHora = agora),
            TagNaoCadastradaEntity(tag = "MEIO", dataHora = agora.minusDays(1))
        ))

        assertThat(rfid.listarNaoCadastradas(null, 0).content.map { it.tag }).containsExactly("NOVA", "MEIO", "ANTIGA")
        assertThat(rfid.listarNaoCadastradas("ei", 0).content.map { it.tag }).containsExactly("MEIO")
    }

    @Test
    fun `tela de pendentes mostra o total e pagina`() {
        val agora = LocalDateTime.now()
        naoCadastradas.saveAll((1..51).map { TagNaoCadastradaEntity(tag = "E-PEND-$it", dataHora = agora.minusMinutes(it.toLong())) })

        mvc.perform(get("/admin/tags-pendentes").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("(51)")))
            .andExpect(content().string(containsString("Página 1 de 2")))
            .andExpect(content().string(containsString("/admin/tags-pendentes?pagina=2")))
    }
}
