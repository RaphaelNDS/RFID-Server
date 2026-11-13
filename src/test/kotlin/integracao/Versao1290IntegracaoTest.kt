package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.SituacaoBem
import org.example.model.TagNaoCadastradaEntity
import org.example.repository.ClienteRepository
import org.example.repository.TagNaoCadastradaRepository
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CadastroLoteService
import org.example.service.CatalogoService
import org.example.service.DadosCliente
import org.example.service.LocacaoService
import org.example.service.PatrimonioCadastrosService
import org.example.service.RfidService
import org.example.service.Sequencia
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpSession
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/** 1.29.0: cadastro em lote das pendentes e importação de clientes por planilha. */
class Versao1290IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var lote: CadastroLoteService
    @Autowired lateinit var pendentes: TagNaoCadastradaRepository
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var cadastros: PatrimonioCadastrosService
    @Autowired lateinit var locacao: LocacaoService
    @Autowired lateinit var clientes: ClienteRepository

    private val s = UUID.randomUUID().toString().take(6).uppercase()

    private fun pendente(epc: String) = pendentes.save(TagNaoCadastradaEntity(tag = epc, dataHora = LocalDateTime.now()))

    @Test
    fun `sequencia respeita os zeros e o prefixo`() {
        assertThat(Sequencia("GER-", "008").valor(3)).isEqualTo("GER-011")
        assertThat(Sequencia("", "99").valor(1)).isEqualTo("100")
        assertThat(Sequencia.de(" ", "")).isNull()
        assertThat(Sequencia.de("X", "")!!.valor(0)).isEqualTo("X1")
        assertThatThrownBy { Sequencia("A", "1a") }.hasMessageContaining("só dígitos")
    }

    @Test
    fun `lote cadastra as pendentes marcadas com codigo e patrimonio em sequencia`() {
        val epcs = (1..3).map { "LOTE$it-$s" }.onEach(::pendente)
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest("Betoneira $s")).id
        val local = cadastros.salvarLocal(null, "Galpão $s", null)

        mvc.perform(get("/admin/tags-pendentes").with(Perfis.gestor()))
            .andExpect(content().string(containsString("Cadastrar marcadas em lote")))
        mvc.perform(get("/admin/tags-pendentes/lote").param("etiquetas", epcs[0], epcs[1], epcs[2], "NAO-PENDENTE-$s").with(Perfis.gestor()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("3 etiqueta(s) pendente(s)")))
            .andExpect(content().string(containsString(epcs[2])))

        mvc.perform(post("/admin/tags-pendentes/lote").with(Perfis.gestor()).with(csrf())
            .param("etiquetas", *epcs.toTypedArray())
            .param("codigoPrefixo", "BT-$s-").param("codigoInicio", "009")
            .param("patrimonioPrefixo", "PB-$s-").param("patrimonioInicio", "1")
            .param("tipoEquipamentoId", tipo.toString()).param("localId", local.id.toString()).param("situacao", "DISPONIVEL"))
            .andExpect(flash().attribute("msg", "3 etiqueta(s) cadastrada(s) em lote."))

        val gravadas = epcs.map { tags.findByTagReal(it).get() }
        assertThat(gravadas.map { it.codigoInterno }).containsExactly("BT-$s-009", "BT-$s-010", "BT-$s-011")
        assertThat(gravadas.map { it.patrimonio }).containsExactly("PB-$s-1", "PB-$s-2", "PB-$s-3")
        assertThat(gravadas).allMatch { it.tipoEquipamentoId == tipo && it.localId == local.id && it.situacao == SituacaoBem.DISPONIVEL }
        assertThat(pendentes.jaPendentes(epcs)).isEmpty()
    }

    @Test
    fun `lote e tudo ou nada e so quem cadastra etiquetas usa`() {
        val epcs = (1..3).map { "FALHA$it-$s" }.onEach(::pendente)
        // O 2º patrimônio da sequência já é de outra etiqueta
        rfid.cadastrarTag(CadastroRequest(tag = "OUTRA-$s", codigoInterno = "TI-OUTRA-$s", patrimonio = "PF-$s-2"))

        mvc.perform(post("/admin/tags-pendentes/lote").with(Perfis.gestor()).with(csrf())
            .param("etiquetas", *epcs.toTypedArray())
            .param("codigoInicio", "1").param("patrimonioPrefixo", "PF-$s-").param("patrimonioInicio", "1"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Etiqueta ${epcs[1]} (2ª do lote)")))
            .andExpect(content().string(containsString("Nenhuma etiqueta foi cadastrada")))

        // Sem código inicial
        assertThatThrownBy { lote.cadastrar(epcs, CadastroRequest(), null, null) }.hasMessageContaining("código interno")

        mvc.perform(get("/admin/tags-pendentes/lote").param("todas", "true").with(Perfis.usuario())).andExpect(status().isForbidden)
        mvc.perform(post("/admin/tags-pendentes/lote").with(Perfis.usuario()).with(csrf()).param("etiquetas", epcs[0]).param("codigoInicio", "1"))
            .andExpect(status().isForbidden)
    }


    /** Sem a transação do teste (o IntegracaoBase abre uma em cada teste e esconderia o desfazer). */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `lote desfaz de verdade as etiquetas ja gravadas quando uma falha`() {
        val epc = "DESFAZ-$s"
        pendente(epc)
        try {
            // A 2ª etiqueta (em branco) é recusada depois de a 1ª ter sido gravada
            assertThatThrownBy { lote.cadastrar(listOf(epc, " "), CadastroRequest(), Sequencia("D-$s-", "1"), null) }
                .hasMessageContaining("Nenhuma etiqueta foi cadastrada")
            assertThat(tags.existsByTagReal(epc)).isFalse()
            assertThat(pendentes.jaPendentes(listOf(epc))).containsExactly(epc)
        } finally {
            pendentes.findAll().filter { it.tag == epc }.forEach(pendentes::delete)
        }
    }
    // ---------- Importação de clientes ----------

    private fun csv(vararg linhas: String) = ("Nome;CPF/CNPJ;Contato;Telefone;Email;Endereço;Observação\n" + linhas.joinToString("\n")).toByteArray()

    @Test
    fun `importacao de clientes simula, confirma, atualiza pelo documento e aponta os erros`() {
        val cnpj = "11" + s.hashCode().toUInt().toString().padStart(12, '0').take(12)
        val cpfExistente = "9" + (s.hashCode().toUInt() % 1_000_000_000u).toString().padStart(10, '0')
        locacao.salvarCliente(null, DadosCliente("Antigo $s", cpfExistente, telefone = "1111"))

        val sessao = MockHttpSession()
        val arquivo = MockMultipartFile("arquivo", "clientes.csv", "text/csv", csv(
            "Construtora $s;$cnpj;João;(11) 9999;COMPRAS@X.COM;Rua A, 1;",
            "Nome Novo $s;$cpfExistente;;;;;Cliente antigo",
            "Repetida $s;$cnpj;;;;;",
            "Doc Curto $s;12345;;;;;",
            ";;;;;;",
            "Email Ruim $s;;;;sem-arroba;;"
        ))
        mvc.perform(multipart("/admin/clientes/importar").file(arquivo).session(sessao).with(Perfis.gestor()).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Conferência da planilha")))
            .andExpect(content().string(containsString("CPF/CNPJ repetido na planilha (também na linha 2)")))
            .andExpect(content().string(containsString("não é um CPF (11 dígitos) nem um CNPJ")))
            .andExpect(content().string(containsString("email inválido")))
            .andExpect(content().string(containsString("altera: nome, observação")))
        assertThat(clientes.findFirstByDocumento(cnpj)).isNull()   // simulação não grava

        mvc.perform(post("/admin/clientes/importar/confirmar").session(sessao).with(Perfis.gestor()).with(csrf()))
            .andExpect(content().string(containsString("Importação concluída")))
        val novo = clientes.findFirstByDocumento(cnpj)!!
        assertThat(novo.nome).isEqualTo("Construtora $s")
        assertThat(novo.email).isEqualTo("compras@x.com")
        assertThat(novo.endereco).isEqualTo("Rua A, 1")
        val atualizado = clientes.findFirstByDocumento(cpfExistente)!!
        assertThat(atualizado.nome).isEqualTo("Nome Novo $s")
        assertThat(atualizado.telefone).isEqualTo("1111")   // célula vazia não apaga
        assertThat(atualizado.observacao).isEqualTo("Cliente antigo")
        assertThat(clientes.findAll().none { it.nome == "Doc Curto $s" || it.nome == "Email Ruim $s" }).isTrue()

        // Permissões: sem Criar na Locação não importa; o link aparece para quem pode
        mvc.perform(multipart("/admin/clientes/importar").file(arquivo).with(Perfis.usuario()).with(csrf())).andExpect(status().isForbidden)
        mvc.perform(get("/admin/clientes/importar/modelo").with(Perfis.gestor())).andExpect(status().isOk)
        mvc.perform(get("/admin/clientes").with(Perfis.gestor())).andExpect(content().string(containsString("/admin/clientes/importar")))
    }
}
