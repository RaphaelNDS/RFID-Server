package org.example.service

import jakarta.transaction.Transactional
import org.example.model.TagEntity
import org.example.model.TagNaoCadastradaEntity
import org.example.repository.*
import org.example.request.CadastroRequest
import org.example.request.TagNaoCadastradaView
import org.example.response.TagResponse
import org.springframework.stereotype.Service
import java.security.MessageDigest
import org.example.response.TagViewAdmin
import java.time.LocalDateTime

@Service
class RfidService(
    private val repo: TagRepository,
    private val naoRepo: TagNaoCadastradaRepository,
    private val tipoRepo: TipoEquipamentoRepository,
    private val marcaRepo: MarcaRepository,
    private val modeloRepo: ModeloRepository
) {

    /* ================= CADASTRO NORMAL ================= */

    @Transactional
    fun cadastrarTag(req: CadastroRequest) {

        val hash = hashTag(req.tag)

        val entity = TagEntity(
            tagHash = hash,
            tagReal = req.tag,
            codigoInterno = req.codigoInterno,
            patrimonio = req.patrimonio,
            numeroSerie = req.numeroSerie,

            tipoEquipamentoId = req.tipoEquipamentoId,
            marcaId = req.marcaId,
            modeloId = req.modeloId
        )

        repo.save(entity)

        naoRepo.deleteByTag(req.tag)
    }

    fun buscarPorTag(tagReal: String): TagResponse? {

        val hash = hashTag(tagReal)
        val tag = repo.findByTagHash(hash) ?: return null

        val tipoNome = tag.tipoEquipamentoId
            ?.let { tipoRepo.findById(it).orElse(null) }
            ?.nome

        val marcaNome = tag.marcaId
            ?.let { marcaRepo.findById(it).orElse(null) }
            ?.nome

        val modeloNome = tag.modeloId
            ?.let { modeloRepo.findById(it).orElse(null) }
            ?.nome

        return TagResponse(
            codigoInterno = tag.codigoInterno,
            tipo = tipoNome,
            marca = marcaNome,
            modelo = modeloNome,
            patrimonio = tag.patrimonio,
            numeroSerie = tag.numeroSerie
        )
    }


    fun listarParaAdmin(): List<TagViewAdmin> =
        repo.findAll().map { tag ->

            val tipoNome = tag.tipoEquipamentoId
                ?.let { tipoRepo.findById(it).orElse(null) }
                ?.nome

            val marcaNome = tag.marcaId
                ?.let { marcaRepo.findById(it).orElse(null) }
                ?.nome

            val modeloNome = tag.modeloId
                ?.let { modeloRepo.findById(it).orElse(null) }
                ?.nome

            TagViewAdmin(
                tag = tag.tagReal,
                codigoInterno = tag.codigoInterno,
                tipoNome = tipoNome,
                marcaNome = marcaNome,
                modeloNome = modeloNome,
                patrimonio = tag.patrimonio,
                numeroSerie = tag.numeroSerie
            )
        }



    /* ================= NÃO CADASTRADAS ================= */

    fun salvarNaoCadastrada(tag: String) {

        if (repo.existsByTagReal(tag)) return
        if (naoRepo.existsByTag(tag)) return

        naoRepo.save(
            TagNaoCadastradaEntity(
                tag = tag,
                dataHora = LocalDateTime.now()
            )
        )
    }

    fun listarNaoCadastradas(): List<TagNaoCadastradaView> =
        naoRepo.findAll()
            .sortedByDescending { it.dataHora }
            .map {
                TagNaoCadastradaView(
                    tag = it.tag,
                    dataHora = it.dataHora
                )
            }

    /* ================= UTIL ================= */

    private fun hashTag(tag: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(tag.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}


