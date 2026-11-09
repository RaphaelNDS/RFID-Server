package org.example.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

/** Resultado da conferência de um dump do banco. */
enum class ResultadoDump {
    /** O pg_restore leu o arquivo inteiro sem erro: o dump restaura. */
    INTEIRO,
    /** O pg_restore acusou erro no arquivo (ex.: "end of file"): cortado, não restaura. */
    INCOMPLETO,
    /** Não deu para conferir (Docker parado, contêiner fora do ar, demorou demais): não é prova de problema. */
    NAO_CONFERIDO
}

/**
 * Confere se um dump do banco está inteiro, pedindo ao contêiner de backup que leia o arquivo todo
 * (`pg_restore -f /dev/null`, que não grava nada). Um dump cortado (computador desligado no meio do pg_dump) tem
 * mais de 0 bytes e passava como "em dia"; e o `pg_restore --list` não serve: ele só lê o índice do começo do
 * arquivo e aceita um dump que perdeu só o final (testado em 09/11/2026 com um dump real sem os últimos 200 bytes).
 *
 * [comando]: o comando com `{arquivo}` no lugar do nome do dump (dentro da pasta last do contêiner). Vazio = não
 * confere (os dumps contam como inteiros pelo tamanho, como antes).
 */
@Component
class ConferenciaDump(
    @Value("\${rfid.banco.conferir-dump:docker exec rfid-backup pg_restore -f /dev/null /backups/last/{arquivo}}")
    comando: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** O comando separado em partes (sem aspas: o padrão e os usos previstos não têm espaço nos argumentos). */
    private val partes: List<String> = comando.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    val ligada: Boolean get() = partes.isNotEmpty()

    fun conferir(arquivo: Path): ResultadoDump {
        if (!ligada) return ResultadoDump.NAO_CONFERIDO
        // Só o nome do arquivo entra no comando, e só no formato dos dumps (rfid-AAAAMMDD-HHMMSS.dump)
        if (!NOME_VALIDO.matches(arquivo.name)) return ResultadoDump.NAO_CONFERIDO
        val processo = try {
            ProcessBuilder(partes.map { it.replace("{arquivo}", arquivo.name) }).redirectErrorStream(true).start()
        } catch (e: Exception) {
            log.debug("Conferência do dump {} não rodou: {}", arquivo.name, e.message)
            return ResultadoDump.NAO_CONFERIDO
        }
        // Lê a saída numa linha à parte: com a saída cheia, o processo travaria esperando alguém ler
        val saida = StringBuilder()
        val leitor = Thread { runCatching { processo.inputStream.bufferedReader().forEachLine { if (saida.length < 4000) saida.appendLine(it) } } }
            .apply { isDaemon = true; start() }
        if (!processo.waitFor(LIMITE_SEGUNDOS, TimeUnit.SECONDS)) {
            processo.destroyForcibly()
            log.warn("Conferência do dump {} passou de {} s: não conferido", arquivo.name, LIMITE_SEGUNDOS)
            return ResultadoDump.NAO_CONFERIDO
        }
        leitor.join(2000)
        val texto = saida.toString().trim()
        return when {
            processo.exitValue() == 0 -> ResultadoDump.INTEIRO
            // Erro do próprio pg_restore = o arquivo tem problema. Qualquer outro erro (docker sem o contêiner, Docker
            // Desktop fechado) é do ambiente, não do dump
            texto.contains("pg_restore:") -> {
                log.warn("Dump do banco {} está incompleto ou corrompido: {}", arquivo.name, texto.lineSequence().firstOrNull { it.contains("pg_restore:") })
                ResultadoDump.INCOMPLETO
            }
            else -> {
                log.debug("Conferência do dump {} não rodou (código {}): {}", arquivo.name, processo.exitValue(), texto.take(300))
                ResultadoDump.NAO_CONFERIDO
            }
        }
    }

    private companion object {
        val NOME_VALIDO = Regex("""^[A-Za-z0-9_.-]+-\d{8}-\d{6}\.dump$""")
        const val LIMITE_SEGUNDOS = 120L
    }
}
