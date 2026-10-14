package org.example.service

import jakarta.persistence.criteria.Predicate
import org.example.model.LeituraAndroidEntity
import org.example.repository.LeituraAndroidRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

/** Filtros da tela de leituras. Campos vazios não filtram. */
data class FiltroLeituras(
    val dia: LocalDate? = null,
    val tag: String? = null,
    val tipo: String? = null,
    val marca: String? = null,
    val modelo: String? = null,
    val numeroSerie: String? = null,
    val operador: String? = null
)

/**
 * Consulta das leituras para a tela web: filtros + paginação (a tabela cresce sem parar com os
 * coletores rodando, então nunca é carregada inteira).
 */
@Service
class LeituraAndroidConsultaService(
    private val repo: LeituraAndroidRepository,
    private val relogio: Clock,
    @Value("\${rfid.leituras.por-pagina:50}") porPagina: Int
) {

    private val log = LoggerFactory.getLogger(javaClass)

    val tamanhoPagina: Int = porPagina.coerceIn(10, MAXIMO_POR_PAGINA)

    /**
     * Uma página de leituras, da mais recente para a mais antiga.
     * [pagina] começa em 0; se passar da última, devolve a última (ex.: link antigo depois de um filtro).
     */
    fun buscar(filtro: FiltroLeituras, pagina: Int): Page<LeituraAndroidEntity> {
        val inicio = System.currentTimeMillis()
        val spec = especificacao(filtro)
        val ordem = Sort.by(Sort.Order.desc("dataHora"), Sort.Order.desc("id"))

        var resultado = repo.findAll(spec, PageRequest.of(pagina.coerceAtLeast(0), tamanhoPagina, ordem))

        if (resultado.content.isEmpty() && resultado.totalPages > 0 && pagina >= resultado.totalPages) {
            resultado = repo.findAll(spec, PageRequest.of(resultado.totalPages - 1, tamanhoPagina, ordem))
        }

        val duracao = System.currentTimeMillis() - inicio
        if (duracao > LIMITE_LENTO_MS) {
            log.warn("Consulta de leituras demorou {} ms ({} resultado(s), filtros: {})", duracao, resultado.totalElements, descrever(filtro))
        } else {
            log.debug("Consulta de leituras: página {} de {}, {} resultado(s) em {} ms (filtros: {})",
                resultado.number + 1, resultado.totalPages, resultado.totalElements, duracao, descrever(filtro))
        }

        return resultado
    }

    /**
     * Todas as leituras do filtro, da mais recente para a mais antiga, para exportar. Acima de
     * [MAXIMO_EXPORTACAO] recusa com mensagem (IllegalArgumentException → 400), em vez de montar uma planilha
     * gigante na memória: a pessoa filtra por dia, operador etc. e exporta de novo.
     */
    fun buscarParaExportar(filtro: FiltroLeituras): List<LeituraAndroidEntity> {
        val spec = especificacao(filtro)
        val total = repo.count(spec)

        if (total > MAXIMO_EXPORTACAO) {
            log.warn("Exportação de leituras recusada: {} resultado(s), acima do máximo de {} (filtros: {})", total, MAXIMO_EXPORTACAO, descrever(filtro))
            throw IllegalArgumentException(
                "A exportação teria $total leituras, acima do máximo de $MAXIMO_EXPORTACAO. Filtre por dia, operador ou outro campo e exporte de novo."
            )
        }

        return repo.findAll(spec, Sort.by(Sort.Order.desc("dataHora"), Sort.Order.desc("id")))
    }

    /** Texto dos filtros aplicados, para o log de quem exportou o quê. */
    fun descreverFiltro(filtro: FiltroLeituras): String = descrever(filtro)

    /** Leituras por dia nos últimos [dias] dias, do mais recente para o mais antigo (atalhos da tela). */
    fun diasRecentes(dias: Long = DIAS_ATALHO): Map<String, Long> {
        val hoje = LocalDate.now(relogio)
        return repo.contarPorDiaEntre(hoje.minusDays(dias - 1).atStartOfDay(), hoje.plusDays(1).atStartOfDay())
            .map { it[0].toString() to (it[1] as Number).toLong() }
            .sortedByDescending { it.first }
            .toMap(LinkedHashMap())
    }

    private fun especificacao(f: FiltroLeituras) = Specification<LeituraAndroidEntity> { root, _, cb ->
        val condicoes = mutableListOf<Predicate>()

        // Dia inteiro: [00:00 do dia, 00:00 do dia seguinte) — antes parava em 23:59:59
        f.dia?.let {
            condicoes += cb.greaterThanOrEqualTo(root.get("dataHora"), it.atStartOfDay())
            condicoes += cb.lessThan(root.get("dataHora"), it.plusDays(1).atStartOfDay())
        }

        // "Contém", sem diferenciar maiúsculas; % e _ digitados valem como texto, não como curinga
        mapOf(
            "tag" to f.tag, "tipo" to f.tipo, "marca" to f.marca,
            "modelo" to f.modelo, "numeroSerie" to f.numeroSerie, "operador" to f.operador
        ).forEach { (campo, valor) ->
            valor?.trim()?.takeIf { it.isNotEmpty() }?.let {
                condicoes += cb.like(cb.lower(root.get(campo)), "%${escapar(it.lowercase())}%", '\\')
            }
        }

        cb.and(*condicoes.toTypedArray())
    }

    private fun escapar(texto: String) = texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun descrever(f: FiltroLeituras) = listOfNotNull(
        f.dia?.let { "dia=$it" },
        f.tag?.takeIf { it.isNotBlank() }?.let { "etiqueta=$it" },
        f.tipo?.takeIf { it.isNotBlank() }?.let { "tipo=$it" },
        f.marca?.takeIf { it.isNotBlank() }?.let { "marca=$it" },
        f.modelo?.takeIf { it.isNotBlank() }?.let { "modelo=$it" },
        f.numeroSerie?.takeIf { it.isNotBlank() }?.let { "série=$it" },
        f.operador?.takeIf { it.isNotBlank() }?.let { "operador=$it" }
    ).ifEmpty { listOf("nenhum") }.joinToString(", ")

    companion object {
        const val MAXIMO_POR_PAGINA = 200
        const val MAXIMO_EXPORTACAO = 100_000L
        const val DIAS_ATALHO = 31L
        private const val LIMITE_LENTO_MS = 1000L
    }
}
