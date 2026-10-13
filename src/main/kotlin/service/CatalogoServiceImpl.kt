package org.example.service

import org.springframework.transaction.annotation.Transactional
import org.example.model.MarcaEntity
import org.example.model.ModeloEntity
import org.example.model.TipoEquipamentoEntity
import org.example.repository.MarcaRepository
import org.example.repository.ModeloRepository
import org.example.repository.TagRepository
import org.example.repository.TipoEquipamentoRepository
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.springframework.stereotype.Service
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder

@Service
class CatalogoServiceImpl(
    private val tipoRepo: TipoEquipamentoRepository,
    private val marcaRepo: MarcaRepository,
    private val modeloRepo: ModeloRepository,
    private val tagRepo: TagRepository
) : CatalogoService {

    private val log = LoggerFactory.getLogger(javaClass)

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
        ).also { log.info("Tipo de equipamento cadastrado: '{}' (id {}) por {}", it.nome, it.id, usuarioAtual()) }
    }

    /* =====================================================
       SALVAR MARCA
    ====================================================== */

    @Transactional
    override fun salvarMarca(req: MarcaRequest): MarcaEntity {

        val nome = req.nome.trim()
        require(nome.isNotBlank()) { "Nome da marca não pode ser vazio" }

        // Sem o tipo, o "!!" antigo estourava NullPointerException (erro 500)
        val tipoId = req.tipoId ?: run {
            log.warn("Cadastro da marca '{}' recusado: tipo de equipamento não informado (por {})", nome, usuarioAtual())
            throw IllegalArgumentException("Selecione o tipo de equipamento da marca")
        }

        val tipo = tipoRepo.findById(tipoId).orElseThrow {
            log.warn("Cadastro da marca '{}' recusado: tipo de equipamento {} não existe (por {})", nome, tipoId, usuarioAtual())
            IllegalArgumentException("Tipo de equipamento não encontrado")
        }

        if (marcaRepo.findByNomeIgnoreCaseAndTipoId(nome, tipo.id!!).isPresent) {
            throw IllegalArgumentException("Marca já cadastrada para esse tipo!")
        }

        return marcaRepo.save(
            MarcaEntity(
                nome = nome,
                tipo = tipo
            )
        ).also { log.info("Marca cadastrada: '{}' no tipo '{}' (id {}) por {}", it.nome, it.tipo.nome, it.id, usuarioAtual()) }
    }

    /* =====================================================
       SALVAR MODELO
    ====================================================== */

    @Transactional
    override fun salvarModelo(req: ModeloRequest): ModeloEntity {

        val nome = req.nome.trim()
        require(nome.isNotBlank()) { "Nome do modelo não pode ser vazio" }

        // Sem a marca, o "!!" antigo estourava NullPointerException (erro 500)
        val marcaId = req.marcaId ?: run {
            log.warn("Cadastro do modelo '{}' recusado: marca não informada (por {})", nome, usuarioAtual())
            throw IllegalArgumentException("Selecione a marca do modelo")
        }

        val marca = marcaRepo.findById(marcaId).orElseThrow {
            log.warn("Cadastro do modelo '{}' recusado: marca {} não existe (por {})", nome, marcaId, usuarioAtual())
            IllegalArgumentException("Marca não encontrada")
        }

        if (modeloRepo.findByNomeIgnoreCaseAndMarcaId(nome, marca.id!!).isPresent) {
            throw IllegalArgumentException("Modelo já cadastrado para essa marca!")
        }

        return modeloRepo.save(
            ModeloEntity(
                nome = nome,
                marca = marca
            )
        ).also { log.info("Modelo cadastrado: '{}' na marca '{}' (id {}) por {}", it.nome, it.marca.nome, it.id, usuarioAtual()) }
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
            .also { log.info("Tipo de equipamento editado: id {} agora se chama '{}' (por {})", it.id, it.nome, usuarioAtual()) }
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
            .also { log.info("Marca editada: id {} agora se chama '{}' (por {})", it.id, it.nome, usuarioAtual()) }
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
            .also { log.info("Modelo editado: id {} agora se chama '{}' (por {})", it.id, it.nome, usuarioAtual()) }
    }

    /* =====================================================
       EXCLUSÃO SEGURA
    ====================================================== */

    // Além destas verificações (que dão a mensagem amigável), o banco tem chaves estrangeiras
    // (migração V2) que impedem a exclusão de item em uso mesmo se alguém pular o service.

    @Transactional
    override fun excluirTipo(id: Long) {

        val nome = tipoRepo.findById(id).orElse(null)?.nome

        if (marcaRepo.existsByTipoId(id)) {
            throw IllegalStateException("Não é possível excluir tipo com marcas vinculadas")
        }
        recusarSeEmUso("este tipo", nome, id, tagRepo.countByTipoEquipamentoId(id))

        tipoRepo.deleteById(id)
        log.info("Tipo de equipamento excluído: '{}' (id {}) por {}", nome, id, usuarioAtual())
    }

    @Transactional
    override fun excluirMarca(id: Long) {

        val nome = marcaRepo.findById(id).orElse(null)?.nome

        if (modeloRepo.existsByMarcaId(id)) {
            throw IllegalStateException("Não é possível excluir marca com modelos vinculados")
        }
        recusarSeEmUso("esta marca", nome, id, tagRepo.countByMarcaId(id))

        marcaRepo.deleteById(id)
        log.info("Marca excluída: '{}' (id {}) por {}", nome, id, usuarioAtual())
    }

    @Transactional
    override fun excluirModelo(id: Long) {

        val nome = modeloRepo.findById(id).orElse(null)?.nome

        // Antes não havia verificação nenhuma: as etiquetas ficavam apontando para um modelo inexistente
        recusarSeEmUso("este modelo", nome, id, tagRepo.countByModeloId(id))

        modeloRepo.deleteById(id)
        log.info("Modelo excluído: '{}' (id {}) por {}", nome, id, usuarioAtual())
    }

    // item com artigo, ex.: "esta marca"
    private fun recusarSeEmUso(item: String, nome: String?, id: Long, etiquetas: Long) {
        if (etiquetas == 0L) return

        log.warn(
            "Exclusão recusada: {} '{}' (id {}) está em uso por {} etiqueta(s) (tentativa de {})",
            item.substringAfter(" "), nome, id, etiquetas, usuarioAtual()
        )
        throw IllegalStateException(
            "Não é possível excluir: $etiquetas etiqueta(s) usam $item. " +
                "Altere ou exclua essas etiquetas antes."
        )
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

    private fun usuarioAtual(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "sistema"
}
