package org.example.service

import org.example.util.CifraBackup
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Situação da cópia do banco fora do computador (tela Status e avisos). */
data class SituacaoCopiaExterna(
    /** Pasta configurada (rfid.banco.copia-externa). */
    val ligada: Boolean,
    /** Hora do dump da cópia mais recente na pasta externa (null = nenhuma). */
    val momentoUltimaCopia: LocalDateTime?,
    /** Erro da última tentativa (null = deu certo ou ainda não tentou). */
    val erro: String?,
    /** Sem cópia nova há mais que o limite (ou nenhuma), com a cópia ligada. */
    val atrasada: Boolean,
    val resumo: String
) {
    val comProblema: Boolean get() = ligada && (erro != null || atrasada)
}

/**
 * Cópia do backup do banco fora do computador (1.5.1). O contêiner rfid-backup grava os dumps no mesmo disco do banco:
 * se o disco do notebook falhar, perde-se o banco e os backups juntos (e a cópia manual, backup-fora-do-computador,
 * depende de alguém lembrar). De hora em hora, o último dump inteiro (BackupBancoService, já conferido pelo pg_restore)
 * é cifrado com a senha do backup (rfid.backup.senha, a mesma do certificado) e gravado na pasta externa, por exemplo
 * a do OneDrive da empresa, que sobe sozinha para a nuvem. Guarda os [manter] mais recentes.
 *
 * Sem senha, NÃO copia: o dump leva todos os dados e os hashes das senhas dos usuários e não pode ir aberto para a
 * nuvem. Para restaurar: java -jar rfid-server.jar --decifrar-backup rfid-AAAAMMDD-HHMMSS.dump.cifrado
 */
@Service
class CopiaExternaBancoService(
    @Value("\${rfid.banco.copia-externa:}") pastaConfigurada: String,
    @Value("\${rfid.banco.copia-externa-manter:14}") private val manter: Int,
    @Value("\${rfid.banco.copia-externa-atrasada-horas:24}") private val horasAtraso: Long,
    @Value("\${rfid.backup.senha:}") private val senha: String,
    private val backupBanco: BackupBancoService,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    val pasta: Path? = pastaConfigurada.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it).toAbsolutePath().normalize() }

    @Volatile private var ultimoErro: String? = null

    /** De hora em hora (o contêiner faz um dump de 6 em 6 horas): copia o último dump inteiro se ainda não estiver lá. */
    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
    fun copiarAgendado() {
        runCatching { copiar() }.onFailure { log.error("Cópia do backup do banco para fora do computador falhou", it) }
    }

    /** Devolve o arquivo copiado agora, ou null (desligada, já copiado, sem dump ou com erro: ver [situacao]). */
    @Synchronized
    fun copiar(): Path? {
        val destino = pasta ?: return null
        if (senha.isBlank()) {
            return falhar("sem a senha do backup (rfid.backup.senha / RFID_BACKUP_SENHA): o banco não vai aberto para fora do computador")
        }
        val dump = backupBanco.situacao().ultimoBom ?: return falhar("nenhum backup do banco inteiro para copiar")

        val arquivo = destino.resolve(dump.name + EXTENSAO)
        if (Files.isRegularFile(arquivo)) {
            ultimoErro = null
            return null   // já copiado
        }
        return try {
            Files.createDirectories(destino)
            val parcial = destino.resolve(arquivo.name + ".parcial")
            CifraBackup.cifrarArquivo(dump, parcial, senha)
            try {
                Files.move(parcial, arquivo, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(parcial, arquivo, StandardCopyOption.REPLACE_EXISTING)
            }
            log.info("Cópia cifrada do backup do banco gravada fora do computador: {} ({} KB)", arquivo, arquivo.fileSize() / 1024)
            limpar(destino)
            ultimoErro = null
            arquivo
        } catch (e: Exception) {
            falhar("não foi possível gravar em $destino (${e.message})")
        }
    }

    fun situacao(): SituacaoCopiaExterna {
        val destino = pasta ?: return SituacaoCopiaExterna(false, null, null, false,
            "desligada: defina RFID_BANCO_COPIA_EXTERNA (ex.: a pasta do OneDrive) para guardar o banco fora do computador")
        val agora = LocalDateTime.now(relogio)
        val ultima = copias(destino).firstOrNull()
        val momento = ultima?.let { PADRAO.find(it.name)?.groupValues?.get(1)?.let { t -> LocalDateTime.parse(t, FORMATO_NOME) } }
        val atrasada = momento == null || Duration.between(momento, agora).toHours() >= horasAtraso
        val erro = ultimoErro
        val resumo = buildString {
            append(if (momento == null) "nenhuma cópia em $destino" else "${momento.format(FORMATO_TELA)} (${idade(momento, agora)}), em $destino")
            if (atrasada && momento != null) append(" — ATRASADA: mais de $horasAtraso h sem cópia nova")
            if (erro != null) append(" — a última tentativa falhou: $erro")
        }
        return SituacaoCopiaExterna(true, momento, erro, atrasada, resumo)
    }

    private fun falhar(motivo: String): Path? {
        if (ultimoErro != motivo) log.warn("Cópia do backup do banco para fora do computador não foi feita: {}", motivo)
        ultimoErro = motivo
        return null
    }

    /** As cópias da pasta, da mais nova para a mais antiga (pelo nome, que tem a data do dump). */
    private fun copias(destino: Path): List<Path> =
        if (!Files.isDirectory(destino)) emptyList()
        else Files.list(destino).use { a -> a.filter { it.isRegularFile() && PADRAO.matches(it.name) }.toList() }.sortedByDescending { it.name }

    /** Guarda as [manter] mais recentes e apaga as .parcial esquecidas (computador desligado no meio da cópia). */
    private fun limpar(destino: Path) {
        copias(destino).drop(manter.coerceAtLeast(1)).forEach {
            runCatching { Files.delete(it) }.onSuccess { _ -> log.info("Cópia antiga do backup do banco apagada da pasta externa: {}", it.name) }
        }
        Files.list(destino).use { a -> a.filter { it.name.endsWith("$EXTENSAO.parcial") }.toList() }
            .filter { Duration.between(Files.getLastModifiedTime(it).toInstant(), relogio.instant()).toHours() >= 1 }
            .forEach { runCatching { Files.delete(it) } }
    }

    private fun idade(momento: LocalDateTime, agora: LocalDateTime): String {
        val d = Duration.between(momento, agora)
        return when {
            d.toDays() > 0 -> "há ${d.toDays()}d ${d.toHoursPart()}h"
            d.toHours() > 0 -> "há ${d.toHours()}h"
            else -> "há ${d.toMinutes()}min"
        }
    }

    private companion object {
        const val EXTENSAO = ".cifrado"
        val PADRAO = Regex("""^.+-(\d{8}-\d{6})\.dump\.cifrado$""")
        val FORMATO_NOME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val FORMATO_TELA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    }
}
