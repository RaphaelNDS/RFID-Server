package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.Acao
import org.example.model.ColetorEntity
import org.example.model.Modulo
import org.example.model.TagNaoCadastradaEntity
import org.example.repository.ColetorRepository
import org.example.repository.LeituraAndroidRepository
import org.example.repository.TagNaoCadastradaRepository
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.service.InventarioCiclicoService
import org.example.service.InventarioService
import org.example.service.ItemLido
import org.example.service.PadraoEpc
import org.example.service.PadraoEtiquetasService
import org.example.service.PatrimonioCadastrosService
import org.example.service.RfidService
import org.example.service.SituacaoCiclo
import org.example.service.TreinamentoColetorService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.example.suporte.comoOperador
import org.hamcrest.Matchers.containsString
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
import java.time.LocalDateTime
import java.util.UUID

/** 1.31.0: padrão das etiquetas da empresa e modo treinamento do coletor. */
class Versao1310IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var padrao: PadraoEtiquetasService
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var pendentes: TagNaoCadastradaRepository
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var leituras: LeituraAndroidRepository
    @Autowired lateinit var coletores: ColetorRepository
    @Autowired lateinit var treinamento: TreinamentoColetorService
    @Autowired lateinit var inventarios: InventarioService
    @Autowired lateinit var ciclico: InventarioCiclicoService
    @Autowired lateinit var cadastros: PatrimonioCadastrosService
    @Autowired lateinit var operadores: OperadorTeste

    private val s = UUID.randomUUID().toString().take(6).uppercase()

    @Test
    fun `padrao das etiquetas deixa de fora das pendentes o que nao e da empresa`() {
        val p = PadraoEpc(listOf("E280"), listOf(24))
        assertThat(p.aceita("E2801191A504006F3C6E11A1")).isTrue()
        assertThat(p.aceita("e2801191a504006f3c6e11a1")).isTrue()
        assertThat(p.aceita("000000000000000000900028")).isFalse()
        assertThat(p.aceita("E2801191")).isFalse()
        assertThat(PadraoEpc(emptyList(), emptyList()).aceita("QUALQUER")).isTrue()
        assertThatThrownBy { padrao.definir("E28G", "", false) }.hasMessageContaining("Começo inválido")
        assertThatThrownBy { padrao.definir("", "2", false) }.hasMessageContaining("Tamanho inválido")

        // Já pendentes antes do padrão: uma fora e uma dentro (24 caracteres)
        val foraAntiga = "00000000000000000090" + s.take(4)
        val dentroAntiga = "E280000000000000000" + s.take(5)
        pendentes.save(TagNaoCadastradaEntity(tag = foraAntiga, dataHora = LocalDateTime.now()))
        pendentes.save(TagNaoCadastradaEntity(tag = dentroAntiga, dataHora = LocalDateTime.now()))

        mvc.perform(post("/admin/personalizar/etiquetas").with(Perfis.admin()).with(csrf())
            .param("prefixos", "e280").param("tamanhos", "24").param("limpar", "true"))
            .andExpect(flash().attribute("msg", containsString("começando com E280, com 24 caracteres")))
        assertThat(pendentes.jaPendentes(listOf(foraAntiga, dentroAntiga))).containsExactly(dentroAntiga)

        // Lidas agora: a de fora não vira pendente (nem pela rota antiga nem pelo lote)
        val fora = "00000000000000000091" + s.take(4)
        val foraLote = "0000$s"
        val dentro = "E280111111111111111" + s.take(5)
        rfid.salvarNaoCadastrada(fora)
        rfid.registrarPendenteDoLote(foraLote)
        rfid.salvarNaoCadastrada(dentro)
        assertThat(pendentes.jaPendentes(listOf(fora, foraLote, dentro))).containsExactly(dentro)
        assertThat(padrao.ignoradas().ultimas).contains(fora, foraLote)
        // Fora do padrão ainda pode ser cadastrada à mão
        rfid.cadastrarTag(CadastroRequest(tag = fora, codigoInterno = "TI-$s"))
        assertThat(tags.existsByTagReal(fora)).isTrue()

        mvc.perform(get("/admin/tags-pendentes").with(Perfis.usuario()))
            .andExpect(content().string(containsString("Só entram as etiquetas da empresa")))
        mvc.perform(get("/admin/personalizar").with(Perfis.admin()))
            .andExpect(content().string(containsString("Etiquetas da empresa")))
            .andExpect(content().string(containsString("Ignoradas desde")))
        mvc.perform(post("/admin/personalizar/etiquetas").with(Perfis.gestor()).with(csrf()).param("prefixos", ""))
            .andExpect(status().isForbidden)

        // Os dois vazios desligam
        padrao.definir("", "", false)
        assertThat(padrao.padrao().ativo).isFalse()
        rfid.salvarNaoCadastrada("0000X$s")
        assertThat(pendentes.jaPendentes(listOf("0000X$s"))).hasSize(1)
    }

    @Test
    fun `coletor em treinamento le e inventaria sem mudar nada`() {
        val coletor = "coletor-teste"
        operadores.criarUsuario(email = "treino-$s@x.com", permissoes = arrayOf(
            Modulo.LEITURA to Acao.READ, Modulo.RFID to Acao.CREATE, Modulo.RFID to Acao.UPDATE))
        val token = operadores.entrar(mvc, "treino-$s@x.com")
        if (!coletores.existsById(coletor)) coletores.save(ColetorEntity(coletor, LocalDateTime.now(), LocalDateTime.now()))

        val galpao = cadastros.salvarLocal(null, "Galpão T $s", null)
        val patio = cadastros.salvarLocal(null, "Pátio T $s", null)
        val epc = "TREINO-$s"
        rfid.cadastrarTag(CadastroRequest(tag = epc, codigoInterno = "TI-T$s", localId = galpao.id))

        mvc.perform(post("/admin/coletores/$coletor/treinamento").with(Perfis.admin()).with(csrf()).param("ligado", "true"))
            .andExpect(flash().attribute("msg", containsString("em modo treinamento")))
        assertThat(treinamento.emTreinamento(coletor)).isTrue()
        mvc.perform(get("/admin/coletores").with(Perfis.admin())).andExpect(content().string(containsString("Sair do treinamento")))

        // Leitura e pendente: respondem normalmente, nada é gravado
        mvc.perform(post("/api/android/leitura").comoOperador(token).contentType(MediaType.APPLICATION_JSON).content("{\"tag\":\"$epc\"}"))
            .andExpect(status().isOk)
        assertThat(leituras.findFirstByTagOrderByDataHoraDesc(epc)).isNull()
        mvc.perform(post("/api/android/naocadastrada").comoOperador(token).contentType(MediaType.APPLICATION_JSON).content("{\"tag\":\"NOVA-T$s\"}"))
            .andExpect(status().isOk)
        assertThat(pendentes.jaPendentes(listOf("NOVA-T$s"))).isEmpty()

        // Cadastro e transferência: recusados com o aviso
        mvc.perform(post("/api/android/tags").comoOperador(token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"tag\":\"CAD-T$s\",\"codigoInterno\":\"TI-C$s\"}"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.mensagem", containsString("modo treinamento")))
        assertThat(tags.existsByTagReal("CAD-T$s")).isFalse()
        mvc.perform(post("/api/android/tags/$epc/transferencia").comoOperador(token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"local\":\"Pátio T $s\"}"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.mensagem", containsString("modo treinamento")))

        // Inventário de treino no pátio: o bem continua no galpão, e o galpão continua "nunca contado" no ciclo
        val idPatio = UUID.randomUUID().toString()
        assertThat(inventarios.abrir(idPatio, patio.nome, null, coletor).treino).isTrue()
        inventarios.registrarItens(idPatio, listOf(ItemLido(epc, null)), coletor)
        inventarios.finalizar(idPatio, null, coletor)
        assertThat(tags.findByTagReal(epc).get().localId).isEqualTo(galpao.id)
        val idGalpao = UUID.randomUUID().toString()
        inventarios.abrir(idGalpao, galpao.nome, null, coletor)
        inventarios.registrarItens(idGalpao, listOf(ItemLido(epc, null)), coletor)
        inventarios.finalizar(idGalpao, null, coletor)
        assertThat(ciclico.plano().locais.single { it.local.id == galpao.id }.situacao).isEqualTo(SituacaoCiclo.NUNCA)
        mvc.perform(get("/admin/inventarios").with(Perfis.usuario())).andExpect(content().string(containsString(">Treino</span>")))

        // Só o administrador muda; fora do treinamento volta a gravar
        mvc.perform(post("/admin/coletores/$coletor/treinamento").with(Perfis.gestor()).with(csrf()).param("ligado", "false"))
            .andExpect(status().isForbidden)
        mvc.perform(post("/admin/coletores/$coletor/treinamento").with(Perfis.admin()).with(csrf()).param("ligado", "false"))
        assertThat(treinamento.emTreinamento(coletor)).isFalse()
        mvc.perform(post("/api/android/leitura").comoOperador(token).contentType(MediaType.APPLICATION_JSON).content("{\"tag\":\"$epc\"}"))
            .andExpect(status().isOk)
        assertThat(leituras.findFirstByTagOrderByDataHoraDesc(epc)).isNotNull()
        assertThat(inventarios.abrir(UUID.randomUUID().toString(), patio.nome, null, coletor).treino).isFalse()
    }
}
