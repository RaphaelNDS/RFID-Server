package org.example.service

import org.example.model.LeituraAndroidEntity
import org.example.repository.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

@Service
class LeituraAndroidService(
    private val leituraRepo: LeituraAndroidRepository,
    private val tagRepo: TagRepository,
    private val tipoRepo: TipoEquipamentoRepository,
    private val marcaRepo: MarcaRepository,
    private val modeloRepo: ModeloRepository,
    private val eventos: LeituraEventosService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun salvarLeitura(tagReal: String, lidoEm: OffsetDateTime? = null, operador: String? = null) {

        require(tagReal.isNotBlank()) { "Informe a TAG RFID" }

        // Leituras descartadas ficam em DEBUG: o leitor pode repetir a mesma tag várias vezes por segundo
        val tag = tagRepo.findByTagReal(tagReal).orElse(null) ?: run {
            log.debug("Leitura ignorada: tag {} não está cadastrada", tagReal)
            return
        }

        if (
            tag.codigoInterno.isNullOrBlank() ||
            tag.tipoEquipamentoId == null ||
            tag.marcaId == null ||
            tag.modeloId == null ||
            tag.patrimonio.isNullOrBlank() ||
            tag.numeroSerie.isNullOrBlank()
        ) {
            log.debug("Leitura ignorada: tag {} tem cadastro incompleto (tipo, marca, modelo, patrimônio ou série)", tagReal)
            return
        }

        val leitura = LeituraAndroidEntity(
            tag = tagReal,
            codigoInterno = tag.codigoInterno,
            tipo = tipoRepo.findById(tag.tipoEquipamentoId!!).orElseThrow().nome,
            marca = marcaRepo.findById(tag.marcaId!!).orElseThrow().nome,
            modelo = modeloRepo.findById(tag.modeloId!!).orElseThrow().nome,
            patrimonio = tag.patrimonio,
            numeroSerie = tag.numeroSerie,
            dataHora = dataHoraDaLeitura(tagReal, lidoEm),
            operador = operador
        )

        leituraRepo.save(leitura)

        log.debug("Leitura registrada: tag {} (patrimônio {}, operador {})", tagReal, tag.patrimonio, operador ?: "—")

        eventos.publicar(leitura)
    }

    /**
     * Usa a hora informada pelo coletor (leituras offline chegam depois de feitas), convertida
     * para o fuso do servidor. Sem ela, ou se estiver no futuro (relógio do coletor adiantado),
     * vale a hora do recebimento.
     */
    private fun dataHoraDaLeitura(tagReal: String, lidoEm: OffsetDateTime?): LocalDateTime {
        val agora = LocalDateTime.now()
        if (lidoEm == null) return agora

        val lida = lidoEm.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()

        if (lida.isAfter(agora.plus(TOLERANCIA_RELOGIO))) {
            log.warn(
                "Leitura da tag {} com data no futuro ({}): relógio do coletor adiantado? Usando a hora do recebimento",
                tagReal, lidoEm
            )
            return agora
        }

        val atraso = Duration.between(lida, agora)
        if (atraso > ATRASO_LEITURA_OFFLINE) {
            log.info("Leitura offline recebida: tag {} lida em {} ({} min atrás)", tagReal, lida, atraso.toMinutes())
        }

        return lida
    }

    private companion object {
        val TOLERANCIA_RELOGIO: Duration = Duration.ofMinutes(5)
        val ATRASO_LEITURA_OFFLINE: Duration = Duration.ofMinutes(1)
    }
}
