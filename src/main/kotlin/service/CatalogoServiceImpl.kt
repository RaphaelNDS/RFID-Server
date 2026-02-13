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

    override fun listarTipos(): List<TipoEquipamentoEntity> =
        tipoRepo.findAll()

    override fun listarMarcas(): List<MarcaEntity> =
        marcaRepo.findAll()

    override fun listarModelos(): List<ModeloEntity> =
        modeloRepo.findAll()

    override fun listarMarcasPorTipo(tipoId: Long): List<MarcaEntity> =
        marcaRepo.findByTipoId(tipoId)

    override fun listarModelosPorMarca(marcaId: Long): List<ModeloEntity> =
        modeloRepo.findByMarcaId(marcaId)

    // SALVAR TIPO

    @Transactional
    override fun salvarTipo(req: TipoEquipamentoRequest) {

        val nome = req.nome.trim()

        val existente = tipoRepo.findByNomeIgnoreCase(nome)

        if (existente.isPresent) {
            throw IllegalArgumentException("Tipo já cadastrado!")
        }

        tipoRepo.save(
            TipoEquipamentoEntity(
                nome = nome
            )
        )
    }

    // SALVAR MARCA

    @Transactional
    override fun salvarMarca(req: MarcaRequest) {

        val nome = req.nome.trim()

        val tipo = tipoRepo.findById(req.tipoId!!)
            .orElseThrow { IllegalArgumentException("Tipo não encontrado") }

        val existente = marcaRepo
            .findByNomeIgnoreCaseAndTipoId(nome, tipo.id!!)

        if (existente.isPresent) {
            throw IllegalArgumentException("Marca já cadastrada para esse tipo!")
        }

        marcaRepo.save(
            MarcaEntity(
                nome = nome,
                tipo = tipo
            )
        )
    }


    // SALVAR MODELO

    @Transactional
    override fun salvarModelo(req: ModeloRequest) {

        val nome = req.nome.trim()

        val marca = marcaRepo.findById(req.marcaId!!)
            .orElseThrow { IllegalArgumentException("Marca não encontrada") }

        val existente = modeloRepo
            .findByNomeIgnoreCaseAndMarcaId(nome, marca.id!!)

        if (existente.isPresent) {
            throw IllegalArgumentException("Modelo já cadastrado para essa marca!")
        }

        modeloRepo.save(
            ModeloEntity(
                nome = nome,
                marca = marca
            )
        )
    }
}






/*
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

        val existente = tipoRepo.findByNomeIgnoreCase(req.nome.trim())

        if (existente.isPresent) {
            throw IllegalArgumentException("Tipo já cadastrado!")
        }

        val tipo = TipoEquipamentoEntity(
            nome = req.nome.trim()
        )

        tipoRepo.save(tipo)
    }

    @Transactional
    override fun salvarMarca(req: MarcaRequest) {

        val tipo = tipoRepo.findById(req.tipoId!!)
            .orElseThrow { IllegalArgumentException("Tipo não encontrado") }

        val existente = marcaRepo
            .findByNomeIgnoreCaseAndTipoId(req.nome.trim(), tipo.id!!)

        if (existente.isPresent) {
            throw IllegalArgumentException("Marca já cadastrada para esse tipo!")
        }

        val marca = MarcaEntity(
            nome = req.nome.trim(),
            tipo = tipo
        )

        marcaRepo.save(marca)
    }

    @Transactional
    override fun salvarModelo(req: ModeloRequest) {

        val marca = marcaRepo.findById(req.marcaId!!)
            .orElseThrow { IllegalArgumentException("Marca não encontrada") }

        val existente = modeloRepo
            .findByNomeIgnoreCaseAndMarcaId(req.nome.trim(), marca.id!!)

        if (existente.isPresent) {
            throw IllegalArgumentException("Modelo já cadastrado para essa marca!")
        }

        val modelo = ModeloEntity(
            nome = req.nome.trim(),
            marca = marca
        )

        modeloRepo.save(modelo)
    }

//    @Transactional
//    override fun salvarTipo(req: TipoEquipamentoRequest) {
//        val tipo = TipoEquipamentoEntity(nome = req.nome)
//        tipoRepo.save(tipo)
//    }




//    @Transactional
//    override fun salvarMarca(req: MarcaRequest) {
//        val tipo = tipoRepo.findById(req.tipoId!!)
//            .orElseThrow { IllegalArgumentException("Tipo não encontrado") }
//
//        val marca = MarcaEntity(
//            nome = req.nome,
//            tipo = tipo
//        )
//        marcaRepo.save(marca)
//    }

//    @Transactional
//    override fun salvarModelo(req: ModeloRequest) {
//        val marca = marcaRepo.findById(req.marcaId!!)
//            .orElseThrow { IllegalArgumentException("Marca não encontrada") }
//
//        val modelo = ModeloEntity(
//            nome = req.nome,
//            marca = marca
//        )
//        modeloRepo.save(modelo)
//    }
}

*/