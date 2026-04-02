package org.example.util

import org.example.model.LeituraAndroidEntity
import org.springframework.data.jpa.domain.Specification

object LeituraAndroidSpec {

    fun filtro(
        tag: String?,
        tipo: String?,
        marca: String?,
        modelo: String?,
        numeroSerie: String?
    ): Specification<LeituraAndroidEntity> {

        return Specification { root, _, cb ->

            val predicates = mutableListOf<jakarta.persistence.criteria.Predicate>()

            tag?.takeIf { it.isNotBlank() }?.let {
                predicates += cb.like(cb.lower(root.get("tag")), "%${it.lowercase()}%")
            }

            tipo?.takeIf { it.isNotBlank() }?.let {
                predicates += cb.equal(root.get<String>("tipo"), it)
            }

            marca?.takeIf { it.isNotBlank() }?.let {
                predicates += cb.equal(root.get<String>("marca"), it)
            }

            modelo?.takeIf { it.isNotBlank() }?.let {
                predicates += cb.equal(root.get<String>("modelo"), it)
            }

            numeroSerie?.takeIf { it.isNotBlank() }?.let {
                predicates += cb.like(
                    cb.lower(root.get("numeroSerie")),
                    "%${it.lowercase()}%"
                )
            }

            cb.and(*predicates.toTypedArray())
        }
    }
}
