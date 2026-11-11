package org.example.response

import org.example.model.SituacaoBem
import java.math.BigDecimal
import java.time.LocalDate

data class TagViewAdmin(
    val id: Long,
    val tag: String,
    val codigoInterno: String,
    val tipoNome: String?,
    val marcaNome: String?,
    val modeloNome: String?,
    val patrimonio: String,
    val numeroSerie: String,
    // Gestão de patrimônio (1.6.0)
    val unidadeNome: String? = null,
    val localNome: String? = null,
    val responsavelNome: String? = null,
    val situacao: SituacaoBem = SituacaoBem.DISPONIVEL,
    // Valor (1.8.0)
    val valorAquisicao: BigDecimal? = null,
    val dataAquisicao: LocalDate? = null,
    val notaFiscal: String? = null
) {
    /** "Notebook Dell Latitude" (o que houver do catálogo) ou null. */
    val descricao: String? get() = listOfNotNull(tipoNome, marcaNome, modeloNome).joinToString(" ").ifBlank { null }

    /** "Matriz › Sala 101", "Sala 101" ou null. */
    val localDescricao: String? get() = localNome?.let { l -> unidadeNome?.let { "$it › $l" } ?: l }
}
