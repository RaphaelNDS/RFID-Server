package org.example.service

import org.example.repository.LeituraAndroidRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * Retenção das leituras: apaga as leituras com mais de [reterDias] dias, todo dia de madrugada.
 *
 * DESLIGADA por padrão (rfid.leituras.reter-dias = 0): apagar leituras muda os relatórios, então é decisão
 * do responsável. Inventários e cadastro de etiquetas ficam em outras tabelas e não são afetados.
 *
 * Mínimo de [MINIMO_DIAS] dias: a data da leitura é a hora em que ela aconteceu no coletor, e um coletor
 * offline pode enviá-la dias depois (janela de sincronização); um prazo curto apagaria leitura recém-chegada.
 */
@Service
class RetencaoLeiturasService(
    private val repo: LeituraAndroidRepository,
    private val relogio: Clock,
    @Value("\${rfid.leituras.reter-dias:0}") configurado: Long
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Dias efetivamente usados (0 = desligada). */
    val reterDias: Long = when {
        configurado <= 0 -> 0
        configurado < MINIMO_DIAS -> {
            log.warn(
                "rfid.leituras.reter-dias={} é menor que o mínimo de {} dias: usando {} (leituras offline chegam com a data antiga)",
                configurado, MINIMO_DIAS, MINIMO_DIAS
            )
            MINIMO_DIAS
        }
        else -> configurado
    }

    val ativa: Boolean get() = reterDias > 0

    @EventListener(ApplicationReadyEvent::class)
    fun informarConfiguracao() {
        if (ativa) {
            log.info("Retenção de leituras ativa: leituras com mais de {} dias são apagadas todo dia às 03:30", reterDias)
        } else {
            log.info("Retenção de leituras desligada: as leituras ficam guardadas para sempre (rfid.leituras.reter-dias=0)")
        }
    }

    @Scheduled(cron = "\${rfid.leituras.retencao-cron:0 30 3 * * *}")
    fun executarAgendada() {
        if (!ativa) return
        // Falha aqui não pode derrubar nada: fica no log e tenta de novo amanhã
        runCatching { apagarAntigas() }
            .onFailure { log.error("Falha ao apagar leituras antigas (retenção de {} dias): {}", reterDias, it.message, it) }
    }

    /** Apaga as leituras anteriores ao limite e devolve quantas foram apagadas. */
    @Transactional
    fun apagarAntigas(): Int {
        if (!ativa) return 0

        val limite = LocalDate.now(relogio).minusDays(reterDias).atStartOfDay()
        val inicio = System.currentTimeMillis()
        val apagadas = repo.apagarAnterioresA(limite)

        if (apagadas > 0) {
            log.info(
                "Retenção de leituras: {} leitura(s) anteriores a {} apagada(s) em {} ms (guardando {} dias)",
                apagadas, limite.toLocalDate(), System.currentTimeMillis() - inicio, reterDias
            )
        } else {
            log.debug("Retenção de leituras: nenhuma leitura anterior a {}", limite.toLocalDate())
        }
        return apagadas
    }

    companion object {
        const val MINIMO_DIAS = 30L
    }
}
