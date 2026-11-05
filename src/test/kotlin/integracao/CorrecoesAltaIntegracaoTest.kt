package org.example.integracao

import org.example.request.CadastroRequest
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** Correções de prioridade alta da análise de 04/11/2026. */
class CorrecoesAltaIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var rfid: RfidService

    @Test
    fun `cadastro recusado volta com o tipo, a marca e o modelo escolhidos`() {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest(nome = "Notebook")).id
        val marca = catalogo.salvarMarca(MarcaRequest(nome = "Dell", tipoId = tipo)).id
        val modelo = catalogo.salvarModelo(ModeloRequest(nome = "Latitude", marcaId = marca)).id
        rfid.cadastrarTag(CadastroRequest(tag = "E-PRIMEIRA", codigoInterno = "TI-1", patrimonio = "PAT-1"))

        // Patrimônio repetido: o servidor recusa e a tela volta com o que foi digitado
        mvc.perform(
            post("/admin/cadastro/salvar").with(Perfis.admin()).with(csrf())
                .param("tag", "E-SEGUNDA").param("codigoInterno", "TI-2").param("patrimonio", "PAT-1")
                .param("tipoEquipamentoId", "$tipo").param("marcaId", "$marca").param("modeloId", "$modelo")
        ).andExpect(status().isOk)
            .andExpect(content().string(containsString("já está em outra etiqueta")))
            .andExpect(content().string(matchesPattern("(?s).*<option value=\"$tipo\" selected=\"selected\">Notebook</option>.*")))
            .andExpect(content().string(containsString("data-inicial=\"$marca\"")))
            .andExpect(content().string(containsString("data-inicial=\"$modelo\"")))
            // O script comum que recarrega marca e modelo ao abrir
            .andExpect(content().string(containsString("marcaSelect.dataset.inicial")))
    }

    @Test
    fun `dashboard avisa das leituras novas em vez de recarregar a pagina sozinho`() {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/dashboard").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"avisoLeiturasNovas\"")))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("evt.onmessage = () => location.reload()"))))
    }

    @Test
    fun `arquivo enviado sem login e recusado com 403 antes de qualquer outra coisa`() {
        mvc.perform(
            multipart("/login").file(MockMultipartFile("arquivo", "grande.bin", "application/octet-stream", ByteArray(1024)))
        ).andExpect(status().isForbidden)

        // A cadeia dos coletores também não aceita arquivo
        mvc.perform(
            multipart("/api/android/leituras/lote").file(MockMultipartFile("arquivo", "x.bin", "application/octet-stream", ByteArray(10)))
                .header(org.example.suporte.OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR)
        ).andExpect(status().isForbidden)
    }
}
