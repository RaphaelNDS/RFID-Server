package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.ErroColetorEntity
import org.example.repository.ErroColetorRepository
import org.example.service.ConfiguracaoSistemaService
import org.example.service.DicasErroColetor
import org.example.service.ModuloOpcional
import org.example.service.PrimeirosPassosService
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.UUID

/** 1.30.0: primeiros passos, telas no menu e dicas dos erros do coletor. */
class Versao1300IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var config: ConfiguracaoSistemaService
    @Autowired lateinit var primeirosPassos: PrimeirosPassosService
    @Autowired lateinit var erros: ErroColetorRepository

    private val s = UUID.randomUUID().toString().take(6).uppercase()

    @Test
    fun `dicas dos erros conhecidos do coletor`() {
        val zebra = "Gatilho apertado e a leitura não começou: Charging in Progress-Command Not allowed (OPERACAO_EM_ANDAMENTO)"
        assertThat(DicasErroColetor.dica("LEITOR_RFID", zebra)).contains("carregador")
        assertThat(DicasErroColetor.dica("LEITOR_RFID", "Command Not allowed (OPERACAO_EM_ANDAMENTO)")).contains("solte o gatilho")
        assertThat(DicasErroColetor.dica("SINCRONIZACAO", "java.net.SocketTimeoutException: timeout")).contains("Wi-Fi")
        assertThat(DicasErroColetor.dica("ENVIO_RECUSADO", "x", "javax.net.ssl.SSLHandshakeException: Trust anchor for certification path not found")).contains("certificado")
        assertThat(DicasErroColetor.dica("RELOGIO", "diferença de 3 h")).contains("data, a hora")
        assertThat(DicasErroColetor.dica("FALHA_GERAL", "NullPointerException")).isNull()

        val coletor = "coletor-dica-$s"
        erros.save(ErroColetorEntity(id = UUID.randomUUID().toString(), coletor = coletor, momento = LocalDateTime.now(),
            recebidoEm = LocalDateTime.now(), tipo = "LEITOR_RFID", mensagem = zebra))
        mvc.perform(get("/admin/coletores/$coletor/erros").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("tire do carregador para ler as etiquetas")))
            .andExpect(content().string(containsString("Charging in Progress")))
    }

    @Test
    fun `telas fora do menu somem do menu de todos mas a tela continua, e so o admin muda`() {
        mvc.perform(get("/dashboard").with(Perfis.usuario())).andExpect(content().string(containsString("Agenda de entregas")))

        val ligados = (ModuloOpcional.entries - ModuloOpcional.AGENDA - ModuloOpcional.MANUTENCOES).map { it.name }
        mvc.perform(post("/admin/personalizar/menu").with(Perfis.admin()).with(csrf()).param("ligados", *ligados.toTypedArray()))
            .andExpect(status().is3xxRedirection)
        assertThat(config.desligados()).containsExactlyInAnyOrder(ModuloOpcional.AGENDA, ModuloOpcional.MANUTENCOES)

        mvc.perform(get("/dashboard").with(Perfis.usuario()))
            .andExpect(content().string(not(containsString("Agenda de entregas"))))
            .andExpect(content().string(not(containsString("icones.svg#lapis\"/></svg>Manutenções"))))
            .andExpect(content().string(containsString("Disponibilidade")))
        mvc.perform(get("/admin/agenda").with(Perfis.usuario())).andExpect(status().isOk)   // a tela continua
        mvc.perform(get("/admin/personalizar").with(Perfis.admin()))
            .andExpect(status().isOk).andExpect(content().string(containsString("Telas no menu")))

        // Só o administrador
        mvc.perform(get("/admin/personalizar").with(Perfis.gestor())).andExpect(status().isForbidden)
        mvc.perform(post("/admin/personalizar/menu").with(Perfis.gestor()).with(csrf())).andExpect(status().isForbidden)
        assertThat(config.desligados()).hasSize(2)

        // Marcar tudo traz de volta
        mvc.perform(post("/admin/personalizar/menu").with(Perfis.admin()).with(csrf()).param("ligados", *ModuloOpcional.entries.map { it.name }.toTypedArray()))
        assertThat(config.desligados()).isEmpty()
        mvc.perform(get("/dashboard").with(Perfis.usuario())).andExpect(content().string(containsString("Agenda de entregas")))
            .andExpect(content().string(containsString("icones.svg#lapis\"/></svg>Manutenções")))
    }

    @Test
    fun `primeiros passos aparecem para o admin ate ele ocultar`() {
        val passos = primeirosPassos.passos()
        assertThat(passos.total).isEqualTo(8)
        // Sem rfid.backup.pasta nos testes: o backup fora do computador falta, então o quadro aparece
        assertThat(passos.passos.single { it.titulo == "Backup fora do computador" }.feito).isFalse()

        mvc.perform(get("/dashboard").with(Perfis.admin())).andExpect(content().string(containsString("Ocultar este quadro")))
        mvc.perform(get("/dashboard").with(Perfis.usuario())).andExpect(content().string(not(containsString("Ocultar este quadro"))))

        mvc.perform(post("/admin/personalizar/primeiros-passos").with(Perfis.admin()).with(csrf()).param("ocultar", "true").param("voltar", "painel"))
            .andExpect(status().is3xxRedirection)
        assertThat(config.primeirosPassosOcultos()).isTrue()
        mvc.perform(get("/dashboard").with(Perfis.admin())).andExpect(content().string(not(containsString("Ocultar este quadro"))))
        mvc.perform(get("/admin/personalizar").with(Perfis.admin())).andExpect(content().string(containsString("Mostrar no painel")))

        mvc.perform(post("/admin/personalizar/primeiros-passos").with(Perfis.admin()).with(csrf()).param("ocultar", "false"))
        assertThat(config.primeirosPassosOcultos()).isFalse()
    }
}
