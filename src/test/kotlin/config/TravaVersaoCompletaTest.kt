package org.example.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.config.TravaVersaoCompleta.Aplicada
import org.example.model.SituacaoBem
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test

/** A regra da trava (sem banco) e a ordem na estratégia de migração: a trava vem antes do backup e da migração. */
class TravaVersaoCompletaTest {

    private fun v(versao: String, descricao: String = "x") = Aplicada(MigrationVersion.fromVersion(versao), descricao)

    private val ate33 = (1..33).map { v(it.toString()) }

    @Test
    fun `banco desta versao, de antes dela ou vazio passa`() {
        assertThat(TravaVersaoCompleta.motivo(emptyList(), 0)).isNull()
        assertThat(TravaVersaoCompleta.motivo(ate33, 0)).isNull()
        assertThat(TravaVersaoCompleta.motivo(ate33 + v("34", "movimentacao simples"), 0)).isNull()
        assertThat(TravaVersaoCompleta.motivo(ate33 + v("34", "movimentacao simples") + v("35", "simples sem movimentacao"), 0)).isNull()
        // Repetível (sem versão) não conta
        assertThat(TravaVersaoCompleta.motivo(ate33 + Aplicada(null, "visao"), 0)).isNull()
    }

    @Test
    fun `pedido de locacao, migracao depois da V35 ou outra V34 ou V35 recusam`() {
        val ate35 = ate33 + v("34", "movimentacao simples") + v("35", "simples sem movimentacao")
        assertThat(TravaVersaoCompleta.motivo(ate35, 1)).contains("pedidos_locacao")
        assertThat(TravaVersaoCompleta.motivo(ate33, 1)).contains("pedidos_locacao")
        // Depois da V35: a locadora tem V36 (campos personalizados) e V37 (comprovantes entrega)
        assertThat(TravaVersaoCompleta.motivo(ate35 + v("36", "campos personalizados"), 0)).contains("V36 (campos personalizados)")
        assertThat(TravaVersaoCompleta.motivo(ate35 + v("35.1", "correcao"), 0)).contains("V35.1")
        // V34 e V35 da locadora
        assertThat(TravaVersaoCompleta.motivo(ate33 + v("34", "restricao clientes"), 0))
            .contains("a V34 aplicada no banco é \"restricao clientes\"")
        assertThat(TravaVersaoCompleta.motivo(ate33 + v("34", "movimentacao simples") + v("35", "revisao automatica"), 0))
            .contains("a V35 aplicada no banco é \"revisao automatica\", e não \"simples sem movimentacao\"")
        // A V34 da locadora com uma V35 qualquer: recusa pela V34
        assertThat(TravaVersaoCompleta.motivo(ate33 + v("34", "restricao clientes") + v("35", "simples sem movimentacao"), 0))
            .contains("V34")
    }

    @Test
    fun `a trava roda antes de tudo e, recusando, nem o backup nem a migracao acontecem`() {
        val backup = mockk<BackupAntesMigracao>(relaxed = true)
        val trava = mockk<TravaVersaoCompleta>()
        val flyway = mockk<Flyway>(relaxed = true)
        every { trava.conferir(flyway) } throws IllegalStateException("Este banco é da versão completa (branch locadora)")

        assertThatThrownBy { MigracoesConfig("").estrategia(backup, trava).migrate(flyway) }.hasMessageContaining("branch locadora")
        verify(exactly = 0) { backup.executar() }
        verify(exactly = 0) { flyway.migrate() }
    }

    @Test
    fun `nomes antigos de situacao viram os de agora (planilha e app antigos)`() {
        assertThat(SituacaoBem.entries.map { it.name }).containsExactly("DISPONIVEL", "EXTRAVIADO", "BAIXADO")
        assertThat(SituacaoBem.de("Alugado")).isEqualTo(SituacaoBem.DISPONIVEL)
        assertThat(SituacaoBem.de("EM_DEVOLUCAO")).isEqualTo(SituacaoBem.DISPONIVEL)
        assertThat(SituacaoBem.de("em manutenção")).isEqualTo(SituacaoBem.DISPONIVEL)
        assertThat(SituacaoBem.de("À venda")).isEqualTo(SituacaoBem.DISPONIVEL)
        assertThat(SituacaoBem.de("Disponível")).isEqualTo(SituacaoBem.DISPONIVEL)
        assertThat(SituacaoBem.de("Vendido")).isEqualTo(SituacaoBem.BAIXADO)
        assertThat(SituacaoBem.de("no estoque")).isEqualTo(SituacaoBem.DISPONIVEL)
        // 2.0.0: "Fora do estoque" (planilha exportada antes da 2.1.0) volta para o estoque, como a V35 fez no banco
        assertThat(SituacaoBem.de("Fora do estoque")).isEqualTo(SituacaoBem.DISPONIVEL)
        assertThat(SituacaoBem.de("FORA")).isEqualTo(SituacaoBem.DISPONIVEL)
        assertThat(SituacaoBem.de("extraviado")).isEqualTo(SituacaoBem.EXTRAVIADO)
        assertThat(SituacaoBem.de("qualquer")).isNull()
    }
}
