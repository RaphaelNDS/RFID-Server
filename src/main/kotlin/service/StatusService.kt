package org.example.service

import org.example.config.BancoDadosConfig
import org.example.response.ServerStatusResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.info.BuildProperties
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.lang.management.ManagementFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service
class StatusService(
    private val bancoDados: BancoDadosConfig,
    private val backup: BackupService,
    buildProperties: ObjectProvider<BuildProperties>,
    // Opcionais (valor padrão) para os testes que montam o service à mão continuarem iguais
    private val certificado: CertificadoHttps? = null,
    @Value("\${rfid.https.habilitado:true}") private val httpsHabilitado: Boolean = true
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // Momento em que a JVM subiu: é a "hora de início" real do servidor (antes mostrava a hora atual)
    private val iniciadoEm: LocalDateTime = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().startTime),
        ZoneId.systemDefault()
    )

    // Versão vem do build.gradle.kts (build-info). Rodando direto pela IDE sem o Gradle, pode não existir.
    private val versao: String = buildProperties.ifAvailable?.version ?: "desenvolvimento"

    fun getStatus(): ServerStatusResponse {
        return ServerStatusResponse(
            status = "ONLINE",
            horaServidor = "Desde ${iniciadoEm.format(FORMATO_DATA)}",
            versao = versao,
            banco = bancoDados.descricao,
            tempoOnline = formatarDuracao(Duration.between(iniciadoEm, LocalDateTime.now())),
            ultimoBackup = backup.resumoUltimoBackup(),
            certificadoHttps = when {
                !httpsHabilitado -> "HTTPS desligado: o tráfego não é criptografado"
                else -> certificado?.impressaoDigital ?: "—"
            }
        )
    }

    @EventListener
    fun servidorPronto(event: ApplicationReadyEvent) {
        val porta = event.applicationContext.environment.getProperty("local.server.port") ?: "?"
        val inicializacao = Duration.between(iniciadoEm, LocalDateTime.now())

        log.info(
            "Servidor RFID versão {} no ar na porta {} (iniciado em {}, pronto em {} s)",
            versao, porta, iniciadoEm.format(FORMATO_DATA), "%.1f".format(inicializacao.toMillis() / 1000.0)
        )

        if (versao == "desenvolvimento") {
            log.warn("Versão do sistema desconhecida: build-info não encontrado. Rode pelo Gradle (bootRun/bootJar) para exibir a versão.")
        }
    }

    private fun formatarDuracao(d: Duration): String {
        val dias = d.toDays()
        val horas = d.toHoursPart()
        val minutos = d.toMinutesPart()

        return when {
            dias > 0 -> "${dias}d ${horas}h ${minutos}min"
            horas > 0 -> "${horas}h ${minutos}min"
            minutos > 0 -> "${minutos}min"
            else -> "menos de 1min"
        }
    }

    companion object {
        private val FORMATO_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    }
}
