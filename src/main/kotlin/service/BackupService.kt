package org.example.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import javax.sql.DataSource

/**
 * Backup automático do banco H2 em arquivo.
 *
 * Usa o comando BACKUP TO do H2, que gera um .zip consistente sem parar o servidor. No mesmo .zip vão o
 * certificado HTTPS (.p12) e a senha dele (.senha): sem eles, um servidor restaurado gera outro certificado e
 * todos os coletores precisam ser confirmados de novo.
 * Para restaurar: pare o servidor, extraia o .zip na pasta do banco (substituindo os arquivos) e suba de novo.
 */
@Service
class BackupService(
    dataSource: DataSource,
    @Value("\${spring.datasource.url}") private val url: String,
    @Value("\${rfid.backup.habilitado:true}") private val habilitado: Boolean,
    @Value("\${rfid.backup.pasta:}") pastaConfigurada: String,
    @Value("\${rfid.backup.manter:14}") private val manter: Int,
    private val certificado: CertificadoHttps
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc = JdbcTemplate(dataSource)

    /** Pasta dos backups: a configurada, ou "backups" ao lado do arquivo do banco. */
    val pasta: Path = pastaConfigurada.trim().ifEmpty { null }?.let { Path.of(it) }
        ?: arquivoDoBanco()?.parentFile?.resolve("backups")?.toPath()
        ?: Path.of("data", "backups")

    /** Só faz sentido para H2 em arquivo (banco em memória não tem o que guardar; outros bancos têm ferramentas próprias). */
    val ativo: Boolean get() = habilitado && url.startsWith("jdbc:h2:file:")

    fun motivoInativo(): String = when {
        !habilitado -> "desativado na configuração (rfid.backup.habilitado=false)"
        url.startsWith("jdbc:h2:mem:") -> "banco em memória"
        else -> "o backup automático só funciona com H2 em arquivo"
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
            log.info("Backup automático do banco: inativo ({})", motivoInativo())
            return
        }

        val ultimo = ultimoBackup()
        log.info(
            "Backup automático do banco: ativo, pasta {}, mantendo os últimos {} (último: {})",
            pasta.toAbsolutePath(), manter, ultimo?.let { "${it.fileName}, ${idade(it)}" } ?: "nenhum"
        )

        // Garante que exista pelo menos um backup recente logo depois de instalar/atualizar
        if (ultimo == null || Duration.between(dataDoArquivo(ultimo), LocalDateTime.now()).toHours() >= 24) {
            log.info("Nenhum backup nas últimas 24 horas: fazendo um agora")
            runCatching { fazerBackup("inicialização") }
        }
    }

    /** Faz o backup e devolve o arquivo gerado. Sincronizado para o manual e o agendado não rodarem juntos. */
    @Synchronized
    fun fazerBackup(motivo: String): Path {
        check(ativo) { "Backup indisponível: ${motivoInativo()}" }

        val inicio = System.currentTimeMillis()
        val nome = "rfid-db-${LocalDateTime.now().format(FORMATO_NOME)}.zip"
        val destino = pasta.resolve(nome)
        val parcial = pasta.resolve("$nome.parcial")

        log.info("Iniciando backup do banco ({}): {}", motivo, destino.toAbsolutePath())

        val doCertificado: List<String>
        try {
            Files.createDirectories(pasta)
            Files.deleteIfExists(parcial)

            jdbc.execute("BACKUP TO '${parcial.toAbsolutePath().toString().replace("'", "''")}'")
            doCertificado = adicionarCertificado(parcial)

            validar(parcial, doCertificado)

            // Só vira um backup "de verdade" depois de pronto e validado.
            // Dois backups no mesmo segundo (ex.: duplo clique) têm o mesmo nome: o novo substitui o anterior.
            Files.deleteIfExists(destino)
            Files.move(parcial, destino, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(parcial) }
            log.error("Falha no backup do banco ({}): {}", motivo, e.message, e)
            throw IllegalStateException("Falha ao fazer o backup: ${e.message}", e)
        }

        log.info(
            "Backup concluído: {} ({} KB em {} ms){}",
            nome, Files.size(destino) / 1024, System.currentTimeMillis() - inicio,
            if (doCertificado.isEmpty()) ", sem certificado HTTPS (nenhum arquivo encontrado)"
            else ", com o certificado HTTPS (${doCertificado.joinToString()})"
        )

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
            "{} backup(s) antigo(s) removido(s) para manter os últimos {}: {}",
            excedentes.size, manter, excedentes.joinToString { it.fileName.toString() }
        )
    }

    /**
     * Copia o certificado HTTPS (.p12 e .senha) para dentro do .zip, na raiz, ao lado do banco: com o caminho
     * padrão (./data), extrair o .zip na pasta do banco já põe tudo no lugar. Devolve os nomes copiados.
     */
    private fun adicionarCertificado(zip: Path): List<String> {
        val arquivos = certificado.arquivosParaBackup()
        if (arquivos.isEmpty()) return emptyList()

        FileSystems.newFileSystem(zip).use { fs ->
            arquivos.forEach { Files.copy(it.toPath(), fs.getPath(it.name), StandardCopyOption.REPLACE_EXISTING) }
        }
        return arquivos.map { it.name }
    }

    // Um backup que não abre como zip, vem vazio ou perdeu o certificado no caminho não serve para restaurar
    private fun validar(arquivo: Path, doCertificado: List<String>) {
        ZipFile(arquivo.toFile()).use { zip ->
            check(zip.entries().asSequence().any { it.name.endsWith(".mv.db") && it.size != 0L }) {
                "o arquivo gerado não contém o banco"
            }
            doCertificado.forEach { nome ->
                check(zip.getEntry(nome)?.let { it.size > 0 } == true) { "o arquivo gerado não contém o certificado ($nome)" }
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

    private fun arquivoDoBanco(): File? {
        if (!url.startsWith("jdbc:h2:file:")) return null
        val caminho = url.removePrefix("jdbc:h2:file:").substringBefore(";")
            .replaceFirst(Regex("^~"), System.getProperty("user.home").replace("\\", "/"))
        return File(caminho).absoluteFile
    }

    companion object {
        private val FORMATO_NOME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
        private val FORMATO_TELA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        private val PADRAO_NOME = Regex("""^rfid-db-(\d{4}-\d{2}-\d{2}_\d{6})\.zip$""")
    }
}
