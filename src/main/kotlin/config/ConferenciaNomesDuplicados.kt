package org.example.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Ao subir, avisa no log do servidor o que as migrações V17 e V18 não conseguiram padronizar por haver dado repetido.
 *
 * Elas não apagam nem juntam nada sozinhas: com nomes repetidos (sem diferenciar maiúsculas) a V17 não cria o índice
 * daquela tabela, e com a mesma etiqueta gravada nas duas formas a V18 deixa as duas como estão. O aviso delas saía só
 * como NOTICE do PostgreSQL, que ninguém vê, e a proteção contra duplicata nunca chegava a essa instalação.
 */
@Component
class ConferenciaNomesDuplicados(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    private data class Indice(val nome: String, val descricao: String, val repetidos: String, val criar: String)

    private val indices = listOf(
        Indice("uk_locais_nome_minusculo", "locais de inventário",
            "select min(nome) from locais group by lower(nome) having count(*) > 1",
            "create unique index uk_locais_nome_minusculo on locais (lower(nome))"),
        Indice("uk_tipo_equipamento_nome_minusculo", "tipos de equipamento",
            "select min(nome) from tipo_equipamento group by lower(nome) having count(*) > 1",
            "create unique index uk_tipo_equipamento_nome_minusculo on tipo_equipamento (lower(nome))"),
        Indice("uk_marca_tipo_nome_minusculo", "marcas (no mesmo tipo)",
            "select min(nome) from marca group by tipo_id, lower(nome) having count(*) > 1",
            "create unique index uk_marca_tipo_nome_minusculo on marca (tipo_id, lower(nome))"),
        Indice("uk_modelo_marca_nome_minusculo", "modelos (na mesma marca)",
            "select min(nome) from modelo group by marca_id, lower(nome) having count(*) > 1",
            "create unique index uk_modelo_marca_nome_minusculo on modelo (marca_id, lower(nome))")
    )

    @EventListener(ApplicationReadyEvent::class)
    fun conferir() {
        runCatching {
            indices.forEach { indice ->
                val existe = jdbc.queryForObject("select count(*) from pg_indexes where indexname = ?", Int::class.java, indice.nome) ?: 0
                if (existe > 0) return@forEach
                val nomes = jdbc.queryForList(indice.repetidos + " limit 10", String::class.java)
                if (nomes.isEmpty()) {
                    // As repetições já foram resolvidas: cria agora a proteção que a V17 deixou de criar
                    jdbc.execute(indice.criar)
                    log.info("Proteção contra nome repetido criada em {} ({}): não há mais repetições", indice.descricao, indice.nome)
                    return@forEach
                }
                log.warn("Há {} com o mesmo nome (sem diferenciar maiúsculas): {}. A proteção contra nome repetido ({}) " +
                    "não foi criada; renomeie ou exclua as repetições e reinicie o servidor (ela é criada ao subir)",
                    indice.descricao, nomes.joinToString(), indice.nome)
            }
            val etiquetas = jdbc.queryForList(
                "select tag_real from tags where tag_real <> upper(btrim(tag_real)) limit 10", String::class.java)
            if (etiquetas.isNotEmpty()) {
                log.warn("Etiquetas gravadas em minúsculas que já existem em maiúsculas (a V18 manteve as duas): {}. " +
                    "Exclua a repetida na tela Etiquetas", etiquetas.joinToString())
            }
        }.onFailure { log.warn("Não foi possível conferir nomes e etiquetas repetidos no banco: {}", it.message) }
    }
}
