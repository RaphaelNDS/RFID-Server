package org.example.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Regras mínimas de senha, aplicadas no setup, no cadastro de usuário e na troca de senha.
 * A senha nunca é registrada no log: só o motivo da recusa.
 */
@Component
class PoliticaSenha(
    @Value("\${rfid.senha.tamanho-minimo:8}") private val tamanhoMinimo: Int
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Texto curto das regras, para mostrar nos formulários. */
    val descricao: String
        get() = "Mínimo de $tamanhoMinimo caracteres, com letras e números. Não use seu nome, seu email nem senhas comuns."

    /** Lista os problemas da senha (vazia = senha aceita). */
    fun problemas(senha: String, email: String? = null, nome: String? = null): List<String> {
        val problemas = mutableListOf<String>()
        val minuscula = senha.lowercase()

        if (senha.length < tamanhoMinimo) {
            problemas += "a senha deve ter pelo menos $tamanhoMinimo caracteres"
        }

        // O BCrypt ignora tudo depois de 72 bytes: senha maior seria cortada sem aviso
        if (senha.toByteArray(Charsets.UTF_8).size > TAMANHO_MAXIMO_BYTES) {
            problemas += "a senha pode ter no máximo $TAMANHO_MAXIMO_BYTES caracteres"
        }

        if (senha.none { it.isLetter() } || senha.none { it.isDigit() }) {
            problemas += "a senha deve ter letras e números"
        }

        val usuarioDoEmail = email?.substringBefore('@')?.trim()?.lowercase()
        if (usuarioDoEmail != null && usuarioDoEmail.length >= 3 && minuscula.contains(usuarioDoEmail)) {
            problemas += "a senha não pode conter o seu email"
        }

        val partesDoNome = nome?.lowercase()?.split(' ')?.filter { it.length >= 3 }.orEmpty()
        if (partesDoNome.any { minuscula.contains(it) }) {
            problemas += "a senha não pode conter o seu nome"
        }

        if (minuscula in SENHAS_COMUNS || senha.toSet().size <= 2) {
            problemas += "essa senha é muito comum e fácil de adivinhar"
        }

        return problemas
    }

    /** Lança IllegalArgumentException com todos os motivos (vira mensagem na tela) se a senha não servir. */
    fun exigir(senha: String, email: String? = null, nome: String? = null, contexto: String) {
        val problemas = problemas(senha, email, nome)
        if (problemas.isEmpty()) return

        log.warn("Senha recusada ({}) para '{}': {}", contexto, email ?: "?", problemas.joinToString("; "))
        throw IllegalArgumentException("Senha fraca: " + problemas.joinToString("; ") + ".")
    }

    companion object {
        const val TAMANHO_MAXIMO_BYTES = 72

        // Senhas mais usadas no Brasil e no mundo que passariam pelas outras regras
        private val SENHAS_COMUNS = setOf(
            "12345678a", "a12345678", "abc12345", "abcd1234", "abc123456", "qwerty123", "qwerty12", "1q2w3e4r",
            "1q2w3e4r5t", "1qaz2wsx", "password1", "password123", "passw0rd", "senha123", "senha1234", "senha12345",
            "mudar123", "mudar1234", "trocar123", "admin123", "admin1234", "administrador1", "brasil123", "teste123",
            "teste1234", "usuario123", "iloveyou1", "welcome1", "letmein1", "rfid1234", "rfid12345"
        )
    }
}
