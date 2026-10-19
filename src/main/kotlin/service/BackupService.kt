package org.example.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Backup automático do certificado HTTPS do servidor (.p12 e a senha dele, .senha).
 *
 * O banco (PostgreSQL) tem backup próprio, feito pelo contêiner rfid-backup do docker/docker-compose.yml.
 * O certificado fica fora do banco e precisa do seu: sem ele, um servidor restaurado gera outro certificado
 * e todos os coletores precisam ser confirmados de novo.
 * Para restaurar: pare o servidor, extraia o .zip na pasta data (substituindo os arquivos) e suba de novo.
 */
@Service
class BackupService(
    @Value("\${rfid.backup.habilitado:true}") private val habilitado: Boolean,
    @Value("\${rfid.backup.pasta:}") pastaConfigurada: String,
    @Value("\${rfid.backup.manter:14}") private val manter: Int,
    private val certificado: CertificadoHttps
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Pasta dos backups: a configurada, ou data/backups (ao lado do certificado no caminho padrão). */
    val pasta: Path = pastaConfigurada.trim().ifEmpty { null }?.let { Path.of(it) } ?: Path.of("data", "backups")

    /** Sem certificado em disco (HTTPS desligado) não há o que guardar. */
    val ativo: Boolean get() = habilitado && certificado.arquivosParaBackup().isNotEmpty()

    fun motivoInativo(): String = when {
        !habilitado -> "desativado na configuração (rfid.backup.habilitado=false)"
        else -> "nenhum certificado HTTPS para guardar"
    }

    @Scheduled(cron = "\${rfid.backup.cron:0 0 2 * * *}")
    fun backupAgendado() {
        if (!ativo) return
        // Falha no agendado não pode derrubar nada: fica registrada no log e tenta de novo no próximo horário
        runCatching { fazerBackup("agendado") }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun verificarNaInicializacao() {
        if (!ativo) {
            log.info("Backup automático do certificado HTTPS: inativo ({})", motivoInativo())
            return
        }

        val ultimo = ultimoBackup()
        log.info(
            "Backup automático do certificado HTTPS: ativo, pasta {}, mantendo os últimos {} (último: {}). " +
                "O banco tem backup próprio (contêiner rfid-backup)",
            pasta.toAbsolutePath(), manter, ultimo?.let { "${it.fileName}, ${idade(it)}" } ?: "nenhum"
        )

        // Garante que exista pelo menos um backup recente logo depois de instalar/atualizar
        if (ultimo == null || Duration.between(dataDoArquivo(ultimo), LocalDateTime.now()).toHours() >= 24) {
            log.info("Nenhum backup do certificado nas últimas 24 horas: fazendo um agora")
            runCatching { fazerBackup("inicialização") }
        }
    }

    /** Faz o backup e devolve o arquivo gerado. Sincronizado para o manual e o agendado não rodarem juntos. */
    @Synchronized
    fun fazerBackup(motivo: String): Path {
        check(ativo) { "Backup indisponível: ${motivoInativo()}" }

        val nome = "rfid-certificado-${LocalDateTime.now().format(FORMATO_NOME)}.zip"
        val destino = pasta.resolve(nome)
        val parcial = pasta.resolve("$nome.parcial")
        val arquivos = certificado.arquivosParaBackup()

        try {
            Files.createDirectories(pasta)
            Files.deleteIfExists(parcial)

            // Na raiz do .zip: com o caminho padrão (./data), extrair na pasta data já põe tudo no lugar
            ZipOutputStream(Files.newOutputStream(parcial)).use { zip ->
                arquivos.forEach { arquivo ->
                    zip.putNextEntry(ZipEntry(arquivo.name))
                    Files.copy(arquivo.toPath(), zip)
                    zip.closeEntry()
                }
            }
            validar(parcial, arquivos.map { it.name })

            // Só vira um backup "de verdade" depois de pronto e validado.
            // Dois backups no mesmo segundo (ex.: duplo clique) têm o mesmo nome: o novo substitui o anterior.
            Files.deleteIfExists(destino)
            Files.move(parcial, destino, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(parcial) }
            log.error("Falha no backup do certificado HTTPS ({}): {}", motivo, e.message, e)
            throw IllegalStateException("Falha ao fazer o backup: ${e.message}", e)
        }

        log.info("Backup do certificado HTTPS concluído ({}): {} ({})", motivo, nome, arquivos.joinToString { it.name })

        removerAntigos()
        return destino
    }

    fun listarBackups(): List<Path> {
        if (!Files.isDirectory(pasta)) return emptyList()
        return Files.list(pasta).use { arquivos ->
            arquivos.filter { PADRAO_NOME.matches(it.fileName.toString()) }.toList()
        }.sortedByDescending { it.fileName.toString() } // o nome tem data e hora, então ordena cronologicamente
    }

    fun ultimoBackup(): Path? = listarBackups().firstOrNull()

    /** Texto para a tela de status, ex.: "13/10/2026 02:00 (há 9h)". */
    fun resumoUltimoBackup(): String {
        if (!ativo) return "inativo (${motivoInativo()})"
        val ultimo = ultimoBackup() ?: return "nenhum backup ainda"
        return "${dataDoArquivo(ultimo).format(FORMATO_TELA)} (${idade(ultimo)})"
    }

    private fun removerAntigos() {
        val excedentes = listarBackups().drop(manter.coerceAtLeast(1))
        if (excedentes.isEmpty()) return

        excedentes.forEach { Files.deleteIfExists(it) }
        log.info(
            "{} backup(s) antigo(s) do certificado removido(s) para manter os últimos {}: {}",
            excedentes.size, manter, excedentes.joinToString { it.fileName.toString() }
        )
    }

    // Um backup que não abre como zip ou perdeu algum arquivo no caminho não serve para restaurar
    private fun validar(arquivo: Path, nomes: List<String>) {
        ZipFile(arquivo.toFile()).use { zip ->
            nomes.forEach { nome ->
                check(zip.getEntry(nome)?.let { it.size > 0 } == true) { "o arquivo gerado não contém $nome" }
            }
        }
    }

    private fun dataDoArquivo(arquivo: Path): LocalDateTime =
        LocalDateTime.parse(PADRAO_NOME.find(arquivo.fileName.toString())!!.groupValues[1], FORMATO_NOME)

    private fun idade(arquivo: Path): String {
        val d = Duration.between(dataDoArquivo(arquivo), LocalDateTime.now())
        return when {
            d.toDays() > 0 -> "há ${d.toDays()}d ${d.toHoursPart()}h"
            d.toHours() > 0 -> "há ${d.toHours()}h"
            else -> "há ${d.toMinutes()}min"
        }
    }

    companion object {
        private val FORMATO_NOME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
        private val FORMATO_TELA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        private val PADRAO_NOME = Regex("""^rfid-certificado-(\d{4}-\d{2}-\d{2}_\d{6})\.zip$""")
    }
}
