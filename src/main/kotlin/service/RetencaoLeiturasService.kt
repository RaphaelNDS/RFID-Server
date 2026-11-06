package org.example.service

import org.example.repository.LeituraAndroidRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

/**
 * Retenção das leituras: apaga as leituras com mais de [reterDias] dias, 10 min depois de o servidor subir e
 * depois a cada 24 h.
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
    @Value("\${rfid.leituras.reter-dias:0}") configurado: Long,
    /** Leituras apagadas por transação (ver [apagarAntigas]); menor só nos testes. */
    @Value("\${rfid.leituras.lote-retencao:10000}") tamanhoLote: Int = LOTE_PADRAO
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

    private val tamanhoLote: Int = tamanhoLote.coerceAtLeast(1)

    @EventListener(ApplicationReadyEvent::class)
    fun informarConfiguracao() {
        if (ativa) {
            log.info("Retenção de leituras ativa: leituras com mais de {} dias são apagadas 10 min depois de o servidor subir e a cada 24 h", reterDias)
        } else {
            log.info("Retenção de leituras desligada: as leituras ficam guardadas para sempre (rfid.leituras.reter-dias=0)")
        }
    }

    // 10 min depois de subir e depois a cada 24 h. Era às 03:30, mas com o computador desligado de madrugada
    // nunca rodava (o mesmo que aconteceu com o backup das 02:00)
    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT24H")
    fun executarAgendada() {
        if (!ativa) return
        // Falha aqui não pode derrubar nada: fica no log e tenta de novo em 24 h
        runCatching { apagarAntigas() }
            .onFailure { log.error("Falha ao apagar leituras antigas (retenção de {} dias): {}", reterDias, it.message, it) }
    }

    /**
     * Apaga as leituras anteriores ao limite em lotes de [tamanhoLote], cada lote na sua transação curta (no
     * repositório), até não sobrar nenhuma; devolve o total apagado. Sem @Transactional aqui de propósito: uma
     * transação em volta do laço juntaria tudo num DELETE gigante de novo. Se um lote falhar, os anteriores já
     * ficam apagados e o resto sai na próxima execução.
     */
    fun apagarAntigas(): Int {
        if (!ativa) return 0

        val limite = LocalDate.now(relogio).minusDays(reterDias).atStartOfDay()
        val inicio = System.currentTimeMillis()
        var apagadas = 0
        var lotes = 0
        while (true) {
            val noLote = repo.apagarLoteAnterioresA(limite, tamanhoLote)
            apagadas += noLote
            lotes++
            if (noLote < tamanhoLote) break
            log.debug("Retenção de leituras: lote {} com {} leitura(s) apagado(s); continuando", lotes, noLote)
        }

        if (apagadas > 0) {
            log.info(
                "Retenção de leituras: {} leitura(s) anteriores a {} apagada(s) em {} lote(s) de até {} em {} ms (guardando {} dias)",
                apagadas, limite.toLocalDate(), lotes, tamanhoLote, System.currentTimeMillis() - inicio, reterDias
            )
        } else {
            log.debug("Retenção de leituras: nenhuma leitura anterior a {}", limite.toLocalDate())
        }
        return apagadas
    }

    companion object {
        const val MINIMO_DIAS = 30L

        /** 10.000 leituras por transação: rápido o bastante por lote e sem segurar a tabela por minutos. */
        const val LOTE_PADRAO = 10_000
    }
}
