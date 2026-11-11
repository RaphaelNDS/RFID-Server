package org.example.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** O que a pasta de backups do banco mostra agora. */
data class SituacaoBackupBanco(
    /** Último dump inteiro (null = nenhum). Com a conferência desligada ou sem conseguir conferir: o último com conteúdo. */
    val ultimoBom: Path?,
    val momentoUltimoBom: LocalDateTime?,
    /** O dump mais recente está vazio ou incompleto: a última tentativa falhou (ex.: banco ainda subindo, computador
     *  desligado no meio do pg_dump). */
    val ultimaTentativaFalhou: Boolean,
    /** Sem dump bom há mais que o limite (ou nenhum): o contêiner de backup parou ou não consegue falar com o banco. */
    val atrasado: Boolean,
    /** Pasta não encontrada: o servidor não consegue conferir (pasta configurada errada ou Docker em outro lugar). */
    val pastaEncontrada: Boolean,
    /** Texto para a tela de status. */
    val resumo: String,
    /** O [ultimoBom] foi lido inteiro pelo pg_restore (false = só o tamanho foi conferido). */
    val integridadeConferida: Boolean = false
)

/**
 * Confere se o backup do banco está acontecendo. Quem faz o backup é o contêiner rfid-backup (docker-compose), que
 * o servidor não controla; mas os dumps caem numa pasta desta máquina (docker\backups\last), e o servidor olha a
 * data do mais recente. Aparece na tela de status e vai para o log (aviso) quando estiver atrasado ou falhando:
 * entre 19/10 e 03/11/2026 o backup ficou sem rodar sem ninguém perceber, e em 04/11 uma tentativa gerou um
 * arquivo vazio.
 *
 * Desde a 1.4.6 o dump também é lido inteiro ([ConferenciaDump]): um arquivo cortado (computador desligado no meio
 * do backup) tinha mais de 0 bytes e aparecia "em dia". Cada dump é conferido uma vez só (o resultado fica na memória
 * pelo nome e tamanho); se não der para conferir (Docker fora do ar), vale o tamanho, como antes, sem alarme.
 */
@Service
class BackupBancoService(
    @Value("\${rfid.banco.pasta-backups:./docker/backups}") pasta: String,
    @Value("\${rfid.banco.backup-atrasado-horas:12}") private val horasAtraso: Long,
    private val relogio: Clock,
    private val conferencia: ConferenciaDump? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Nome do dump → (tamanho conferido, resultado). Só INTEIRO e INCOMPLETO ficam guardados: o NAO_CONFERIDO tenta de novo. */
    private val conferidos = ConcurrentHashMap<String, Pair<Long, ResultadoDump>>()

    private fun conferir(dump: Path): ResultadoDump {
        val c = conferencia?.takeIf { it.ligada } ?: return ResultadoDump.NAO_CONFERIDO
        val tamanho = dump.fileSize()
        conferidos[dump.name]?.takeIf { it.first == tamanho }?.let { return it.second }
        val resultado = c.conferir(dump)
        if (resultado != ResultadoDump.NAO_CONFERIDO) conferidos[dump.name] = tamanho to resultado
        // Os dumps apagados pela rotação do contêiner não precisam continuar na memória
        if (conferidos.size > 200) conferidos.keys.retainAll(setOf(dump.name))
        return resultado
    }

    /** Onde o contêiner grava (a mesma pasta do RFID_BACKUP_PASTA do docker\.env); os dumps avulsos ficam em last. */
    val pasta: Path = Path.of(pasta).toAbsolutePath().normalize()

    fun situacao(): SituacaoBackupBanco {
        val last = pasta.resolve("last")
        if (!Files.isDirectory(last)) {
            return SituacaoBackupBanco(null, null, false, atrasado = false, pastaEncontrada = false,
                resumo = "não conferido: pasta $last não encontrada (rfid.banco.pasta-backups)")
        }

        // rfid-AAAAMMDD-HHMMSS.dump; o rfid-latest.dump é um atalho (aparece com 0 bytes no Windows) e fica de fora
        val dumps = Files.list(last).use { arquivos ->
            arquivos.filter { it.isRegularFile() && PADRAO.matches(it.name) }.toList()
        }.sortedByDescending { it.name }

        val maisRecente = dumps.firstOrNull()

        // Do mais novo para o mais antigo: o primeiro com conteúdo que não esteja incompleto é o último bom
        var ultimoBom: Path? = null
        var conferido = false
        val incompletos = mutableSetOf<Path>()
        for (dump in dumps.filter { it.fileSize() > 0 }) {
            when (conferir(dump)) {
                ResultadoDump.INCOMPLETO -> { incompletos.add(dump) }
                ResultadoDump.INTEIRO -> { ultimoBom = dump; conferido = true; break }
                ResultadoDump.NAO_CONFERIDO -> { ultimoBom = dump; break }
            }
        }

        val momento = ultimoBom?.let(::momentoDo)
        val vazio = maisRecente != null && maisRecente.fileSize() == 0L
        val incompleto = maisRecente != null && maisRecente in incompletos
        val agora = LocalDateTime.now(relogio)
        val atrasado = momento == null || Duration.between(momento, agora).toHours() >= horasAtraso

        val resumo = buildString {
            append(if (ultimoBom == null) "nenhum backup encontrado" else "${momento!!.format(FORMATO_TELA)} (${idade(momento, agora)})")
            if (atrasado && ultimoBom != null) append(" — ATRASADO: o contêiner faz de 6 em 6 horas")
            if (vazio) append(" — a última tentativa (${momentoDo(maisRecente!!).format(FORMATO_TELA)}) gerou um arquivo vazio")
            if (incompleto) append(" — a última tentativa (${momentoDo(maisRecente!!).format(FORMATO_TELA)}) gerou um arquivo incompleto, que não restaura")
            if (ultimoBom != null && conferencia?.ligada == true && !conferido) append(" — integridade não conferida (Docker fora do ar?)")
        }
        return SituacaoBackupBanco(ultimoBom, momento, vazio || incompleto, atrasado, true, resumo, conferido)
    }

    /** 10 min depois de subir e a cada 6 h: avisa no log se o backup do banco parou ou está falhando. */
    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT6H")
    fun conferir() {
        runCatching {
            val s = situacao()
            when {
                !s.pastaEncontrada -> log.info("Backup do banco não conferido: pasta {} não encontrada", pasta.resolve("last"))
                s.atrasado || s.ultimaTentativaFalhou -> log.warn(
                    "Backup do banco com problema: último backup {}. Confira se o Docker Desktop está aberto e o contêiner " +
                        "rfid-backup no ar (docker ps; docker logs rfid-backup)", s.resumo)
                else -> log.debug("Backup do banco em dia: {}", s.resumo)
            }
        }.onFailure { log.error("Não foi possível conferir o backup do banco em {}", pasta, it) }
    }

    private fun momentoDo(arquivo: Path): LocalDateTime =
        PADRAO.find(arquivo.name)?.groupValues?.get(1)?.let { LocalDateTime.parse(it, FORMATO_NOME) }
            ?: LocalDateTime.ofInstant(arquivo.getLastModifiedTime().toInstant(), ZoneId.systemDefault())

    private fun idade(momento: LocalDateTime, agora: LocalDateTime): String {
        val d = Duration.between(momento, agora)
        return when {
            d.toDays() > 0 -> "há ${d.toDays()}d ${d.toHoursPart()}h"
            d.toHours() > 0 -> "há ${d.toHours()}h"
            else -> "há ${d.toMinutes()}min"
        }
    }

    private companion object {
        val PADRAO = Regex("""^.+-(\d{8}-\d{6})\.dump$""")
        val FORMATO_NOME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val FORMATO_TELA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    }
}
