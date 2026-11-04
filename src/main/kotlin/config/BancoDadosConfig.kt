package org.example.config

import org.example.repository.LeituraAndroidRepository
import org.example.repository.TagRepository
import org.example.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener

@Configuration
class BancoDadosConfig(
    @Value("\${spring.datasource.url}") private val url: String,
    private val userRepository: UserRepository,
    private val tagRepository: TagRepository,
    private val leituraRepository: LeituraAndroidRepository
) {

    private val log = LoggerFactory.getLogger(BancoDadosConfig::class.java)

    /** Descrição curta do banco em uso, exibida na tela de status, ex.: "PostgreSQL (localhost:5433/rfid)". */
    val descricao: String
        get() = when {
            url.startsWith("jdbc:postgresql://") ->
                "PostgreSQL (${url.removePrefix("jdbc:postgresql://").substringBefore("?")})"
            url.startsWith("jdbc:tc:postgresql:") -> "PostgreSQL (contêiner de teste)"
            else -> url.substringAfter("jdbc:").substringBefore(":")
        }

    @EventListener(ApplicationReadyEvent::class)
    fun registrarBancoEmUso() {
        log.info("Banco de dados: {}", descricao)

        val usuarios = userRepository.count()
        log.info(
            "Dados carregados: {} usuário(s), {} etiqueta(s) cadastrada(s), {} leitura(s) registrada(s)",
            usuarios, tagRepository.count(), leituraRepository.count()
        )

        if (usuarios == 0L) {
            log.warn("Nenhum usuário cadastrado: acesse /setup para criar o administrador inicial")
        }
    }
}

// Separado do BancoDadosConfig de propósito: o Flyway roda antes do JPA,
// então esta classe não pode depender de repositórios.
@Configuration
class MigracoesConfig(
    @Value("\${rfid.banco.backup-antes-migracao:}") private val comandoBackup: String
) {

    private val log = LoggerFactory.getLogger(MigracoesConfig::class.java)

    @Bean
    fun flywayMigrationStrategy() = estrategia(BackupAntesMigracao(comandoBackup))

    /** Separado do @Bean para o teste passar um backup próprio. */
    fun estrategia(backup: BackupAntesMigracao) = FlywayMigrationStrategy { flyway ->
        val pendentes = flyway.info().pending()
        val atual = flyway.info().current()

        if (pendentes.isEmpty()) {
            log.info("Banco de dados em dia, nenhuma migração pendente (versão {})", atual?.version ?: "-")
            return@FlywayMigrationStrategy
        }

        log.info(
            "Aplicando {} migração(ões) no banco: {}",
            pendentes.size, pendentes.joinToString { "V${it.version} (${it.description})" }
        )

        // Banco vazio (primeira instalação) não tem o que guardar. Falha no backup lança exceção e não migra
        if (atual != null) backup.executar()

        try {
            val resultado = flyway.migrate()
            log.info("Migrações aplicadas com sucesso. Versão atual do banco: {}", resultado.targetSchemaVersion)
        } catch (e: Exception) {
            log.error("Falha ao aplicar migrações no banco de dados. O servidor não será iniciado.", e)
            throw e
        }
    }
}
