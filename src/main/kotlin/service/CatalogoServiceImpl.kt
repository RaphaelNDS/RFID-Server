package org.example.service

import jakarta.transaction.Transactional
import org.example.model.MarcaEntity
import org.example.model.ModeloEntity
import org.example.model.TipoEquipamentoEntity
import org.example.repository.MarcaRepository
import org.example.repository.ModeloRepository
import org.example.repository.TipoEquipamentoRepository
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.springframework.stereotype.Service

@Service
class CatalogoServiceImpl(
    private val tipoRepo: TipoEquipamentoRepository,
    private val marcaRepo: MarcaRepository,
    private val modeloRepo: ModeloRepository
) : CatalogoService {

    override fun listarTipos() = tipoRepo.findAll()
    override fun listarMarcas() = marcaRepo.findAll()
    override fun listarModelos() = modeloRepo.findAll()

    override fun listarMarcasPorTipo(tipoId: Long) =
        marcaRepo.findByTipoId(tipoId)

    override fun listarModelosPorMarca(marcaId: Long) =
        modeloRepo.findByMarcaId(marcaId)

    @Transactional
    override fun salvarTipo(req: TipoEquipamentoRequest) {
        val tipo = TipoEquipamentoEntity(nome = req.nome)
        tipoRepo.save(tipo)
    }

    @Transactional
    override fun salvarMarca(req: MarcaRequest) {
        val tipo = tipoRepo.findById(req.tipoId!!)
            .orElseThrow { IllegalArgumentException("Tipo não encontrado") }

        val marca = MarcaEntity(
            nome = req.nome,
            tipo = tipo
        )
        marcaRepo.save(marca)
    }

    @Transactional
    override fun salvarModelo(req: ModeloRequest) {
        val marca = marcaRepo.findById(req.marcaId!!)
            .orElseThrow { IllegalArgumentException("Marca não encontrada") }

        val modelo = ModeloEntity(
            nome = req.nome,
            marca = marca
        )
        modeloRepo.save(modelo)
    }
}

