package org.example.config

import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Backup do banco antes de aplicar migrações (versão nova do servidor que muda as tabelas).
 *
 * Roda um comando externo; o padrão pede um pg_dump ao contêiner de backup do docker-compose
 * (`docker exec rfid-backup /backup.sh`, que grava em docker/backups/last como os backups de 6 em 6 horas).
 * Comando vazio = desligado.
 *
 * Se o comando falhar (Docker parado, contêiner sem acesso ao banco, demora além de [limite]), lança
 * [IllegalStateException]: a migração NÃO é aplicada e o servidor não sobe, porque migrar sem backup é
 * justamente o que se quer evitar. A mensagem diz como resolver ou como migrar sem backup.
 */
class BackupAntesMigracao(
    comando: String,
    private val limite: Duration = Duration.ofMinutes(5)
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** O comando separado em partes (sem aspas: o padrão e os usos previstos não têm espaço nos argumentos). */
    val partes: List<String> = comando.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    val ligado: Boolean get() = partes.isNotEmpty()

    fun executar() {
        if (!ligado) {
            log.warn("Backup antes da migração desligado (rfid.banco.backup-antes-migracao vazio): migrando sem backup")
            return
        }

        val descricao = partes.joinToString(" ")
        log.info("Fazendo backup do banco antes da migração: {}", descricao)
        val inicio = System.currentTimeMillis()

        val processo = try {
            ProcessBuilder(partes).redirectErrorStream(true).start()
        } catch (e: Exception) {
            falhar(descricao, "não foi possível executar o comando (${e.message})")
        }

        // Lê a saída em outra thread: sem isso, saída grande enche o buffer e o processo trava
        val saida = StringBuilder()
        val leitor = Thread { processo.inputStream.bufferedReader().forEachLine { synchronized(saida) { saida.appendLine(it) } } }
            .apply { isDaemon = true; start() }

        if (!processo.waitFor(limite.toMillis(), TimeUnit.MILLISECONDS)) {
            processo.destroyForcibly()
            falhar(descricao, "passou de ${limite.toMinutes()} min sem terminar")
        }
        leitor.join(2000)

        val ultimas = synchronized(saida) { saida.lines().filter { it.isNotBlank() }.takeLast(5).joinToString(" | ") }
        if (processo.exitValue() != 0) {
            falhar(descricao, "terminou com código ${processo.exitValue()}${if (ultimas.isNotEmpty()) ": $ultimas" else ""}")
        }

        log.info("Backup do banco antes da migração concluído em {} ms", System.currentTimeMillis() - inicio)
        if (ultimas.isNotEmpty()) log.debug("Saída do backup: {}", ultimas)
    }

    private fun falhar(descricao: String, motivo: String): Nothing {
        val mensagem = "Backup do banco antes da migração falhou ($descricao: $motivo). A migração NÃO foi aplicada " +
            "e o servidor não vai subir. Confira se o Docker Desktop está aberto e o contêiner rfid-backup está no ar " +
            "(docker ps) e inicie de novo. Para migrar sem backup, defina RFID_BACKUP_ANTES_MIGRACAO vazio."
        log.error(mensagem)
        throw IllegalStateException(mensagem)
    }
}
