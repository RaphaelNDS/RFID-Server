package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.SessaoOperadorEntity
import org.example.repository.SessaoOperadorRepository
import org.example.service.SessaoOperadorService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime

/** Sessões de operador mortas há mais de 30 dias saem do banco; as que ainda podem valer ficam. */
class LimpezaSessoesIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var operadores: OperadorTeste
    @Autowired lateinit var sessoes: SessaoOperadorRepository
    @Autowired lateinit var service: SessaoOperadorService

    @Test
    fun `apaga so as sessoes mortas ha mais de 30 dias`() {
        val usuario = operadores.criarUsuario(email = "limpeza@x.com").id!!
        val agora = LocalDateTime.now()
        // A sessão morre de vez 72 h (prazo de sincronização) depois de expirar
        val morte = agora.minus(service.janelaSincronizacao)

        fun sessao(hash: String, expiraEm: LocalDateTime) =
            sessoes.save(SessaoOperadorEntity(hash, usuario, "coletor-teste", expiraEm.minusHours(12), expiraEm))

        sessao("antiga", morte.minusDays(31))
        sessao("morta-recente", morte.minusDays(29))
        sessao("expirada-ainda-sincroniza", agora.minusHours(1))
        sessao("aberta", agora.plusHours(5))

        val apagadas = service.apagarSessoesMortas()

        assertThat(apagadas).isEqualTo(1)
        assertThat(sessoes.findAll().map { it.tokenHash })
            .contains("morta-recente", "expirada-ainda-sincroniza", "aberta")
            .doesNotContain("antiga")
    }
}
