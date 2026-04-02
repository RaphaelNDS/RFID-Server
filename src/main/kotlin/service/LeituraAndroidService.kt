package org.example.service

import org.example.controller.LeituraAndroidStreamController
import org.example.model.LeituraAndroidEntity
import org.example.model.TipoMovimento
import org.example.request.MovimentoExpedicaoRequest
import org.example.repository.*
import org.springframework.stereotype.Service

@Service
class LeituraAndroidService(
    private val leituraRepo: LeituraAndroidRepository,
    private val tagRepo: TagRepository,
    private val tipoRepo: TipoEquipamentoRepository,
    private val marcaRepo: MarcaRepository,
    private val modeloRepo: ModeloRepository,
    private val streamController: LeituraAndroidStreamController
) {

    fun salvarLeitura(tagReal: String) {

        val tag = tagRepo.findByTagReal(tagReal).orElse(null) ?: return

        if (
            tag.codigoInterno.isNullOrBlank() ||
            tag.tipoEquipamentoId == null ||
            tag.marcaId == null ||
            tag.modeloId == null ||
            tag.patrimonio.isNullOrBlank() ||
            tag.numeroSerie.isNullOrBlank()
        ) return

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

        streamController.publicar(leitura)
    }

    fun registrarMovimento(leituraId: Long, req: MovimentoExpedicaoRequest) {

        if (req.movimento == TipoMovimento.ENTRADA && req.motivoEntrada.isNullOrBlank()) {
            throw RuntimeException("Informe o tipo de entrada")
        }

        val leitura = leituraRepo.findById(leituraId)
            .orElseThrow { RuntimeException("Leitura não encontrada") }

        leitura.movimento = req.movimento
        leitura.motivoEntrada = req.motivoEntrada?.takeIf { it.isNotBlank() }
        leitura.destino = req.destino?.takeIf { it.isNotBlank() }

        leituraRepo.save(leitura)
    }
}
