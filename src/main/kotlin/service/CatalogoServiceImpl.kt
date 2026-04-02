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

    /* =====================================================
       LISTAGENS
    ====================================================== */

    override fun listarTipos() = tipoRepo.findAll()

    override fun listarMarcas() = marcaRepo.findAll()

    override fun listarModelos() = modeloRepo.findAll()

    override fun listarMarcasPorTipo(tipoId: Long) =
        marcaRepo.findByTipoId(tipoId)

    override fun listarModelosPorMarca(marcaId: Long) =
        modeloRepo.findByMarcaId(marcaId)

    /* =====================================================
       SALVAR TIPO
    ====================================================== */

    @Transactional
    override fun salvarTipo(req: TipoEquipamentoRequest): TipoEquipamentoEntity {

        val nome = req.nome.trim()

        require(nome.isNotBlank()) { "Nome do tipo não pode ser vazio" }

        if (tipoRepo.findByNomeIgnoreCase(nome).isPresent) {
            throw IllegalArgumentException("Tipo já cadastrado!")
        }

        return tipoRepo.save(
            TipoEquipamentoEntity(nome = nome)
        )
    }

    /* =====================================================
       SALVAR MARCA
    ====================================================== */

    @Transactional
    override fun salvarMarca(req: MarcaRequest): MarcaEntity {

        val nome = req.nome.trim()
        require(nome.isNotBlank()) { "Nome da marca não pode ser vazio" }

        val tipo = tipoRepo.findById(req.tipoId!!)
            .orElseThrow { IllegalArgumentException("Tipo não encontrado") }

        if (marcaRepo.findByNomeIgnoreCaseAndTipoId(nome, tipo.id!!).isPresent) {
            throw IllegalArgumentException("Marca já cadastrada para esse tipo!")
        }

        return marcaRepo.save(
            MarcaEntity(
                nome = nome,
                tipo = tipo
            )
        )
    }

    /* =====================================================
       SALVAR MODELO
    ====================================================== */

    @Transactional
    override fun salvarModelo(req: ModeloRequest): ModeloEntity {

        val nome = req.nome.trim()
        require(nome.isNotBlank()) { "Nome do modelo não pode ser vazio" }

        val marca = marcaRepo.findById(req.marcaId!!)
            .orElseThrow { IllegalArgumentException("Marca não encontrada") }

        if (modeloRepo.findByNomeIgnoreCaseAndMarcaId(nome, marca.id!!).isPresent) {
            throw IllegalArgumentException("Modelo já cadastrado para essa marca!")
        }

        return modeloRepo.save(
            ModeloEntity(
                nome = nome,
                marca = marca
            )
        )
    }

    /* =====================================================
       EDITAR
    ====================================================== */

    @Transactional
    override fun editarTipo(id: Long, req: TipoEquipamentoRequest): TipoEquipamentoEntity {

        val tipo = tipoRepo.findById(id)
            .orElseThrow { IllegalArgumentException("Tipo não encontrado") }

        val novoNome = req.nome.trim()
        require(novoNome.isNotBlank()) { "Nome inválido" }

        if (tipoRepo.findByNomeIgnoreCase(novoNome)
                .filter { it.id != id }
                .isPresent) {
            throw IllegalArgumentException("Já existe outro tipo com esse nome")
        }

        tipo.nome = novoNome
        return tipoRepo.save(tipo)
    }

    @Transactional
    override fun editarMarca(id: Long, req: MarcaRequest): MarcaEntity {

        val marca = marcaRepo.findById(id)
            .orElseThrow { IllegalArgumentException("Marca não encontrada") }

        val novoNome = req.nome.trim()
        require(novoNome.isNotBlank()) { "Nome inválido" }

        if (marcaRepo
                .findByNomeIgnoreCaseAndTipoId(novoNome, marca.tipo.id!!)
                .filter { it.id != id }
                .isPresent) {
            throw IllegalArgumentException("Já existe outra marca com esse nome nesse tipo")
        }

        marca.nome = novoNome
        return marcaRepo.save(marca)
    }

    @Transactional
    override fun editarModelo(id: Long, req: ModeloRequest): ModeloEntity {

        val modelo = modeloRepo.findById(id)
            .orElseThrow { IllegalArgumentException("Modelo não encontrado") }

        val novoNome = req.nome.trim()
        require(novoNome.isNotBlank()) { "Nome inválido" }

        if (modeloRepo
                .findByNomeIgnoreCaseAndMarcaId(novoNome, modelo.marca.id!!)
                .filter { it.id != id }
                .isPresent) {
            throw IllegalArgumentException("Já existe outro modelo com esse nome nessa marca")
        }

        modelo.nome = novoNome
        return modeloRepo.save(modelo)
    }

    /* =====================================================
       EXCLUSÃO SEGURA
    ====================================================== */

    @Transactional
    override fun excluirTipo(id: Long) {

        if (marcaRepo.existsByTipoId(id)) {
            throw IllegalStateException("Não é possível excluir tipo com marcas vinculadas")
        }

        tipoRepo.deleteById(id)
    }

    @Transactional
    override fun excluirMarca(id: Long) {

        if (modeloRepo.existsByMarcaId(id)) {
            throw IllegalStateException("Não é possível excluir marca com modelos vinculados")
        }

        marcaRepo.deleteById(id)
    }

    @Transactional
    override fun excluirModelo(id: Long) {
        modeloRepo.deleteById(id)
    }

    /* =====================================================
       GRÁFICO
    ====================================================== */

    override fun graficoModelosPorTipo(): Map<String, Long> {

        return tipoRepo.findAll()
            .associate { tipo ->
                tipo.nome to modeloRepo.countByMarcaTipoId(tipo.id!!)
            }
    }
}
