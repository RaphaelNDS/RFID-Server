package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.AcaoHistorico
import org.example.model.HistoricoEtiquetaEntity
import org.example.model.InventarioEntity
import org.example.model.LeituraAndroidEntity
import org.example.model.StatusInventario
import org.example.repository.HistoricoEtiquetaRepository
import org.example.repository.InventarioRepository
import org.example.repository.LeituraAndroidRepository
import org.example.service.AtividadeOperador
import org.example.service.DiscoService
import org.example.service.RelatorioOperadoresService
import org.example.service.SituacaoDisco
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** 1.32.0: relatório por operador e espaço em disco. */
class Versao1320IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var relatorio: RelatorioOperadoresService
    @Autowired lateinit var leituras: LeituraAndroidRepository
    @Autowired lateinit var inventarios: InventarioRepository
    @Autowired lateinit var historico: HistoricoEtiquetaRepository
    @Autowired lateinit var operadores: OperadorTeste
    @Autowired lateinit var disco: DiscoService
    @Autowired lateinit var cadastros: org.example.service.PatrimonioCadastrosService

    private val s = UUID.randomUUID().toString().take(6).uppercase()

    @Test
    fun `relatorio soma por pessoa juntando o email da web e o nome do coletor`() {
        val nome = "Olga $s"
        operadores.criarUsuario(email = "olga-$s@x.com", nome = nome)
        val agora = LocalDateTime.now()
        val local = cadastros.salvarLocal(null, "Local Op $s", null).id
        // Coletor grava o nome; web grava o email: os dois somam na mesma pessoa
        leituras.save(LeituraAndroidEntity(tag = "OP1-$s", dataHora = agora, operador = nome))
        leituras.save(LeituraAndroidEntity(tag = "OP2-$s", dataHora = agora.minusDays(1), operador = nome))
        leituras.save(LeituraAndroidEntity(tag = "OP3-$s", dataHora = agora.minusDays(30), operador = nome))   // fora do período
        historico.save(HistoricoEtiquetaEntity(tagId = null, tagReal = "OP1-$s", acao = AcaoHistorico.CADASTRO,
            usuario = "olga-$s@x.com", origem = "Web", momento = agora))
        inventarios.save(InventarioEntity(id = UUID.randomUUID().toString(), localId = local, dispositivo = "c", operador = nome,
            status = StatusInventario.FINALIZADO, iniciadoEm = agora))
        inventarios.save(InventarioEntity(id = UUID.randomUUID().toString(), localId = local, dispositivo = "c", operador = nome,
            status = StatusInventario.FINALIZADO, iniciadoEm = agora, treino = true))   // treino não conta
        leituras.save(LeituraAndroidEntity(tag = "OP4-$s", dataHora = agora, operador = "Outra $s"))

        val hoje = LocalDate.now()
        val r = relatorio.relatorio(hoje.minusDays(6), hoje)
        val olga = r.linhas.single { it.pessoa == nome }
        assertThat(olga.de(AtividadeOperador.LEITURAS)).isEqualTo(2)
        assertThat(olga.de(AtividadeOperador.CADASTROS)).isEqualTo(1)
        assertThat(olga.de(AtividadeOperador.INVENTARIOS)).isEqualTo(1)
        assertThat(olga.total).isEqualTo(4)
        assertThat(olga.dias).isEqualTo(2)
        assertThat(r.linhas.single { it.pessoa == "Outra $s" }.total).isEqualTo(1)
        assertThat(r.porDia.first().first).isEqualTo(hoje)
        assertThatThrownBy { relatorio.relatorio(hoje, hoje.minusDays(1)) }.hasMessageContaining("antes do início")

        mvc.perform(get("/admin/relatorio-operadores").with(Perfis.usuario()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString(nome)))
            .andExpect(content().string(containsString("Etiquetas em inventário")))
        mvc.perform(get("/admin/relatorio-operadores").param("inicio", hoje.toString()).param("fim", hoje.minusDays(3).toString()).with(Perfis.usuario()))
            .andExpect(content().string(containsString("antes do início")))
        mvc.perform(get("/admin/relatorio-operadores").with(Perfis.somenteCatalogo())).andExpect(status().isForbidden)
        mvc.perform(get("/dashboard").with(Perfis.usuario())).andExpect(content().string(containsString("Por operador")))
    }

    @Test
    fun `espaco em disco na tela status`() {
        val d = disco.situacao()
        assertThat(d.total).isGreaterThan(0)
        assertThat(d.livre).isBetween(0, d.total)
        assertThat(d.partes.map { it.nome }).contains("Banco de dados", "Logs")
        assertThat(d.partes.single { it.nome == "Banco de dados" }.bytes).isNotNull().isGreaterThan(0)
        assertThat(SituacaoDisco.tamanho(5L * 1024 * 1024 * 1024)).isEqualTo("5,0 GB")
        assertThat(SituacaoDisco.tamanho(3L * 1024 * 1024)).isEqualTo("3 MB")
        assertThat(SituacaoDisco.tamanho(null)).isEqualTo("—")
        // Com um mínimo enorme, qualquer disco tem "pouco espaço"
        assertThat(DiscoService(1_000_000, 10, "servidor").situacao().pouco).isTrue()

        mvc.perform(get("/status").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Espaço em disco")))
            .andExpect(content().string(containsString("Banco de dados")))
    }
}
