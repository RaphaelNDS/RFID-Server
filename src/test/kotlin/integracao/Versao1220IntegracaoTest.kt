package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.FormatoCampo
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CampoPersonalizadoService
import org.example.service.CatalogoService
import org.example.service.HistoricoEtiquetaService
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import java.util.UUID

/** 1.22.0: campos personalizados por tipo de equipamento. */
class Versao1220IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var campos: CampoPersonalizadoService
    @Autowired lateinit var historico: HistoricoEtiquetaService
    @Autowired lateinit var tags: TagRepository

    private val s = UUID.randomUUID().toString().take(6).uppercase()

    @Test
    fun `cadastro dos campos valida nome, lista e formato`() {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("PDV $s")).id
        assertThatThrownBy { campos.salvarCampo(null, tipo, "Voltagem", FormatoCampo.LISTA, "110V", false, null) }.hasMessageContaining("2 opções")
        val volt = campos.salvarCampo(null, tipo, " Voltagem ", FormatoCampo.LISTA, "110V; 220V;Bivolt;110v", true, null)
        assertThat(volt.nome).isEqualTo("Voltagem")
        assertThat(volt.listaOpcoes).containsExactly("110V", "220V", "Bivolt")
        assertThatThrownBy { campos.salvarCampo(null, tipo, "voltagem", FormatoCampo.TEXTO, null, false, null) }.hasMessageContaining("já tem o campo")

        // Com valor gravado: o formato não muda e a opção em uso não sai da lista
        val epc = "CP0-$s"
        val tag = rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-$epc", patrimonio = "P-$epc", tipoEquipamentoId = tipo))
        val bem = tags.findByTagReal(epc).get()
        campos.gravar(bem, campos.validar(tipo, mapOf(volt.id to "bivolt")))
        assertThatThrownBy { campos.salvarCampo(volt.id, null, "Voltagem", FormatoCampo.TEXTO, null, true, null) }.hasMessageContaining("formato não muda")
        assertThatThrownBy { campos.salvarCampo(volt.id, null, "Voltagem", FormatoCampo.LISTA, "110V;220V", true, null) }.hasMessageContaining("Bivolt está em uso")
        assertThat(tag).isNotNull()
    }

    @Test
    fun `edicao da etiqueta grava os campos, valida, vai para o historico, a busca e a exportacao`() {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Terminal $s")).id
        val volt = campos.salvarCampo(null, tipo, "Voltagem", FormatoCampo.LISTA, "110V;220V;Bivolt", true, null)
        val firmware = campos.salvarCampo(null, tipo, "Firmware", FormatoCampo.TEXTO, null, false, null)
        val tela = campos.salvarCampo(null, tipo, "Tela (pol)", FormatoCampo.NUMERO, null, false, null)
        val garantia = campos.salvarCampo(null, tipo, "Garantia até", FormatoCampo.DATA, null, false, null)
        val touch = campos.salvarCampo(null, tipo, "Touch", FormatoCampo.SIM_NAO, null, false, null)
        val epc = "CP1-$s"
        rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-$epc", patrimonio = "P-$epc", tipoEquipamentoId = tipo))
        val bem = tags.findByTagReal(epc).get()

        fun salvar(vararg campos: Pair<Long, String>) = mvc.perform(post("/admin/etiquetas/${bem.id}/editar").with(Perfis.admin()).with(csrf())
            .param("codigoInterno", "TI-$epc").param("patrimonio", "P-$epc").param("tipoEquipamentoId", "$tipo")
            .param("situacao", "DISPONIVEL").param("versao", "${tags.findByTagReal(epc).get().versao}")
            .apply { campos.forEach { (id, v) -> param("campo_$id", v) } })

        // A tela mostra os campos do tipo
        mvc.perform(get("/admin/etiquetas/${bem.id}/editar").with(Perfis.admin()))
            .andExpect(content().string(containsString("Voltagem"))).andExpect(content().string(containsString("Garantia até")))

        // Obrigatório vazio e número inválido: volta para a tela com o que foi digitado
        salvar(firmware.id to "v2.1", tela.id to "quinze")
            .andExpect(content().string(containsString("Preencha o campo Voltagem")))
            .andExpect(content().string(containsString("Tela (pol): informe um número")))
            .andExpect(content().string(containsString("value=\"v2.1\"")))
        assertThat(campos.doBem(bem).all { it.valor == null }).isTrue()

        salvar(volt.id to "bivolt", firmware.id to "v2.1", tela.id to "15.6", garantia.id to "2027-06-30", touch.id to "Sim")
            .andExpect(flash().attribute("msg", containsString("Voltagem")))
        val valores = campos.doBem(bem).associate { it.campo.nome to it.exibido }
        assertThat(valores).containsEntry("Voltagem", "Bivolt").containsEntry("Tela (pol)", "15,6")
            .containsEntry("Garantia até", "30/06/2027").containsEntry("Touch", "Sim").containsEntry("Firmware", "v2.1")
        assertThat(historico.historico(epc).map { it.campo }).contains("Voltagem", "Firmware", "Garantia até")

        // Apagar um valor também vai para o histórico
        salvar(volt.id to "Bivolt", firmware.id to "", tela.id to "15,6", garantia.id to "2027-06-30", touch.id to "Sim")
        assertThat(campos.doBem(bem).single { it.campo.id == firmware.id }.valor).isNull()

        // Busca pelo valor do campo
        mvc.perform(get("/admin/tags-cadastradas").param("busca", "bivolt").with(Perfis.admin()))
            .andExpect(content().string(containsString(epc)))

        // Exportação: uma coluna por campo
        val csv = mvc.perform(get("/admin/tags-cadastradas/exportar").param("formato", "csv").param("busca", epc).with(Perfis.admin()))
            .andReturn().response.getContentAsString(Charsets.UTF_8)
        assertThat(csv).contains("Voltagem").contains("Garantia até").contains("Bivolt").contains("30/06/2027")

        // Desativado some da edição e da exportação, sem perder o valor
        campos.ativarCampo(volt.id, false)
        mvc.perform(get("/admin/etiquetas/${bem.id}/editar").with(Perfis.admin()))
            .andExpect(content().string(not(containsString("campo_${volt.id}"))))
        campos.ativarCampo(volt.id, true)
        assertThat(campos.doBem(bem).single { it.campo.id == volt.id }.valor).isEqualTo("Bivolt")
    }

    @Test
    fun `tela de campos lista, cria e respeita as permissoes`() {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Balança $s")).id
        mvc.perform(post("/admin/catalogo/campos").with(Perfis.admin()).with(csrf())
            .param("tipoId", "$tipo").param("nome", "Capacidade (kg)").param("formato", "NUMERO"))
            .andExpect(flash().attribute("msg", containsString("Capacidade (kg) criado")))
        mvc.perform(post("/admin/catalogo/campos").with(Perfis.admin()).with(csrf())
            .param("tipoId", "$tipo").param("nome", "Modo").param("formato", "LISTA").param("opcoes", "só um"))
            .andExpect(flash().attribute("erro", containsString("2 opções")))
        mvc.perform(get("/admin/catalogo/campos").with(Perfis.usuario()))
            .andExpect(content().string(containsString("Capacidade (kg)")))
            .andExpect(content().string(not(containsString("Criar campo"))))
        mvc.perform(post("/admin/catalogo/campos").with(Perfis.usuario()).with(csrf()).param("tipoId", "$tipo").param("nome", "X").param("formato", "TEXTO"))
            .andExpect { assertThat(it.response.status).isIn(302, 403) }
        assertThat(campos.tiposComCampos().single { it.tipoId == tipo }.campos.map { it.nome }).containsExactly("Capacidade (kg)")
    }
}
