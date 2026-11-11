package org.example.util

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
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

    /**
     * O mesmo formato de [cifrar], arquivo para arquivo, lendo e gravando aos poucos (1.5.1): o dump do banco cresce
     * com as leituras e não pode ser carregado inteiro na memória como o .zip do certificado (poucos KB).
     */
    fun cifrarArquivo(origem: Path, destino: Path, senha: String) {
        val aleatorio = SecureRandom()
        val sal = ByteArray(TAMANHO_SAL).also(aleatorio::nextBytes)
        val iv = ByteArray(TAMANHO_IV).also(aleatorio::nextBytes)
        val cifra = Cipher.getInstance("AES/GCM/NoPadding")
        cifra.init(Cipher.ENCRYPT_MODE, chave(senha, sal), GCMParameterSpec(128, iv))
        Files.newOutputStream(destino).buffered().use { saida ->
            saida.write(CABECALHO + sal + iv)
            javax.crypto.CipherOutputStream(saida, cifra).use { cifrado ->
                Files.newInputStream(origem).use { it.copyTo(cifrado, 64 * 1024) }
            }
        }
    }

    /**
     * Decifra [origem] em [destino] (para o --decifrar-backup). Lança IllegalArgumentException como [decifrar]; com
     * erro, o [destino] incompleto é apagado. Observação: o AES-GCM do Java só libera o conteúdo depois de conferir a
     * etiqueta do fim, então guarda o arquivo cifrado na memória durante a decifração.
     */
    fun decifrarArquivo(origem: Path, destino: Path, senha: String) {
        Files.newInputStream(origem).buffered().use { entrada ->
            val cabecalho = entrada.readNBytes(CABECALHO.size)
            val sal = entrada.readNBytes(TAMANHO_SAL)
            val iv = entrada.readNBytes(TAMANHO_IV)
            require(cabecalho.contentEquals(CABECALHO) && sal.size == TAMANHO_SAL && iv.size == TAMANHO_IV) {
                "Este arquivo não é um backup cifrado do servidor RFID"
            }
            val cifra = Cipher.getInstance("AES/GCM/NoPadding")
            cifra.init(Cipher.DECRYPT_MODE, chave(senha, sal), GCMParameterSpec(128, iv))
            try {
                Files.newOutputStream(destino).use { saida ->
                    javax.crypto.CipherInputStream(entrada, cifra).use { it.copyTo(saida, 64 * 1024) }
                }
            } catch (e: IOException) {
                Files.deleteIfExists(destino)
                // O CipherInputStream embrulha a etiqueta GCM errada (senha errada ou arquivo alterado) numa IOException
                if (generateSequence(e as Throwable) { it.cause }.any { it is javax.crypto.AEADBadTagException }) {
                    throw IllegalArgumentException("Senha do backup errada (ou o arquivo foi alterado)")
                }
                throw e
            }
        }
    }

    private fun chave(senha: String, sal: ByteArray): SecretKeySpec {
        val derivada = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(senha.toCharArray(), sal, ITERACOES, 256)).encoded
        return SecretKeySpec(derivada, "AES")
    }
}
