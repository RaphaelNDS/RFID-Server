package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.repository.ColetorRepository
import org.example.service.ColetorService
import org.example.service.SituacaoColetor
import org.example.service.SituacaoContato
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.example.suporte.comoOperador
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** Tela Coletores: o contato de cada coletor registrado pelas chamadas aceitas em /api/android. */
class ColetoresIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var coletores: ColetorRepository
    @Autowired lateinit var service: ColetorService
    @Autowired lateinit var operadores: OperadorTeste

    @BeforeEach
    fun limpar() = service.esquecerContatosGravados()

    private fun ping(chave: String = CHAVE_COLETOR, versao: String? = "1.4.0", codigo: String? = "13", ip: String = "10.20.0.5") =
        mvc.perform(
            get("/api/android/ping").header(OperadorTeste.HEADER_CHAVE, chave)
                .apply { versao?.let { header("X-Versao-App", it) }; codigo?.let { header("X-Versao-Codigo", it) } }
                .with { it.remoteAddr = ip; it }
        ).andExpect(status().isOk)

    @Test
    fun `contato aceito registra versao e IP do coletor`() {
        ping()

        val c = coletores.findById("coletor-teste").get()
        assertThat(c.versaoApp).isEqualTo("1.4.0")
        assertThat(c.codigoVersao).isEqualTo(13)
        assertThat(c.ultimoIp).isEqualTo("10.20.0.5")
    }

    @Test
    fun `chamada com operador registra o operador`() {
        val token = operadores.sessaoPadrao(mvc)
        service.esquecerContatosGravados()

        mvc.perform(get("/api/android/locais").comoOperador(token)).andExpect(status().isOk)

        assertThat(coletores.findById("coletor-teste").get().ultimoOperador).isEqualTo("Olga Operadora")
    }

    @Test
    fun `app antigo sem os headers e header invalido nao atrapalham`() {
        ping(versao = null, codigo = null)
        assertThat(coletores.findById("coletor-teste").get().codigoVersao).isNull()

        // Valores inválidos são ignorados e a chamada segue normal (quebra de linha nem chega aqui:
        // o firewall do Spring Security recusa antes, e um cliente HTTP real não consegue enviar)
        ping(versao = "1.4.0;<script>", codigo = "abc")
        assertThat(coletores.findById("coletor-teste").get().versaoApp).isNull()
    }

    @Test
    fun `chave errada nao registra contato`() {
        mvc.perform(get("/api/android/ping").header(OperadorTeste.HEADER_CHAVE, "chave-errada-0123456789"))
            .andExpect(status().isUnauthorized)

        assertThat(coletores.count()).isZero()
    }

    @Test
    fun `tela lista os coletores com versao e quem nunca conectou`() {
        ping(codigo = "13")
        service.esquecerContatosGravados()

        @Suppress("UNCHECKED_CAST")
        val lista = mvc.perform(get("/admin/coletores").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("coletor-teste")))
            .andExpect(content().string(containsString("Nunca conectou")))
            .andReturn().modelAndView!!.model["coletores"] as List<SituacaoColetor>

        val porNome = lista.associateBy { it.nome }
        assertThat(porNome.getValue("coletor-teste").situacao).isEqualTo(SituacaoContato.EM_USO)
        assertThat(porNome.getValue("coletor-teste").versaoApp).isEqualTo("1.4.0")
        assertThat(porNome.getValue("coletor-leitura").situacao).isEqualTo(SituacaoContato.NUNCA)
    }

    @Test
    fun `versao mais antiga aparece como desatualizada`() {
        ping(chave = CHAVE_COLETOR, codigo = "13")
        ping(chave = "chave-de-leitura-0123456789", versao = "1.3.9", codigo = "10")

        val lista = service.listar().associateBy { it.nome }
        assertThat(lista.getValue("coletor-teste").desatualizado).isFalse()
        assertThat(lista.getValue("coletor-leitura").desatualizado).isTrue()
    }

    @Test
    fun `tela de coletores e so para ADMIN`() {
        mvc.perform(get("/admin/coletores").with(Perfis.gestor())).andExpect(status().isForbidden)
    }

    @Test
    fun `menu mostra Coletores so para ADMIN`() {
        mvc.perform(get("/dashboard").with(Perfis.admin())).andExpect(content().string(containsString("/admin/coletores")))
        mvc.perform(get("/dashboard").with(Perfis.gestor()))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("/admin/coletores"))))
    }
}
