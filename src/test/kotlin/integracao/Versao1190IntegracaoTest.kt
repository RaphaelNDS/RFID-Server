package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.RestricaoCliente
import org.example.model.SituacaoBem
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.DadosCliente
import org.example.service.DadosPedido
import org.example.service.LocacaoService
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import java.time.LocalDate
import java.util.UUID

/** 1.19.0: cliente bloqueado ou só com aprovação. */
class Versao1190IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var locacao: LocacaoService
    @Autowired lateinit var tags: TagRepository

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val hoje = LocalDate.now()
    private var tipoId: Long? = null

    private fun como(role: String, vararg permissoes: String) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            "${role.lowercase()}@x.com", null, (listOf("ROLE_$role") + permissoes).map { SimpleGrantedAuthority(it) })
    }

    private fun expedicao() = como("EXPEDICAO", "LOCACAO_READ", "LOCACAO_CREATE", "LOCACAO_UPDATE")

    @AfterEach
    fun limpar() = SecurityContextHolder.clearContext()

    private fun equipamento(epc: String): String {
        val tipo = tipoId ?: catalogo.salvarTipo(TipoEquipamentoRequest("Coletor $s")).id.also { tipoId = it }
        rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-$epc", patrimonio = "P-$epc", tipoEquipamentoId = tipo))
        return epc
    }

    @Test
    fun `cliente bloqueado nao abre pedido e nada sai, mas a devolucao continua`() {
        como("ADMIN")
        val epc = equipamento("BL1-$s")
        val c = locacao.salvarCliente(null, DadosCliente("Obra $s"))
        val p = locacao.criarPedido(DadosPedido(c.id, hoje.plusDays(3)))
        locacao.adicionarLinha(p.id, null, null, null, null, epc)
        locacao.registrarSaida(p.id, listOf(epc))

        assertThatThrownBy { locacao.definirRestricao(c.id, RestricaoCliente.BLOQUEADO, "  ") }.hasMessageContaining("motivo")
        locacao.definirRestricao(c.id, RestricaoCliente.BLOQUEADO, "Devolveu com avaria 3 vezes")

        expedicao()
        assertThatThrownBy { locacao.criarPedido(DadosPedido(c.id, hoje.plusDays(3))) }
            .hasMessageContaining("bloqueado").hasMessageContaining("avaria")
        // O que já está na rua volta normalmente
        locacao.registrarDevolucao(listOf(epc))
        assertThat(tags.findByTagReal(epc).get().situacao).isNotEqualTo(SituacaoBem.ALUGADO)

        // Pedido aberto antes do bloqueio: a saída também para
        como("ADMIN")
        val c2 = locacao.salvarCliente(null, DadosCliente("Feira $s"))
        val p2 = locacao.criarPedido(DadosPedido(c2.id, hoje.plusDays(3)))
        val epc2 = equipamento("BL2-$s")
        locacao.adicionarLinha(p2.id, null, null, null, null, epc2)
        locacao.definirRestricao(c2.id, RestricaoCliente.BLOQUEADO, "Calote")
        assertThatThrownBy { locacao.registrarSaida(p2.id, listOf(epc2)) }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("bloqueado")
        assertThatThrownBy { locacao.aprovarPedido(p2.id) }.hasMessageContaining("bloqueado")
        assertThat(tags.findByTagReal(epc2).get().situacao).isEqualTo(SituacaoBem.DISPONIVEL)
    }

    @Test
    fun `so com aprovacao trava a saida ate o gestor aprovar, e a expedicao nao aprova`() {
        como("ADMIN")
        val epc = equipamento("AP1-$s")
        val c = locacao.salvarCliente(null, DadosCliente("Evento $s"))
        locacao.definirRestricao(c.id, RestricaoCliente.APROVACAO, "Atrasou 2 devoluções")

        expedicao()
        assertThat(locacao.podeAprovar()).isFalse()
        assertThatThrownBy { locacao.definirRestricao(c.id, RestricaoCliente.NENHUMA, null) }.hasMessageContaining("administrador ou gestor")
        val p = locacao.criarPedido(DadosPedido(c.id, hoje.plusDays(2)))
        assertThat(p.aprovadoEm).isNull()
        locacao.adicionarLinha(p.id, null, null, null, null, epc)
        assertThatThrownBy { locacao.registrarSaida(p.id, listOf(epc)) }.hasMessageContaining("aprovar o pedido nº ${p.id}")
        assertThatThrownBy { locacao.aprovarPedido(p.id) }.hasMessageContaining("administrador ou gestor")

        como("GESTOR", "LOCACAO_READ", "LOCACAO_CREATE", "LOCACAO_UPDATE")
        locacao.aprovarPedido(p.id)
        expedicao()
        assertThat(locacao.registrarSaida(p.id, listOf(epc)).aceitos).hasSize(1)
        assertThat(locacao.pedido(p.id).aprovadoPor).isNotBlank()

        // Quem pode aprovar já cria aprovado
        como("ADMIN")
        assertThat(locacao.criarPedido(DadosPedido(c.id, hoje.plusDays(2))).aprovadoEm).isNotNull()

        // Liberar o cliente apaga o motivo
        val livre = locacao.definirRestricao(c.id, RestricaoCliente.NENHUMA, null)
        assertThat(livre.motivoRestricao).isNull()
        assertThat(livre.restricaoPor).isNotBlank()
    }

    @Test
    fun `telas mostram a restricao e o botao de aprovar so para quem pode`() {
        como("ADMIN")
        val c = locacao.salvarCliente(null, DadosCliente("Show $s"))
        locacao.definirRestricao(c.id, RestricaoCliente.APROVACAO, "Cadastro novo")
        expedicao()
        val p = locacao.criarPedido(DadosPedido(c.id, hoje.plusDays(2)))
        SecurityContextHolder.clearContext()

        mvc.perform(get("/admin/clientes").with(Perfis.admin()))
            .andExpect(content().string(containsString("só com aprovação")))
            .andExpect(content().string(containsString("Cadastro novo")))
        mvc.perform(get("/admin/pedidos/${p.id}").with(Perfis.admin()))
            .andExpect(content().string(containsString("Aprovar pedido")))
        val expedicaoWeb = Perfis.logado(70, "Edu Expedição", "edu@x.com", "EXPEDICAO", listOf("LOCACAO_READ", "LOCACAO_CREATE", "LOCACAO_UPDATE"))
        mvc.perform(get("/admin/pedidos/${p.id}").with(expedicaoWeb))
            .andExpect(content().string(containsString("só aluga com aprovação")))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Aprovar pedido"))))

        mvc.perform(post("/admin/pedidos/${p.id}/aprovar").with(expedicaoWeb).with(csrf()))
            .andExpect(flash().attribute("erro", containsString("administrador ou gestor")))
        mvc.perform(post("/admin/pedidos/${p.id}/aprovar").with(Perfis.admin()).with(csrf()))
            .andExpect(flash().attribute("msg", containsString("aprovado")))
        mvc.perform(post("/admin/clientes/${c.id}/restricao").with(Perfis.admin()).with(csrf())
            .param("restricao", "BLOQUEADO").param("motivo", "Teste"))
            .andExpect(flash().attribute("msg", containsString("bloqueado")))
    }
}
