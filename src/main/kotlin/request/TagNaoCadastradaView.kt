package org.example.request

import java.time.LocalDateTime

/** Linha da lista de etiquetas pendentes (lidas pelos coletores e ainda não cadastradas). */
data class TagNaoCadastradaView(
    val tag: String,
    val dataHora: LocalDateTime
)
