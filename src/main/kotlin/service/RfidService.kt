package org.example.service

import jakarta.transaction.Transactional
import org.example.model.TagEntity
import org.example.model.TagNaoCadastradaEntity
import org.example.repository.TagNaoCadastradaRepository
import org.example.repository.TagRepository
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
    private val naoRepo: TagNaoCadastradaRepository
) {

    /* ================= CADASTRO NORMAL ================= */

    @Transactional
    fun cadastrarTag(req: CadastroRequest) {

        val hash = hashTag(req.tag)

        val entity = TagEntity(
            tagHash = hash,
            tagReal = req.tag,
            codigoInterno = req.codigoInterno,
            modelo = req.modelo,
            patrimonio = req.patrimonio,
            numeroSerie = req.numeroSerie
        )

        repo.save(entity)

        naoRepo.deleteByTag(req.tag)
    }

    fun buscarPorTag(tagReal: String): TagResponse? {

        val hash = hashTag(tagReal)
        val tag = repo.findByTagHash(hash) ?: return null

        return TagResponse(
            tag.codigoInterno,
            modelo = tag.modelo,
            patrimonio = tag.patrimonio,
            numeroSerie = tag.numeroSerie
        )
    }

    fun listarParaAdmin(): List<TagViewAdmin> =
        repo.findAll().map {
            TagViewAdmin(
                tag = it.tagReal,
                codigoInterno = it.codigoInterno,
                modelo = it.modelo,
                patrimonio = it.patrimonio,
                numeroSerie = it.numeroSerie
            )
        }

    /* ================= NÃO CADASTRADAS ================= */

    fun salvarNaoCadastrada(tag: String) {

        // já cadastrada → ignora
        if (repo.existsByTagReal(tag)) return

        // já está na fila → ignora
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


