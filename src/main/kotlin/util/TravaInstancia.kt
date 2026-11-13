package org.example.util

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Uma cópia só do servidor por pasta (1.33.0). Em 13/11/2026 duas cópias subiram com 33 s de diferença: o
 * iniciar-servidor.bat confere a porta 8443, mas ela só abre no fim da subida, e por mais de um minuto as duas
 * rodaram as rotinas de início (conferência das migrações, backup antes da migração, tarefas agendadas) ao mesmo tempo.
 *
 * A trava é tomada no primeiro instante do main, antes do Spring: a segunda cópia desiste na hora, com a mensagem.
 * O Windows solta a trava sozinho quando o processo acaba (até se for fechado à força), então não fica trava "presa".
 * O arquivo guarda o PID e a hora da cópia que está rodando, para a mensagem; a trava fica num byte bem depois do
 * conteúdo, para o texto continuar legível pela outra cópia.
 */
object TravaInstancia {

    /** Mantidos abertos enquanto o processo viver: fechar o canal soltaria a trava. */
    @Volatile private var canal: FileChannel? = null
    @Volatile private var trava: FileLock? = null

    private const val POSICAO = Long.MAX_VALUE - 1
    private val FORMATO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

    sealed interface Resultado {
        data object Adquirida : Resultado
        /** Outra cópia está rodando; [quem] é o que ela gravou (PID e hora), se deu para ler. */
        data class Ocupada(val quem: String?) : Resultado
        /** Não deu para criar ou abrir o arquivo da trava: o servidor sobe sem ela (melhor do que não subir). */
        data class SemTrava(val motivo: String) : Resultado
    }

    fun adquirir(arquivo: Path): Resultado {
        val c = try {
            arquivo.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            FileChannel.open(arquivo, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
        } catch (e: IOException) {
            return Resultado.SemTrava("${arquivo.toAbsolutePath()}: ${e.message}")
        }
        val l = try {
            c.tryLock(POSICAO, 1, false)
        } catch (e: OverlappingFileLockException) {
            null
        } catch (e: IOException) {
            c.close()
            return Resultado.SemTrava("${arquivo.toAbsolutePath()}: ${e.message}")
        }
        if (l == null) {
            val quem = runCatching { Files.readString(arquivo, StandardCharsets.UTF_8).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            c.close()
            return Resultado.Ocupada(quem)
        }
        // Quem está rodando agora, para a mensagem da próxima cópia
        runCatching {
            val texto = "PID ${ProcessHandle.current().pid()}, iniciado em ${LocalDateTime.now().format(FORMATO)}"
            c.truncate(0)
            c.write(ByteBuffer.wrap(texto.toByteArray(StandardCharsets.UTF_8)), 0)
            c.force(true)
        }
        canal = c
        trava = l
        return Resultado.Adquirida
    }

    /** Para os testes: solta a trava deste processo. */
    fun soltar() {
        runCatching { trava?.release() }
        runCatching { canal?.close() }
        trava = null
        canal = null
    }
}
