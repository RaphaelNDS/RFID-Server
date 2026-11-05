package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.LeituraAndroidEntity
import org.example.repository.LeituraAndroidRepository
import org.example.service.RetencaoLeiturasService
import org.example.suporte.IntegracaoBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

class RetencaoLeiturasIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var leituras: LeituraAndroidRepository

    /**
     * Sem a transação do teste (o IntegracaoBase abre uma em cada teste e escondia o defeito): a tarefa agendada
     * chamava o @Transactional do serviço de dentro da própria classe, e o DELETE falhava com
     * TransactionRequiredException toda vez que a retenção estava ligada — nada era apagado.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `tarefa agendada apaga as leituras antigas de verdade`() {
        val antiga = leituras.save(LeituraAndroidEntity(tag = "E200RETENCAO-ANTIGA", dataHora = LocalDateTime.now().minusDays(400)))
        val recente = leituras.save(LeituraAndroidEntity(tag = "E200RETENCAO-RECENTE", dataHora = LocalDateTime.now().minusDays(10)))
        try {
            RetencaoLeiturasService(leituras, Clock.systemDefaultZone(), 365).executarAgendada()

            assertThat(leituras.findById(antiga.id)).isEmpty
            assertThat(leituras.findById(recente.id)).isPresent
        } finally {
            // Gravado fora de transação: limpa para não sobrar nos outros testes
            leituras.deleteAllById(listOf(antiga.id, recente.id).filter { leituras.existsById(it) })
        }
    }
}
