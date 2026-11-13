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
        // Repetível (sem versão) não conta
        assertThat(TravaVersaoCompleta.motivo(ate33 + Aplicada(null, "visao"), 0)).isNull()
    }

    @Test
    fun `pedido de locacao, migracao depois da V34 ou outra V34 recusam`() {
        assertThat(TravaVersaoCompleta.motivo(ate33, 1)).contains("pedidos_locacao")
        assertThat(TravaVersaoCompleta.motivo(ate33 + v("34", "movimentacao simples") + v("35", "algo novo"), 0))
            .contains("V35 (algo novo)")
        assertThat(TravaVersaoCompleta.motivo(ate33 + v("34.1", "correcao"), 0)).contains("V34.1")
        assertThat(TravaVersaoCompleta.motivo(ate33 + v("34", "kits por cliente"), 0)).contains("\"kits por cliente\"")
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
        assertThat(SituacaoBem.de("Alugado")).isEqualTo(SituacaoBem.FORA)
        assertThat(SituacaoBem.de("EM_DEVOLUCAO")).isEqualTo(SituacaoBem.FORA)
        assertThat(SituacaoBem.de("em manutenção")).isEqualTo(SituacaoBem.FORA)
        assertThat(SituacaoBem.de("À venda")).isEqualTo(SituacaoBem.DISPONIVEL)
        assertThat(SituacaoBem.de("Disponível")).isEqualTo(SituacaoBem.DISPONIVEL)
        assertThat(SituacaoBem.de("Vendido")).isEqualTo(SituacaoBem.BAIXADO)
        assertThat(SituacaoBem.de("no estoque")).isEqualTo(SituacaoBem.DISPONIVEL)
        assertThat(SituacaoBem.de("Fora do estoque")).isEqualTo(SituacaoBem.FORA)
        assertThat(SituacaoBem.de("qualquer")).isNull()
    }
}
