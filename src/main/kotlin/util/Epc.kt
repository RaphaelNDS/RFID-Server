package org.example.util

/**
 * Forma padrão da etiqueta (EPC): sem espaços nas pontas e em MAIÚSCULAS.
 *
 * O PostgreSQL diferencia maiúsculas: "e2801170..." colado de uma planilha não batia com "E2801170..." lido pelo
 * coletor (a leitura ia para pendentes e o coletor cadastrava a mesma etiqueta de novo). Toda entrada de EPC (web,
 * coletor, lote, inventário, busca exata) passa por aqui; os dados antigos foram padronizados na V18.
 */
fun normalizarEpc(epc: String): String = epc.trim().uppercase()
