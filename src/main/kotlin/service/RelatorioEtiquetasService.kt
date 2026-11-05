package org.example.service

import org.example.repository.TagRepository
import org.example.response.TagViewAdmin
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Uma etiqueta com a última vez que apareceu numa leitura e num inventário. */
data class EtiquetaVista(
    val etiqueta: TagViewAdmin,
    val ultimaLeitura: LocalDateTime?,
    val ultimoInventario: LocalDateTime?,
    /** Local do inventário mais recente em que foi lida (o último local conhecido). */
    val ultimoLocal: String?,
    /** Data de referência para contar os dias (hoje, pelo relógio do servidor). */
    val hoje: LocalDate
) {
    /** A mais recente entre leitura e inventário; null = nunca vista. */
    val ultimaVez: LocalDateTime? = listOfNotNull(ultimaLeitura, ultimoInventario).maxOrNull()

    /** Dias desde a última vez vista; null = nunca vista. */
    val diasSemSerVista: Long? = ultimaVez?.let { ChronoUnit.DAYS.between(it.toLocalDate(), hoje) }
}

/**
 * Relatórios do cadastro de patrimônio: exportação das etiquetas com a última vez vista e o último local, e a
 * lista das "não vistas há N dias" (equipamento sumido, emprestado ou com a etiqueta danificada).
 *
 * As últimas datas vêm de uma consulta só, que busca cada etiqueta pelos índices (sem agrupar a tabela de leituras).
 */
@Service
class RelatorioEtiquetasService(
    private val rfidService: RfidService,
    private val tags: TagRepository,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Todas as etiquetas da [busca] (a mesma da tela), com a última vez vista. */
    fun etiquetasComUltimaVez(busca: String?): List<EtiquetaVista> {
        val etiquetas = rfidService.listarTodasParaAdmin(busca)
        if (etiquetas.isEmpty()) return emptyList()

        // [tag, última leitura, último inventário, último local] por etiqueta, numa consulta só (ver TagRepository)
        val ultimas = tags.ultimasVezesPorTag().associateBy { it[0] as String }

        val hoje = LocalDate.now(relogio)
        return etiquetas.map { e ->
            val u = ultimas[e.tag]
            EtiquetaVista(e, data(u?.get(1)), data(u?.get(2)), u?.get(3) as String?, hoje)
        }
    }

    /**
     * Etiquetas sem leitura nem inventário há mais de [dias] dias (as nunca vistas primeiro, depois as vistas há
     * mais tempo). [pagina] começa em 0.
     */
    fun naoVistas(dias: Int, pagina: Int): Page<EtiquetaVista> {
        val todas = naoVistasTodas(dias)
        return RfidService.paginaValida(pagina) { p ->
            val pedido = PageRequest.of(p, RfidService.TAMANHO_PAGINA)
            val inicio = (p.toLong() * RfidService.TAMANHO_PAGINA).coerceAtMost(todas.size.toLong()).toInt()
            PageImpl(todas.subList(inicio, (inicio + RfidService.TAMANHO_PAGINA).coerceAtMost(todas.size)), pedido, todas.size.toLong())
        }
    }

    /** Todas as não vistas há mais de [dias] dias, na ordem da tela (exportação). */
    fun naoVistasTodas(dias: Int): List<EtiquetaVista> {
        val diasValidos = diasValidos(dias)
        val limite = LocalDateTime.now(relogio).minusDays(diasValidos.toLong())
        val resultado = etiquetasComUltimaVez(null)
            .filter { it.ultimaVez == null || it.ultimaVez.isBefore(limite) }
            .sortedWith(compareBy(nullsFirst(naturalOrder<LocalDateTime>())) { it.ultimaVez })

        log.debug("Relatório de não vistas há {} dias: {} etiqueta(s)", diasValidos, resultado.size)
        return resultado
    }

    private fun data(valor: Any?): LocalDateTime? = when (valor) {
        null -> null
        is LocalDateTime -> valor
        is Timestamp -> valor.toLocalDateTime() // consulta nativa
        else -> error("Data em formato inesperado: ${valor::class.simpleName}")
    }

    companion object {
        const val DIAS_PADRAO = 30
        const val DIAS_MAXIMO = 3650

        /** Dias do filtro entre 1 e 10 anos (valor fora disso vira o limite mais próximo). */
        fun diasValidos(dias: Int): Int = dias.coerceIn(1, DIAS_MAXIMO)
    }
}
