package org.example.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Vigia o servidor e os backups e manda os avisos (AlertaService), 1.5.1:
 *
 *  - Fora do ar: a cada 5 min grava a hora num arquivo ([arquivoNoAr]). Ao subir, se a última hora gravada tiver mais
 *    de [limiteFora], o servidor ficou fora do ar (desligado, reiniciado, caiu) e avisa de quando a quando. Com o
 *    servidor no ar, um buraco desses entre duas gravações quer dizer que o computador suspendeu (o notebook dormiu).
 *    Não dá para avisar no momento em que cai (com o servidor fora, nada roda): o aviso sai quando ele volta.
 *  - Backups: de hora em hora confere o backup do banco (atrasado ou incompleto) e a cópia fora do computador.
 */
@Service
class MonitorServidorService(
    @Value("\${rfid.monitor.arquivo-no-ar:./data/servidor-no-ar.txt}") arquivo: String,
    @Value("\${rfid.monitor.minutos-fora:30}") minutosFora: Long,
    private val alertas: AlertaService,
    private val backupBanco: BackupBancoService,
    private val copiaExterna: CopiaExternaBancoService,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Vazio = não vigia o fora do ar (os testes não gravam na pasta do projeto). */
    private val arquivoNoAr: Path? = arquivo.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it).toAbsolutePath().normalize() }
    private val limiteFora: Duration = Duration.ofMinutes(minutosFora.coerceAtLeast(10))

    @EventListener(ApplicationReadyEvent::class)
    fun aoSubir() {
        val arquivo = arquivoNoAr ?: return
        runCatching {
            val anterior = lerUltimaHora(arquivo)
            val agora = LocalDateTime.now(relogio)
            if (anterior != null && Duration.between(anterior, agora) > limiteFora) {
                val fora = Duration.between(anterior, agora)
                alertas.informar("Servidor ficou fora do ar por ${duracao(fora)}",
                    "O servidor RFID ficou fora do ar de ${anterior.format(FORMATO)} até ${agora.format(FORMATO)} (${duracao(fora)}). " +
                        "Nesse tempo os coletores guardaram as leituras na fila e enviaram depois. Causas comuns: computador " +
                        "desligado ou reiniciado e ninguém abriu o iniciar-servidor.bat, ou a janela do servidor fechada.")
            }
            gravar(arquivo, agora)
        }.onFailure { log.warn("Não foi possível conferir se o servidor ficou fora do ar ({}): {}", arquivo, it.message) }
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT5M")
    fun registrarNoAr() {
        val arquivo = arquivoNoAr ?: return
        runCatching {
            val agora = LocalDateTime.now(relogio)
            val anterior = lerUltimaHora(arquivo)
            if (anterior != null && Duration.between(anterior, agora) > limiteFora) {
                val parado = Duration.between(anterior, agora)
                alertas.informar("Servidor ficou parado por ${duracao(parado)}",
                    "O servidor RFID não rodou de ${anterior.format(FORMATO)} até ${agora.format(FORMATO)} (${duracao(parado)}), " +
                        "sem ter sido reiniciado: o computador provavelmente suspendeu (notebook dormindo ou tampa fechada). " +
                        "Confira as opções de energia do Windows.")
            }
            gravar(arquivo, agora)
        }.onFailure { log.debug("Não foi possível gravar {}: {}", arquivo, it.message) }
    }

    /** De hora em hora (15 min depois de subir, dando tempo ao backup do contêiner e à primeira cópia). */
    @Scheduled(initialDelayString = "PT15M", fixedDelayString = "PT1H")
    fun conferirBackups() {
        runCatching {
            val banco = backupBanco.situacao()
            if (banco.pastaEncontrada) {
                if (banco.atrasado || banco.ultimaTentativaFalhou) {
                    alertas.problema(BACKUP_BANCO, "Backup do banco com problema",
                        "Último backup do banco: ${banco.resumo}. Confira se o Docker Desktop está aberto e o contêiner " +
                            "rfid-backup no ar (docker ps; docker logs rfid-backup).")
                } else {
                    alertas.resolvido(BACKUP_BANCO, "Backup do banco", "O backup do banco voltou ao normal: ${banco.resumo}.")
                }
            }
            val copia = copiaExterna.situacao()
            if (copia.comProblema) {
                alertas.problema(COPIA_EXTERNA, "Cópia do banco fora do computador com problema", "Cópia fora do computador: ${copia.resumo}.")
            } else if (copia.ligada) {
                alertas.resolvido(COPIA_EXTERNA, "Cópia do banco fora do computador", "A cópia fora do computador voltou ao normal: ${copia.resumo}.")
            }
        }.onFailure { log.error("Não foi possível conferir os backups para os avisos", it) }
    }

    private fun lerUltimaHora(arquivo: Path): LocalDateTime? =
        if (!Files.isRegularFile(arquivo)) null
        else runCatching { LocalDateTime.parse(Files.readString(arquivo).trim()) }.getOrNull()

    private fun gravar(arquivo: Path, agora: LocalDateTime) {
        arquivo.parent?.let { Files.createDirectories(it) }
        Files.writeString(arquivo, agora.toString())
    }

    companion object {
        const val BACKUP_BANCO = "backup-banco"
        const val COPIA_EXTERNA = "copia-externa"
        private val FORMATO: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

        fun duracao(d: Duration): String = when {
            d.toDays() > 0 -> "${d.toDays()}d ${d.toHoursPart()}h"
            d.toHours() > 0 -> "${d.toHours()}h ${d.toMinutesPart()}min"
            else -> "${d.toMinutes()} min"
        }
    }
}
