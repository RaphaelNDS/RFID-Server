package org.example.util

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Criptografia do backup do certificado HTTPS (AES-256-GCM, chave derivada da senha com PBKDF2-SHA256).
 *
 * O .zip leva a chave privada do HTTPS e a senha dela: quem copiasse um backup da pasta de rede conseguia se passar
 * pelo servidor para os coletores. Com rfid.backup.senha (RFID_BACKUP_SENHA), o arquivo sai cifrado e a senha fica
 * só na configuração, fora do backup.
 *
 * Formato: "RFIDBK1" + sal (16 bytes) + IV (12 bytes) + conteúdo cifrado com a etiqueta GCM (que acusa senha errada
 * ou arquivo alterado). Para restaurar: java -jar rfid-server.jar --decifrar-backup arquivo.zip.cifrado
 */
object CifraBackup {

    private val CABECALHO = "RFIDBK1".toByteArray()
    private const val TAMANHO_SAL = 16
    private const val TAMANHO_IV = 12
    private const val ITERACOES = 210_000

    fun cifrar(conteudo: ByteArray, senha: String): ByteArray {
        val aleatorio = SecureRandom()
        val sal = ByteArray(TAMANHO_SAL).also(aleatorio::nextBytes)
        val iv = ByteArray(TAMANHO_IV).also(aleatorio::nextBytes)
        val cifra = Cipher.getInstance("AES/GCM/NoPadding")
        cifra.init(Cipher.ENCRYPT_MODE, chave(senha, sal), GCMParameterSpec(128, iv))
        return CABECALHO + sal + iv + cifra.doFinal(conteudo)
    }

    /** Lança IllegalArgumentException com a senha errada, arquivo alterado ou que não é um backup cifrado. */
    fun decifrar(arquivo: ByteArray, senha: String): ByteArray {
        val inicio = CABECALHO.size
        require(arquivo.size > inicio + TAMANHO_SAL + TAMANHO_IV && arquivo.copyOfRange(0, inicio).contentEquals(CABECALHO)) {
            "Este arquivo não é um backup cifrado do servidor RFID"
        }
        val sal = arquivo.copyOfRange(inicio, inicio + TAMANHO_SAL)
        val iv = arquivo.copyOfRange(inicio + TAMANHO_SAL, inicio + TAMANHO_SAL + TAMANHO_IV)
        val cifra = Cipher.getInstance("AES/GCM/NoPadding")
        cifra.init(Cipher.DECRYPT_MODE, chave(senha, sal), GCMParameterSpec(128, iv))
        return try {
            cifra.doFinal(arquivo, inicio + TAMANHO_SAL + TAMANHO_IV, arquivo.size - inicio - TAMANHO_SAL - TAMANHO_IV)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw IllegalArgumentException("Senha do backup errada (ou o arquivo foi alterado)")
        }
    }

    private fun chave(senha: String, sal: ByteArray): SecretKeySpec {
        val derivada = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(senha.toCharArray(), sal, ITERACOES, 256)).encoded
        return SecretKeySpec(derivada, "AES")
    }
}
