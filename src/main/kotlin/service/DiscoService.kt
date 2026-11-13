package org.example.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Quanto ocupa cada coisa do servidor no disco (nulo = não deu para medir). */
data class ParteDisco(val nome: String, val bytes: Long?, val dica: String? = null)

data class SituacaoDisco(
    val pasta: String,
    val livre: Long,
    val total: Long,
    val partes: List<ParteDisco>,
    /** Jars de versões anteriores na pasta do servidor (o rfid-server.jar em uso não entra). */
    val jarsAntigos: List<String>,
    val pouco: Boolean
) {
    val percentualLivre: Int get() = if (total <= 0) 0 else ((livre * 100) / total).toInt()
    val resumo: String get() = "${tamanho(livre)} livres de ${tamanho(total)} ($percentualLivre%)"

    companion object {
        @JvmStatic
        fun tamanho(bytes: Long?): String = when {
            bytes == null -> "—"
            bytes >= 1L shl 30 -> String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f GB", bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> "${bytes / (1L shl 20)} MB"
            bytes >= 1L shl 10 -> "${bytes / (1L shl 10)} KB"
            else -> "$bytes bytes"
        }
    }
}

/**
 * Espaço em disco (1.32.0). A máquina do servidor tem um disco só: o banco (Docker), os backups, os logs e os jars de
 * versões anteriores disputam o mesmo espaço. A tela Status mostra quanto sobra e quanto ocupa cada parte; com pouco
 * espaço (menos de [minimoGb] GB ou de [minimoPercentual]% livres) aparece o alerta e vai o aviso (MonitorServidorService).
 * Só mede: nada é apagado pelo servidor.
 */
@Service
class DiscoService(
    @Value("\${rfid.disco.minimo-gb:5}") private val minimoGb: Long,
    @Value("\${rfid.disco.minimo-percentual:10}") private val minimoPercentual: Int,
    @Value("\${rfid.disco.pasta-servidor:servidor}") pastaServidor: String,
    private val jdbc: JdbcTemplate? = null,
    private val backupBanco: BackupBancoService? = null,
    private val backupCertificado: BackupService? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val raiz: Path = Path.of(".").toAbsolutePath().normalize()
    private val servidor: Path = Path.of(pastaServidor).toAbsolutePath().normalize()

    fun situacao(): SituacaoDisco {
        val loja = Files.getFileStore(raiz)
        val livre = loja.usableSpace
        val total = loja.totalSpace
        val jars = jarsAntigos()
        val partes = listOf(
            ParteDisco("Banco de dados", medir("banco") { jdbc?.queryForObject("select pg_database_size(current_database())", Long::class.java) },
                "Dentro do Docker; cresce com as leituras guardadas"),
            ParteDisco("Backups do banco", backupBanco?.pasta?.let { p -> medir("backups do banco") { tamanhoDaPasta(p) } }, backupBanco?.pasta?.toString()),
            ParteDisco("Backups do certificado", backupCertificado?.pasta?.let { p -> medir("backups do certificado") { tamanhoDaPasta(p) } },
                backupCertificado?.pasta?.toAbsolutePath()?.toString()),
            ParteDisco("Logs", medir("logs") { tamanhoDaPasta(Path.of("data", "logs")) }),
            ParteDisco("Versões anteriores do servidor (${jars.size} jar(s))", jars.sumOf { servidor.resolve(it).fileSize() },
                if (jars.isEmpty()) null else "Podem ser apagados, deixando o rfid-server.jar e o -anterior mais novo")
        )
        val pouco = livre < minimoGb * (1L shl 30) || (total > 0 && livre * 100 / total < minimoPercentual)
        return SituacaoDisco(raiz.root?.toString() ?: raiz.toString(), livre, total, partes, jars, pouco)
    }

    /** Os .jar da pasta do servidor que não são o rfid-server.jar em uso, do mais antigo para o mais novo. */
    fun jarsAntigos(): List<String> = runCatching {
        if (!Files.isDirectory(servidor)) return emptyList()
        Files.list(servidor).use { s ->
            s.filter { it.isRegularFile() && it.name.endsWith(".jar") && it.name != "rfid-server.jar" }
                .sorted(compareBy { Files.getLastModifiedTime(it) }).map { it.name }.toList()
        }
    }.getOrElse { emptyList() }

    private fun tamanhoDaPasta(p: Path): Long? {
        if (!Files.isDirectory(p)) return null
        return Files.walk(p).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0) }.sum() }
    }

    private fun medir(oque: String, conta: () -> Long?): Long? = runCatching(conta)
        .onFailure { log.debug("Não foi possível medir o espaço de {}: {}", oque, it.message) }.getOrNull()
}
