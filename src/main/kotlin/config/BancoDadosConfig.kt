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
            "Dados carregados: {} usuário(s), {} etiqueta(s) cadastrada(s), {}",
            usuarios, tagRepository.count(), leiturasRegistradas()
        )

        if (usuarios == 0L) {
            log.warn("Nenhum usuário cadastrado: acesse /setup para criar o administrador inicial")
        }
    }

    /**
     * Quantas leituras há, sem count(): a tabela de leituras chega a milhões de linhas e o count() a varria inteira
     * a cada subida só para esta linha de log. Usa a estimativa do PostgreSQL e, sem ela, só confere se há alguma.
     */
    fun leiturasRegistradas(): String = try {
        val estimativa = leituraRepository.estimativaDoTotal() ?: -1L
        when {
            estimativa > 0 -> "cerca de $estimativa leitura(s) registrada(s) (estimativa do PostgreSQL)"
            leituraRepository.existeAlguma() -> "leituras registradas (total ainda não estimado pelo PostgreSQL)"
            else -> "nenhuma leitura registrada"
        }
    } catch (e: Exception) {
        log.warn("Não foi possível estimar o total de leituras para o log de inicialização: {}", e.message)
        "total de leituras desconhecido"
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
    fun flywayMigrationStrategy() = estrategia(BackupAntesMigracao(comandoBackup), TravaVersaoCompleta())

    /** Separado do @Bean para o teste passar um backup e uma trava próprios. */
    fun estrategia(backup: BackupAntesMigracao, trava: TravaVersaoCompleta) = FlywayMigrationStrategy { flyway ->
        // 2.0.0: banco da versão completa (branch locadora) é recusado antes de tudo, com ou sem migração pendente
        trava.conferir(flyway)

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
