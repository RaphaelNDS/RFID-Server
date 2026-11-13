package org.example

import org.example.util.CifraBackup
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

@SpringBootApplication
@EnableScheduling // tarefas agendadas, como o backup diário do banco
class RfidApplication {

    // Relógio injetável: nos testes dá para "avançar o tempo" (ex.: fim do bloqueio de login)
    @Bean
    fun relogio(): Clock = Clock.systemDefaultZone()
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--decifrar-backup") {
        kotlin.system.exitProcess(decifrarBackup(args.drop(1)))
    }
    // 1.33.0: uma cópia só por pasta, decidido antes de qualquer coisa (o Spring nem começa na segunda cópia).
    // RFID_TRAVA_INSTANCIA=false desliga; RFID_TRAVA_ARQUIVO muda o arquivo (padrão ./data/servidor.lock)
    if (System.getenv("RFID_TRAVA_INSTANCIA")?.trim()?.equals("false", ignoreCase = true) != true) {
        val arquivo = Path.of(System.getenv("RFID_TRAVA_ARQUIVO")?.takeIf { it.isNotBlank() } ?: "./data/servidor.lock")
        when (val r = org.example.util.TravaInstancia.adquirir(arquivo)) {
            is org.example.util.TravaInstancia.Resultado.Ocupada -> {
                System.err.println("[ERRO] Já tem um servidor RFID rodando nesta pasta" + (r.quem?.let { " ($it)" } ?: "") + ".")
                System.err.println("       Esta segunda cópia não vai subir: duas ao mesmo tempo disputariam o banco, os backups e a porta.")
                System.err.println("       Se o outro ainda está subindo, espere ele terminar (pode levar até 1 minuto). Para trocar, pare o outro antes.")
                kotlin.system.exitProcess(3)
            }
            is org.example.util.TravaInstancia.Resultado.SemTrava ->
                System.err.println("[AVISO] Não foi possível criar a trava de cópia única (${r.motivo}): o servidor sobe sem ela.")
            org.example.util.TravaInstancia.Resultado.Adquirida -> {}
        }
    }
    runApplication<RfidApplication>(*args)
}

/**
 * Restauração de um backup cifrado, sem subir o servidor:
 *   java -jar rfid-server.jar --decifrar-backup rfid-certificado-AAAA-MM-DD_HHMMSS.zip.cifrado
 *   java -jar rfid-server.jar --decifrar-backup rfid-AAAAMMDD-HHMMSS.dump.cifrado   (cópia do banco fora do computador)
 * A senha vem de RFID_BACKUP_SENHA ou é pedida no terminal. Grava ao lado o .zip (extraia-o na pasta data) ou o .dump.
 */
private fun decifrarBackup(argumentos: List<String>): Int {
    val origem = argumentos.firstOrNull()?.let { Path.of(it) }
    if (origem == null || !Files.isRegularFile(origem)) {
        System.err.println("Uso: java -jar rfid-server.jar --decifrar-backup arquivo.zip.cifrado")
        return 2
    }
    val senha = System.getenv("RFID_BACKUP_SENHA")?.takeIf { it.isNotBlank() }
        ?: System.console()?.readPassword("Senha do backup (RFID_BACKUP_SENHA): ")?.let { String(it) }
    if (senha.isNullOrBlank()) {
        System.err.println("Informe a senha do backup em RFID_BACKUP_SENHA ou rode num terminal para digitá-la")
        return 2
    }
    // .zip.cifrado (certificado) vira .zip; .dump.cifrado (cópia do banco fora do computador, 1.5.1) vira .dump
    val nome = origem.fileName.toString().removeSuffix(".cifrado")
    val destino = origem.resolveSibling(if (nome.endsWith(".zip") || nome.endsWith(".dump")) nome else "$nome.zip")
    return try {
        CifraBackup.decifrarArquivo(origem, destino, senha)
        if (nome.endsWith(".dump")) {
            println("Backup do banco decifrado em $destino. Para restaurar, copie o arquivo para docker\\backups\\last e siga o README (Restaurar o banco).")
        } else {
            println("Backup decifrado em $destino. Pare o servidor, extraia o .zip na pasta data e suba de novo.")
        }
        0
    } catch (e: IllegalArgumentException) {
        System.err.println("Não foi possível decifrar: ${e.message}")
        1
    }
}
