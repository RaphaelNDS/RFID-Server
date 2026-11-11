package org.example.integracao

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.Acao
import org.example.model.EstadoDevolucao
import org.example.model.Modulo
import org.example.model.MotivoBaixa
import org.example.model.SituacaoBem
import org.example.model.SituacaoPedido
import org.example.model.StatusManutencao
import org.example.repository.ManutencaoRepository
import org.example.repository.PedidoItemRepository
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.DadosCliente
import org.example.service.DadosPedido
import org.example.service.FiltroEtiquetas
import org.example.service.LocacaoService
import org.example.service.ProblemaLeitura
import org.example.service.RfidService
import org.example.service.VendaService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.example.suporte.comoOperador
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

/** 1.12.0: locadora (clientes, pedidos de locação, saída e devolução pelo coletor e pela web, conferência, venda). */
class Versao1120IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var locacao: LocacaoService
    @Autowired lateinit var vendas: VendaService
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var itens: PedidoItemRepository
    @Autowired lateinit var manutencoes: ManutencaoRepository
    @Autowired lateinit var operadores: OperadorTeste

    private val s = UUID.randomUUID().toString().take(6).uppercase()
    private val hoje = LocalDate.now()

    private fun tag(epc: String) = tags.findByTagReal(epc).get()

    private fun cadastrar(epc: String, tipoId: Long? = null) {
        rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-$epc", patrimonio = "P-$epc", tipoEquipamentoId = tipoId))
    }

    /** Operador do coletor com RFID · Editar (saída e devolução). */
    private val token by lazy {
        val email = "loc-$s@x.com".lowercase()
        operadores.criarUsuario(email = email, permissoes = arrayOf(Modulo.LEITURA to Acao.READ, Modulo.RFID to Acao.READ, Modulo.LOCACAO to Acao.READ, Modulo.LOCACAO to Acao.UPDATE))
        // O login devolve a permissão nova (um login só: outro derrubaria esta sessão)
        val resposta = mvc.perform(post("/api/android/login").header(OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR).contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"$email","senha":"${OperadorTeste.SENHA}"}"""))
            .andExpect(jsonPath("$.podeMovimentarLocacao").value(true))
            .andReturn().response.contentAsString
        com.jayway.jsonpath.JsonPath.read<String>(resposta, "$.token")
    }

    private fun coletor(url: String, json: String) =
        mvc.perform(post(url).comoOperador(token).contentType(MediaType.APPLICATION_JSON).content(json))

    /** Tipo "Coletor", 4 equipamentos dele (E1..E4) e um de outro tipo (E5); pedido com 2 do tipo e o E4 específico. */
    private fun cenario(): Long {
        val coletorTipo = catalogo.salvarTipo(TipoEquipamentoRequest("Coletor $s")).id
        val outro = catalogo.salvarTipo(TipoEquipamentoRequest("Impressora $s")).id
        (1..4).forEach { cadastrar("E$it-$s", coletorTipo) }
        cadastrar("E5-$s", outro)
        val cliente = locacao.salvarCliente(null, DadosCliente("Construtora $s", "12.345.678/0001-90", "João", "11 99999-0000"))
        val pedido = locacao.criarPedido(DadosPedido(cliente.id, hoje.plusDays(10), hoje, "Obra Rua Y"))
        locacao.adicionarLinha(pedido.id, coletorTipo, null, null, 2, null)
        locacao.adicionarLinha(pedido.id, null, null, null, null, "P-E4-$s")
        return pedido.id
    }

    @Test
    fun `fluxo da locacao - sai em partes, recusa o que nao serve, devolve e a web confere a devolucao`() {
        val id = cenario()

        // Saída parcial, depois o resto (com o E1 de novo: reenvio não duplica). E3 sobra (o tipo já tem 2), E5 é de
        // outro tipo, NAOEXISTE sem cadastro
        val primeira = locacao.registrarSaida(id, listOf("E1-$s"))
        assertThat(primeira.aceitos.map { it.codigo }).containsExactly("E1-$s")
        assertThat(primeira.pedido.situacao).isEqualTo(SituacaoPedido.EM_SEPARACAO)
        val resto = locacao.registrarSaida(id, listOf("E1-$s", "E2-$s", "E4-$s", "E3-$s", "E5-$s", "NAOEXISTE-$s"))
        assertThat(resto.saidos).hasSize(3)
        assertThat(resto.recusados.associate { it.codigo to it.problema }).containsExactlyInAnyOrderEntriesOf(mapOf(
            "E3-$s" to ProblemaLeitura.EXCEDENTE, "E5-$s" to ProblemaLeitura.FORA_DO_PEDIDO, "NAOEXISTE-$s" to ProblemaLeitura.SEM_CADASTRO))
        assertThat(resto.pedido.situacao).isEqualTo(SituacaoPedido.ENTREGUE)
        assertThat(itens.findAllByPedidoIdOrderByIdAsc(id)).hasSize(3)
        assertThat(tag("E2-$s").situacao).isEqualTo(SituacaoBem.ALUGADO)

        // Ficha do bem alugado no coletor mostra o pedido
        mvc.perform(get("/api/android/tags/E1-$s").comoOperador(token))
            .andExpect(jsonPath("$.pedido").value(id.toInt()))
            .andExpect(jsonPath("$.cliente").value(containsString("Construtora $s")))
            .andExpect(jsonPath("$.situacao").value("ALUGADO"))
            .andExpect(jsonPath("$.localEntrega").value("Obra Rua Y"))

        // Devolução de um (e um que não está alugado); repetir não duplica
        repeat(2) {
            val r = locacao.registrarDevolucao(listOf("E1-$s", "E3-$s"))
            assertThat(r.recebidos.map { it.second.id }).containsExactly(id)
            assertThat(r.semLocacao.map { it.first }).containsExactly("E3-$s")
            assertThat(r.pedidos.single().situacao).isEqualTo(SituacaoPedido.DEVOLVIDO_PARCIAL)
        }
        assertThat(tag("E1-$s").situacao).isEqualTo(SituacaoBem.EM_DEVOLUCAO)

        // Conferência na web: com avaria, vai para a manutenção
        val e1 = itens.findAllByPedidoIdOrderByIdAsc(id).first { it.tagReal == "E1-$s" }
        mvc.perform(get("/admin/pedidos/devolucoes").with(Perfis.gestor()))
            .andExpect(content().string(containsString("P-E1-$s")))
        mvc.perform(post("/admin/pedidos/$id/itens/${e1.id}/conferencia").with(Perfis.gestor()).with(csrf())
            .param("estado", "AVARIA").param("destino", "MANUTENCAO").param("observacao", "Tela quebrada"))
            .andExpect(flash().attribute("msg", containsString("manutenção")))
        assertThat(tag("E1-$s").situacao).isEqualTo(SituacaoBem.EM_MANUTENCAO)
        val conferido = itens.findById(e1.id).get()
        assertThat(conferido.estadoDevolucao).isEqualTo(EstadoDevolucao.AVARIA)
        assertThat(manutencoes.findById(conferido.manutencaoId!!).get().status).isEqualTo(StatusManutencao.ABERTA)
        assertThat(manutencoes.findById(conferido.manutencaoId!!).get().problema).contains("Tela quebrada")

        // Volta o resto: pedido encerrado; a conferência OK deixa Disponível
        locacao.registrarDevolucao(listOf("E2-$s", "E4-$s"))
        assertThat(locacao.pedido(id).situacao).isEqualTo(SituacaoPedido.ENCERRADO)
        val e2 = itens.findAllByPedidoIdOrderByIdAsc(id).first { it.tagReal == "E2-$s" }
        locacao.conferirDevolucao(e2.id, EstadoDevolucao.OK, false, null)
        assertThat(tag("E2-$s").situacao).isEqualTo(SituacaoBem.DISPONIVEL)
        assertThat(rfid.pedidoAtual(tag("E2-$s").id)).isNull()
    }

    @Test
    fun `pela web - cria o pedido, acrescenta linhas, registra saida e devolucao, e o contrato sai em PDF`() {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Rádio $s")).id
        cadastrar("R1-$s", tipo)
        val cliente = locacao.salvarCliente(null, DadosCliente("Eventos $s", endereco = "Av. Central, 100"))

        val url = mvc.perform(post("/admin/pedidos").with(Perfis.gestor()).with(csrf())
            .param("cliente", "${cliente.id}").param("previsao", hoje.plusDays(3).toString()))
            .andExpect(status().is3xxRedirection).andReturn().response.redirectedUrl!!
        val id = url.substringAfterLast('/').toLong()
        assertThat(locacao.pedido(id).localEntrega).isEqualTo("Av. Central, 100")   // vazio = endereço do cliente

        mvc.perform(post("/admin/pedidos/$id/linhas").with(Perfis.gestor()).with(csrf()).param("tipoEquipamentoId", "$tipo").param("quantidade", "1"))
            .andExpect(flash().attribute("msg", containsString("1 × Rádio $s")))
        mvc.perform(get("/admin/pedidos/$id").with(Perfis.gestor()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Registrar saída")))
        mvc.perform(post("/admin/pedidos/$id/saida").with(Perfis.gestor()).with(csrf()).param("codigos", "P-R1-$s"))
            .andExpect(flash().attribute("msg", containsString("Saída de 1 equipamento(s)")))
        assertThat(tag("R1-$s").situacao).isEqualTo(SituacaoBem.ALUGADO)
        mvc.perform(get("/admin/pedidos").param("busca", "R1-$s").with(Perfis.usuario()))
            .andExpect(content().string(containsString("Eventos $s")))

        val pdf = mvc.perform(get("/admin/pedidos/$id/contrato.pdf").with(Perfis.usuario()))
            .andExpect(status().isOk).andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andReturn().response.contentAsByteArray
        val texto = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }.replace(Regex("\r?\n"), " ")
        assertThat(texto).contains("Contrato de locação").contains("Eventos $s").contains("P-R1-$s")

        mvc.perform(post("/admin/pedidos/$id/devolucao").with(Perfis.gestor()).with(csrf()).param("codigos", "R1-$s"))
            .andExpect(flash().attribute("msg", containsString("1 equipamento(s) recebido(s)")))
        assertThat(tag("R1-$s").situacao).isEqualTo(SituacaoBem.EM_DEVOLUCAO)
        // Quem só vê não mexe
        mvc.perform(post("/admin/pedidos/$id/saida").with(Perfis.usuario()).with(csrf()).param("codigos", "R1-$s")).andExpect(status().isForbidden)
    }

    @Test
    fun `equipamento no pedido nao recebe baixa, nao e excluido, nao muda de situacao pela edicao e nao e vendido`() {
        val id = cenario()
        locacao.registrarSaida(id, listOf("E1-$s"))
        val tagId = tag("E1-$s").id

        assertThatThrownBy { rfid.darBaixa(tagId, MotivoBaixa.PERDA, null, null) }.hasMessageContaining("pedido de locação nº $id")
        assertThatThrownBy { rfid.excluirTag(tagId) }.hasMessageContaining("antes de excluir")
        assertThatThrownBy { vendas.vender(tagId, null, null, null, null) }.hasMessageContaining("pedido de locação nº $id")
        assertThatThrownBy { locacao.cancelarPedido(id, "desistiu") }.hasMessageContaining("Já saiu equipamento")
        // A edição salva os outros campos e mantém Alugado (a situação nem vai no formulário)
        mvc.perform(post("/admin/etiquetas/$tagId/editar").with(Perfis.admin()).with(csrf())
            .param("codigoInterno", "TI-NOVO-$s").param("patrimonio", "P-E1-$s"))
            .andExpect(status().is3xxRedirection)
        assertThat(tag("E1-$s").situacao).isEqualTo(SituacaoBem.ALUGADO)
        assertThat(tag("E1-$s").codigoInterno).isEqualTo("TI-NOVO-$s")
        mvc.perform(get("/admin/etiquetas/$tagId/editar").with(Perfis.admin()))
            .andExpect(content().string(containsString("Pedido nº $id")))
            .andExpect(content().string(not(containsString("name=\"situacao\""))))
        // "Alugado" não se escolhe na edição de outro equipamento
        mvc.perform(post("/admin/etiquetas/${tag("E2-$s").id}/editar").with(Perfis.admin()).with(csrf())
            .param("codigoInterno", "TI-E2-$s").param("patrimonio", "P-E2-$s").param("situacao", "ALUGADO"))
            .andExpect(content().string(containsString("vem do pedido de locação")))
    }

    @Test
    fun `venda deixa Vendido e fora das listas, desfazer volta para A venda, e os nomes antigos de situacao ainda valem`() {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Notebook $s")).id
        cadastrar("V1-$s", tipo)
        val cliente = locacao.salvarCliente(null, DadosCliente("Comprador $s"))
        val tagId = tag("V1-$s").id

        mvc.perform(post("/admin/etiquetas/$tagId/venda").with(Perfis.gestor()).with(csrf())
            .param("cliente", "${cliente.id}").param("valor", "2.500,00"))
            .andExpect(flash().attribute("msg", containsString("Vendido")))
        assertThat(tag("V1-$s").situacao).isEqualTo(SituacaoBem.VENDIDO)
        assertThat(vendas.vendaAtual(tagId)!!.valor).isEqualByComparingTo("2500.00")
        assertThat(rfid.listarTodasParaAdmin(null, FiltroEtiquetas()).map { it.tag }).doesNotContain("V1-$s")
        assertThat(rfid.listarTodasParaAdmin(null, FiltroEtiquetas(incluirBaixados = true)).map { it.tag }).contains("V1-$s")
        assertThatThrownBy { rfid.darBaixa(tagId, MotivoBaixa.SUCATA, null, null) }.hasMessageContaining("vendido")

        mvc.perform(post("/admin/etiquetas/$tagId/venda/desfazer").with(Perfis.gestor()).with(csrf()))
        assertThat(tag("V1-$s").situacao).isEqualTo(SituacaoBem.A_VENDA)
        assertThat(vendas.vendaAtual(tagId)).isNull()

        // Planilha ou app antigos mandando "Em uso" / EMPRESTADO
        assertThat(SituacaoBem.de("Em uso")).isEqualTo(SituacaoBem.DISPONIVEL)
        assertThat(SituacaoBem.de("EM_ESTOQUE")).isEqualTo(SituacaoBem.DISPONIVEL)
        assertThat(SituacaoBem.de("Emprestado")).isEqualTo(SituacaoBem.ALUGADO)
        assertThat(SituacaoBem.de("À venda")).isEqualTo(SituacaoBem.A_VENDA)
    }

    @Test
    fun `cliente com documento repetido e recusado, e pedido valida datas e linhas`() {
        locacao.salvarCliente(null, DadosCliente("A $s", "529.982.247-25"))
        assertThatThrownBy { locacao.salvarCliente(null, DadosCliente("B $s", "52998224725")) }.hasMessageContaining("Já existe o cliente A $s")
        assertThatThrownBy { locacao.salvarCliente(null, DadosCliente("C $s", "123")) }.hasMessageContaining("CPF")
        val c = locacao.salvarCliente(null, DadosCliente("D $s"))
        assertThatThrownBy { locacao.criarPedido(DadosPedido(c.id, hoje.minusDays(1))) }.hasMessageContaining("passado")
        assertThatThrownBy { locacao.criarPedido(DadosPedido(c.id, hoje.plusDays(1), hoje.plusDays(5))) }.hasMessageContaining("antes da saída")
        val p = locacao.criarPedido(DadosPedido(c.id, hoje.plusDays(1)))
        assertThatThrownBy { locacao.adicionarLinha(p.id, null, null, null, 1, null) }.hasMessageContaining("tipo")
        assertThatThrownBy { locacao.registrarSaida(p.id, emptyList()) }.hasMessageContaining("Informe as etiquetas")
        locacao.cancelarPedido(p.id, "Cliente desistiu")
        assertThatThrownBy { locacao.registrarSaida(p.id, listOf("X")) }.isInstanceOf(IllegalStateException::class.java)
        mvc.perform(get("/admin/clientes").with(Perfis.usuario())).andExpect(content().string(containsString("CPF 529.982.247-25")))
        assertThat(ProblemaLeitura.entries).hasSize(4)
    }
}
