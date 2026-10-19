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
import java.io.File

@Configuration
class BancoDadosConfig(
    @Value("\${spring.datasource.url}") private val url: String,
    private val userRepository: UserRepository,
    private val tagRepository: TagRepository,
    private val leituraRepository: LeituraAndroidRepository
) {

    private val log = LoggerFactory.getLogger(BancoDadosConfig::class.java)

    /** Descrição curta do banco em uso, exibida na tela de status. */
    val descricao: String
        get() = when {
            url.startsWith("jdbc:h2:mem:") -> "H2 (memória)"
            url.startsWith("jdbc:h2:file:") || url.startsWith("jdbc:h2:./") || url.startsWith("jdbc:h2:~") -> "H2 (arquivo)"
            url.startsWith("jdbc:postgresql:") -> "PostgreSQL"
            else -> url.substringAfter("jdbc:").substringBefore(":")
        }

    // Caminho absoluto do arquivo .mv.db quando o banco é H2 em arquivo
    private fun arquivoH2(): File? {
        if (!url.startsWith("jdbc:h2:file:")) return null
        val caminho = url.removePrefix("jdbc:h2:file:").substringBefore(";")
            .replaceFirst(Regex("^~"), System.getProperty("user.home").replace("\\", "/"))
        return File("$caminho.mv.db").absoluteFile
    }

    @EventListener(ApplicationReadyEvent::class)
    fun registrarBancoEmUso() {
        val arquivo = arquivoH2()

        when {
            arquivo != null -> {
                val tamanhoKb = if (arquivo.exists()) arquivo.length() / 1024 else 0
                log.info("Banco de dados: H2 em arquivo: {} ({} KB)", arquivo.path, tamanhoKb)
            }
            url.startsWith("jdbc:h2:mem:") ->
                log.warn("Banco de dados em MEMÓRIA: todos os dados serão perdidos quando o servidor reiniciar")
            else ->
                log.info("Banco de dados: {}", descricao)
        }

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
class MigracoesConfig {

    private val log = LoggerFactory.getLogger(MigracoesConfig::class.java)

    @Bean
    fun flywayMigrationStrategy() = FlywayMigrationStrategy { flyway ->
        val pendentes = flyway.info().pending()

        if (pendentes.isEmpty()) {
            log.info(
                "Banco de dados em dia, nenhuma migração pendente (versão {})",
                flyway.info().current()?.version ?: "-"
            )
            return@FlywayMigrationStrategy
        }

        log.info(
            "Aplicando {} migração(ões) no banco: {}",
            pendentes.size, pendentes.joinToString { "V${it.version} (${it.description})" }
        )

        try {
            val resultado = flyway.migrate()
            log.info("Migrações aplicadas com sucesso. Versão atual do banco: {}", resultado.targetSchemaVersion)
        } catch (e: Exception) {
            log.error("Falha ao aplicar migrações no banco de dados. O servidor não será iniciado.", e)
            throw e
        }
    }
}
