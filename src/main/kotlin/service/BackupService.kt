package org.example.service

import org.example.util.CifraBackup
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup automático do certificado HTTPS do servidor (.p12 e a senha dele, .senha).
 *
 * O banco (PostgreSQL) tem backup próprio, feito pelo contêiner rfid-backup do docker/docker-compose.yml.
 * O certificado fica fora do banco e precisa do seu: sem ele, um servidor restaurado gera outro certificado
 * e todos os coletores precisam ser confirmados de novo.
 * Para restaurar: pare o servidor, extraia o .zip na pasta data (substituindo os arquivos) e suba de novo. Backup
 * cifrado (.zip.cifrado, com RFID_BACKUP_SENHA): antes, java -jar rfid-server.jar --decifrar-backup arquivo.zip.cifrado
 */
@Service
class BackupService(
    @Value("\${rfid.backup.habilitado:true}") private val habilitado: Boolean,
    @Value("\${rfid.backup.pasta:}") pastaConfigurada: String,
    @Value("\${rfid.backup.manter:14}") private val manter: Int,
    private val certificado: CertificadoHttps,
    /** Senha que cifra o backup (RFID_BACKUP_SENHA). Vazia: o .zip sai aberto, com a chave privada e a senha dela. */
    @Value("\${rfid.backup.senha:}") private val senhaBackup: String = ""
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
        // Sobra de backup interrompido sai mesmo com o backup desligado: pode ser um .zip aberto com a chave privada
        runCatching { limparParciais() }
            .onFailure { log.error("Falha ao procurar sobras de backup do certificado em {}: {}", pasta.toAbsolutePath(), it.message, it) }

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
        if (senhaBackup.isBlank()) {
            log.warn("O backup do certificado leva a chave privada do HTTPS e a senha dela SEM criptografia: quem copiar um " +
                "backup consegue se passar pelo servidor para os coletores. Defina RFID_BACKUP_SENHA (guarde a senha fora " +
                "desta máquina) para cifrar os próximos")
        }

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

        val cifrado = senhaBackup.isNotBlank()
        val nome = "rfid-certificado-${LocalDateTime.now().format(FORMATO_NOME)}.zip" + if (cifrado) EXTENSAO_CIFRADO else ""
        val destino = pasta.resolve(nome)
        val parcial = pasta.resolve("$nome.parcial")
        val arquivos = certificado.arquivosParaBackup()

        try {
            Files.createDirectories(pasta)
            limparParciais()

            // O .zip é montado na memória (são poucos KB): antes ia ABERTO para <nome>.parcial na pasta de backup e
            // só depois era cifrado; se o servidor caísse no meio, a chave privada ficava aberta ali para sempre.
            // Na raiz do .zip: com o caminho padrão (./data), extrair na pasta data já põe tudo no lugar
            val memoria = ByteArrayOutputStream()
            ZipOutputStream(memoria).use { zip ->
                arquivos.forEach { arquivo ->
                    zip.putNextEntry(ZipEntry(arquivo.name))
                    Files.copy(arquivo.toPath(), zip)
                    zip.closeEntry()
                }
            }
            val zip = memoria.toByteArray()
            validar(zip, arquivos.map { it.name })
            // A chave privada não sai aberta da máquina: só quem tem a senha (fora do backup) restaura
            val conteudo = if (cifrado) CifraBackup.cifrar(zip, senhaBackup) else zip

            // Na pasta de backup só entra o conteúdo final (já cifrado, se for o caso): o .parcial existe só para a
            // troca atômica de nome, e um que sobrar de uma queda é apagado no próximo backup ou ao subir.
            Files.write(parcial, conteudo)

            // Só vira um backup "de verdade" depois de pronto e validado.
            // Dois backups no mesmo segundo (ex.: duplo clique) têm o mesmo nome: o novo substitui o anterior.
            Files.deleteIfExists(destino)
            Files.move(parcial, destino, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(parcial) }
            log.error("Falha no backup do certificado HTTPS ({}): {}", motivo, e.message, e)
            throw IllegalStateException("Falha ao fazer o backup: ${e.message}", e)
        }

        log.info("Backup do certificado HTTPS concluído ({}): {} ({}{})", motivo, nome, arquivos.joinToString { it.name },
            if (cifrado) ", cifrado com a senha de rfid.backup.senha" else "")

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

    /**
     * Apaga os .parcial que sobraram de um backup interrompido (servidor derrubado no meio). Nas versões até a 1.4.2
     * o .parcial era o .zip ABERTO, com a chave privada do HTTPS, mesmo com RFID_BACKUP_SENHA.
     */
    private fun limparParciais() {
        if (!Files.isDirectory(pasta)) return
        val sobras = Files.list(pasta).use { arquivos ->
            arquivos.filter { PADRAO_PARCIAL.matches(it.fileName.toString()) }.toList()
        }
        sobras.forEach { sobra ->
            runCatching { Files.deleteIfExists(sobra) }
                .onSuccess {
                    log.warn("Backup do certificado interrompido encontrado e apagado: {} (sobra de um backup que não " +
                        "terminou, por exemplo com o servidor desligado no meio)", sobra.fileName)
                }
                .onFailure { log.error("Não foi possível apagar a sobra de backup {}: {}", sobra.toAbsolutePath(), it.message, it) }
        }
    }

    // Um backup que não abre como zip ou perdeu algum arquivo no caminho não serve para restaurar
    private fun validar(zip: ByteArray, nomes: List<String>) {
        val tamanhos = mutableMapOf<String, Long>()
        ZipInputStream(ByteArrayInputStream(zip)).use { entrada ->
            generateSequence { entrada.nextEntry }.forEach { item ->
                tamanhos[item.name] = entrada.readAllBytes().size.toLong()
            }
        }
        nomes.forEach { nome ->
            check((tamanhos[nome] ?: 0) > 0) { "o arquivo gerado não contém $nome" }
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
        private val PADRAO_NOME = Regex("""^rfid-certificado-(\d{4}-\d{2}-\d{2}_\d{6})\.zip(\.cifrado)?$""")
        private val PADRAO_PARCIAL = Regex("""^rfid-certificado-.*\.parcial$""")
        const val EXTENSAO_CIFRADO = ".cifrado"
    }
}
