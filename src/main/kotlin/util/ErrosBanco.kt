package org.example.util

import java.sql.SQLException

/** SQLState do PostgreSQL para "duplicate key value violates unique constraint". */
const val SQLSTATE_UNICIDADE = "23505"

/**
 * O erro (ou alguma causa dele) é violação de índice único (SQLState 23505)? Serve para tratar como "repetida" só
 * o reenvio de verdade: um DataIntegrityViolationException também vem de NOT NULL, chave estrangeira, CHECK ou texto
 * maior que a coluna, e esses antes eram engolidos como "repetida" (a leitura sumia sem nenhum aviso).
 */
fun violacaoDeUnicidade(erro: Throwable): Boolean =
    generateSequence(erro) { atual -> atual.cause?.takeIf { it !== atual } }
        .take(20) // proteção contra cadeia de causas circular
        .any { (it as? SQLException)?.sqlState == SQLSTATE_UNICIDADE }
