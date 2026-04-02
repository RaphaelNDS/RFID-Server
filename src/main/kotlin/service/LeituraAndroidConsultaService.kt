package org.example.service

import org.example.model.LeituraAndroidEntity
import org.example.repository.LeituraAndroidRepository
import org.example.util.LeituraAndroidSpec
import org.springframework.stereotype.Service

@Service
class LeituraAndroidConsultaService(
    private val repo: LeituraAndroidRepository
) {

    fun listarComFiltro(
        tag: String?,
        tipo: String?,
        marca: String?,
        modelo: String?,
        numeroSerie: String?
    ): List<LeituraAndroidEntity> {

        val spec = LeituraAndroidSpec.filtro(
            tag, tipo, marca, modelo, numeroSerie
        )

        return repo.findAll(spec)
    }

    fun contadorPorDia(): Map<String, Long> =
        repo.contarPorDia().associate {
            it[0].toString() to (it[1] as Long)
        }
}

