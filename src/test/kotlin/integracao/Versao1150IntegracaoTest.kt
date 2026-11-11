package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.Acao
import org.example.model.Modulo
import org.example.model.Role
import org.example.repository.PedidoItemRepository
import org.example.repository.UserRepository
import org.example.request.CadastroRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.DadosCliente
import org.example.service.DadosPedido
import org.example.service.LocacaoService
import org.example.service.RfidService
import org.example.service.UsuarioService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

/** 1.15.0: perfis por função (Expedição, Conferente, Técnico) com os módulos Locação e Manutenção. */
class Versao1150IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var locacao: LocacaoService
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var itens: PedidoItemRepository
    @Autowired lateinit var usuarios: UsuarioService
    @Autowired lateinit var repo: UserRepository
    @Autowired lateinit var operadores: OperadorTeste

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val hoje = LocalDate.now()

    /** Logado com as permissões do perfil de função, como o login monta. */
    private fun perfil(role: Role, id: Long = 50): RequestPostProcessor =
        Perfis.logado(id, role.rotulo, "${role.name.lowercase()}@x.com", role.name, role.permissoesFixas().map { "${it.first}_${it.second}" })

    private val expedicao get() = perfil(Role.EXPEDICAO, 51)
    private val conferente get() = perfil(Role.CONFERENTE, 52)
    private val tecnico get() = perfil(Role.TECNICO, 53)

    /** Um pedido com um equipamento que saiu e voltou: esperando a conferência. */
    private fun devolvidoAguardando(): Pair<Long, Long> {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Rádio $s")).id
        rfid.cadastrarTag(CadastroRequest(tag = "R-$s", codigoInterno = "TI-R-$s", patrimonio = "P-R-$s", tipoEquipamentoId = tipo))
        val c = locacao.salvarCliente(null, DadosCliente("Cliente $s"))
        val p = locacao.criarPedido(DadosPedido(c.id, hoje.plusDays(2)))
        locacao.adicionarLinha(p.id, tipo, null, null, 1, null)
        locacao.registrarSaida(p.id, listOf("R-$s"))
        locacao.registrarDevolucao(listOf("R-$s"))
        return p.id to itens.findAllByPedidoIdOrderByIdAsc(p.id).single().id
    }

    @Test
    fun `cada perfil de funcao tem as permissoes dele`() {
        assertThat(Role.EXPEDICAO.permissoesFixas()).contains(Modulo.LOCACAO to Acao.CREATE, Modulo.LOCACAO to Acao.UPDATE)
            .doesNotContain(Modulo.MANUTENCAO to Acao.READ)
        assertThat(Role.CONFERENTE.permissoesFixas()).contains(Modulo.LOCACAO to Acao.UPDATE, Modulo.MANUTENCAO to Acao.CREATE, Modulo.RFID to Acao.UPDATE)
            .doesNotContain(Modulo.LOCACAO to Acao.CREATE)
        assertThat(Role.TECNICO.permissoesFixas()).contains(Modulo.MANUTENCAO to Acao.UPDATE, Modulo.LOCACAO to Acao.READ)
            .doesNotContain(Modulo.LOCACAO to Acao.UPDATE)
        assertThat(Role.entries.filter { it.deFuncao }).containsExactly(Role.EXPEDICAO, Role.CONFERENTE, Role.TECNICO)
        // Nenhum perfil de função mexe em usuários nem exclui
        Role.FUNCOES.forEach { r -> assertThat(r.permissoesFixas().map { it.second }).doesNotContain(Acao.DELETE) }
    }

    @Test
    fun `ADMIN da o perfil de funcao e as permissoes gravadas sao as do perfil`() {
        val admin = operadores.criarUsuario(email = "adm-$s@x.com".lowercase(), role = Role.ADMIN)
        val u = operadores.criarUsuario(email = "func-$s@x.com".lowercase())
        usuarios.editar(u.id!!, "Teo Técnico", Role.TECNICO, listOf("CATALOGO"), listOf("DELETE"), admin.id!!)   // as caixas são ignoradas
        val salvo = repo.findById(u.id!!).get()
        assertThat(salvo.role).isEqualTo(Role.TECNICO)
        assertThat(salvo.permissoes.map { it.modulo to it.acao }).containsExactlyInAnyOrderElementsOf(Role.TECNICO.permissoesFixas())

        mvc.perform(get("/admin/usuarios").with(Perfis.admin())).andExpect(content().string(containsString("Técnico")))
        mvc.perform(get("/admin/usuarios/${u.id}/editar").with(Perfis.admin())).andExpect(content().string(containsString("Por função")))
    }

    @Test
    fun `expedicao cria pedido e registra saida, mas nao abre manutencao nem manda para la`() {
        val (pedido, item) = devolvidoAguardando()
        mvc.perform(get("/admin/pedidos/novo").with(expedicao)).andExpect(status().isOk)
        mvc.perform(get("/admin/manutencoes").with(expedicao)).andExpect(status().isForbidden)
        mvc.perform(get("/admin/pedidos/$pedido").with(expedicao))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("value=\"MANUTENCAO\""))))
        mvc.perform(post("/admin/pedidos/$pedido/itens/$item/conferencia").with(expedicao).with(csrf())
            .param("estado", "AVARIA").param("destino", "MANUTENCAO").param("observacao", "Antena quebrada"))
            .andExpect(flash().attribute("erro", containsString("Manutenção · Criar")))
        assertThat(itens.findById(item).get().conferido).isFalse()
    }

    @Test
    fun `conferente confere e manda para a manutencao, mas nao cria pedido`() {
        val (pedido, item) = devolvidoAguardando()
        mvc.perform(get("/admin/pedidos/novo").with(conferente)).andExpect(status().isForbidden)
        mvc.perform(post("/admin/pedidos/$pedido/itens/$item/conferencia").with(conferente).with(csrf())
            .param("estado", "AVARIA").param("destino", "MANUTENCAO").param("observacao", "Antena quebrada"))
            .andExpect(flash().attribute("msg", containsString("manutenção")))
        assertThat(itens.findById(item).get().manutencaoId).isNotNull()
    }

    @Test
    fun `tecnico abre manutencao e ve os pedidos, mas nao mexe neles`() {
        val (pedido, _) = devolvidoAguardando()
        mvc.perform(get("/admin/manutencoes/nova").with(tecnico)).andExpect(status().isOk)
        mvc.perform(get("/admin/pedidos/$pedido").with(tecnico)).andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Registrar devolução"))))
        mvc.perform(get("/admin/pedidos/novo").with(tecnico)).andExpect(status().isForbidden)
        mvc.perform(post("/admin/pedidos/$pedido/devolucao").with(tecnico).with(csrf()).param("codigos", "R-$s")).andExpect(status().isForbidden)
        // O painel do dia aparece (Locação · Ver) com as manutenções
        mvc.perform(get("/dashboard").with(tecnico)).andExpect(status().isOk).andExpect(content().string(containsString("Disponíveis")))
    }

    @Test
    fun `quem so tem Etiquetas nao entra mais na locacao nem na manutencao`() {
        val soEtiquetas = Perfis.logado(60, "Eva Etiquetas", "eva@x.com", "CUSTOM", listOf("RFID_READ", "RFID_UPDATE"))
        mvc.perform(get("/admin/pedidos").with(soEtiquetas)).andExpect(status().isForbidden)
        mvc.perform(get("/admin/manutencoes").with(soEtiquetas)).andExpect(status().isForbidden)
        mvc.perform(get("/admin/tags-cadastradas").with(soEtiquetas)).andExpect(status().isOk)
        assertThat(OperadorTeste.SENHA).isNotBlank()
    }
}
