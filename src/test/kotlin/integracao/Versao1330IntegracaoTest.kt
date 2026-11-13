package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.service.AlertaService
import org.example.service.PrimeirosPassosService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import java.util.UUID

/** 1.33.0: com os avisos desligados, o painel do administrador mostra os problemas que ninguém recebeu. */
class Versao1330IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var alertas: AlertaService
    @Autowired lateinit var primeirosPassos: PrimeirosPassosService

    private val s = UUID.randomUUID().toString().take(6).uppercase()

    @Test
    fun `problema sem aviso aparece no painel do admin ate ser resolvido, e os avisos viram um primeiro passo`() {
        // Nos testes não há SMTP nem Telegram
        assertThat(alertas.algumLigado).isFalse()
        assertThat(primeirosPassos.passos().passos.single { it.titulo == "Avisos" }.feito).isFalse()

        val chave = "teste-$s"
        try {
            alertas.problema(chave, "Disco $s quase cheio", "detalhe")
            alertas.problema(chave, "Disco $s quase cheio", "de novo")   // repetido não duplica
            assertThat(alertas.problemasAbertos().count { it == "Disco $s quase cheio" }).isEqualTo(1)

            mvc.perform(get("/dashboard").with(Perfis.admin()))
                .andExpect(content().string(containsString("problema(s) sem aviso")))
                .andExpect(content().string(containsString("Disco $s quase cheio")))
            mvc.perform(get("/dashboard").with(Perfis.usuario()))
                .andExpect(content().string(not(containsString("Disco $s quase cheio"))))
        } finally {
            alertas.resolvido(chave, "Disco", "ok")
        }
        assertThat(alertas.problemasAbertos()).doesNotContain("Disco $s quase cheio")
        mvc.perform(get("/dashboard").with(Perfis.admin()))
            .andExpect(content().string(not(containsString("Disco $s quase cheio"))))
    }
}
