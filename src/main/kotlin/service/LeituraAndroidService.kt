package org.example.service

import org.example.model.LeituraAndroidEntity
import org.example.repository.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

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

    fun salvarLeitura(tagReal: String) {

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
            numeroSerie = tag.numeroSerie
        )

        leituraRepo.save(leitura)

        log.debug("Leitura registrada: tag {} (patrimônio {})", tagReal, tag.patrimonio)

        eventos.publicar(leitura)
    }
}
