package org.example.util

/**
 * Email digitado no login (ou no /setup) na forma usada para o log e para as chaves dos contadores de tentativas.
 *
 * O campo vem de quem nem fez login: sem corte, um email de alguns MB ia inteiro para o log a cada tentativa e
 * virava chave de mapa em memória (TentativasLoginService). Aqui ele é cortado em [TAMANHO_MAXIMO] caracteres,
 * perde os caracteres de controle e invisíveis (quebras de linha, tabulação, zero-width) e fica em minúsculas, como
 * o email gravado no banco (UsuarioService.normalizarEmail).
 */
object EmailLogin {

    /** Tamanho máximo de um email (o mesmo limite usual da coluna e do padrão de email). */
    const val TAMANHO_MAXIMO = 255

    // Controle (\p{Cc}), formatação invisível (\p{Cf}, ex.: zero-width) e separadores de linha/parágrafo do Unicode
    private val INVISIVEIS = Regex("[\\p{Cc}\\p{Cf}\\u2028\\u2029]")

    fun normalizar(email: String?): String {
        if (email == null) return ""
        // Corta antes da regex: não percorre megabytes à toa (a folga cobre os invisíveis que serão tirados)
        return INVISIVEIS.replace(email.take(TAMANHO_MAXIMO * 4), "").trim().take(TAMANHO_MAXIMO).lowercase()
    }

    /** Email maior que o permitido: recusado antes de chegar ao banco ou à conferência da senha. */
    fun longoDemais(email: String?): Boolean = (email?.length ?: 0) > TAMANHO_MAXIMO
}
