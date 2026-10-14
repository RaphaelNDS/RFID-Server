package org.example.service

import org.example.model.ErroColetorEntity
import org.example.repository.ErroColetorRepository
import org.example.util.MascaraSensivel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Um erro como chega do app (POST /api/android/erros). */
data class ErroRecebido(
    val id: String = "",
    val momento: OffsetDateTime? = null,
    val tipo: String = "",
    val mensagem: String = "",
    val detalhe: String? = null,
    val ocorrencias: Int = 1,
    val versaoApp: String? = null,
    val codigoVersao: Int? = null,
    val operador: String? = null
)

data class ResultadoErros(val recebidos: Int, val novos: Int, val descartados: Int)

/**
 * Erros do app nos coletores: travamentos (FALHA_GERAL) e falhas relevantes que o app registra e envia quando
 * há rede. Tudo que vem do coletor é conferido e cortado; texto passa pela MascaraSensivel de novo.
 *
 * Contra inundação (app em loop de travamento): no máximo [MAXIMO_POR_ENVIO] por envio e [limitePorDia] por
 * coletor por dia; acima disso descarta, com um aviso no log por coletor por hora (não por envio). O app já
 * agrupa os repetidos (ocorrencias).
 */
@Service
class ErroColetorService(
    private val repo: ErroColetorRepository,
    private val relogio: Clock,
    @Value("\${rfid.coletores.erros-por-dia:500}") private val limitePorDia: Int,
    @Value("\${rfid.coletores.dias-erros:90}") private val diasRetencao: Long
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // Coletor → (dia, gravados no dia); coletor → última hora em que o descarte foi avisado
    private val gravadosNoDia = ConcurrentHashMap<String, Pair<LocalDate, Int>>()
    private val avisoDescarte = ConcurrentHashMap<String, LocalDateTime>()

    @Transactional
    fun receber(coletor: String, itens: List<ErroRecebido>): ResultadoErros {
        require(itens.size <= MAXIMO_POR_ENVIO) { "No máximo $MAXIMO_POR_ENVIO erros por envio (recebidos ${itens.size})" }

        val agora = LocalDateTime.now(relogio)
        var novos = 0
        var descartados = 0
        var invalidos = 0
        val porTipo = linkedMapOf<String, Int>()
        val versoes = linkedSetOf<String>()

        for (item in itens) {
            val id = item.id.trim().takeIf { valido(it) } ?: run { invalidos++; null } ?: continue
            if (repo.existsById(id)) continue   // reenvio: já gravado

            if (!dentroDoLimite(coletor, agora)) {
                descartados++
                continue
            }

            val tipo = item.tipo.uppercase().filter { it in 'A'..'Z' || it == '_' }.take(30).ifEmpty { "DESCONHECIDO" }
            val erro = ErroColetorEntity(
                id = id,
                coletor = coletor,
                momento = momentoDoColetor(item.momento, agora),
                recebidoEm = agora,
                tipo = tipo,
                mensagem = texto(item.mensagem, 500).ifEmpty { "(sem mensagem)" },
                detalhe = item.detalhe?.let { texto(it, 8000, manterLinhas = true) }?.ifEmpty { null },
                ocorrencias = item.ocorrencias.coerceIn(1, 1_000_000),
                versaoApp = item.versaoApp?.let { texto(it, 50) }?.ifEmpty { null },
                codigoVersao = item.codigoVersao?.takeIf { it > 0 },
                operador = item.operador?.let { texto(it, 255) }?.ifEmpty { null }
            )
            repo.save(erro)
            novos++
            porTipo.merge(tipo, erro.ocorrencias, Int::plus)
            erro.versaoApp?.let { versoes += it }

            if (tipo == TIPO_TRAVAMENTO) {
                log.warn("App do coletor {} travou em {} (versão {}): {}", coletor, erro.momento, erro.versaoApp ?: "?", erro.mensagem.lineSequence().first())
            }
        }

        if (novos > 0) {
            log.info("Coletor {} enviou {} erro(s) do app (versão {}): {}", coletor, novos,
                versoes.joinToString().ifEmpty { "?" }, porTipo.entries.joinToString { "${it.key} ${it.value}" })
        }
        if (invalidos > 0) log.warn("Coletor {} enviou {} erro(s) com identificador inválido: ignorado(s)", coletor, invalidos)
        if (descartados > 0) avisarDescarte(coletor, descartados, agora)

        return ResultadoErros(itens.size, novos, descartados)
    }

    /** Para os testes de integração: o banco volta ao estado inicial a cada teste, a memória não. */
    internal fun esquecerContagens() {
        gravadosNoDia.clear()
        avisoDescarte.clear()
    }

    private fun valido(id: String) = id.length == 36 && runCatching { UUID.fromString(id) }.isSuccess

    @Synchronized
    private fun dentroDoLimite(coletor: String, agora: LocalDateTime): Boolean {
        val hoje = agora.toLocalDate()
        val (dia, quantos) = gravadosNoDia[coletor]?.takeIf { it.first == hoje } ?: (hoje to 0)
        if (quantos >= limitePorDia) return false
        gravadosNoDia[coletor] = dia to quantos + 1
        return true
    }

    private fun avisarDescarte(coletor: String, descartados: Int, agora: LocalDateTime) {
        val ultimo = avisoDescarte[coletor]
        if (ultimo != null && Duration.between(ultimo, agora) < Duration.ofHours(1)) return
        avisoDescarte[coletor] = agora
        log.warn("Coletor {} passou do limite de {} erros por dia: {} erro(s) descartado(s) (app em loop de falha?). Próximo aviso em 1 h",
            coletor, limitePorDia, descartados)
    }

    // Sem caracteres de controle (exceto quebra de linha/tab no detalhe), sem dados sensíveis, cortado
    private fun texto(bruto: String, maximo: Int, manterLinhas: Boolean = false): String {
        val semControle = bruto.filter { !it.isISOControl() || (manterLinhas && (it == '\n' || it == '\t')) }
        return MascaraSensivel.limpar(semControle).trim().take(maximo)
    }

    // Hora do relógio do coletor; sem ela, ou no futuro (relógio adiantado), vale a de chegada
    private fun momentoDoColetor(momento: OffsetDateTime?, agora: LocalDateTime): LocalDateTime {
        val local = momento?.atZoneSameInstant(ZoneId.systemDefault())?.toLocalDateTime() ?: return agora
        return if (local.isAfter(agora.plusMinutes(5))) agora else local
    }

    /** Erros por coletor nas últimas 24 h: conta registros (um erro agrupado com várias ocorrências conta 1). */
    fun contarUltimas24h(): Map<String, Long> =
        repo.contarPorColetorDesde(LocalDateTime.now(relogio).minusHours(24))
            .associate { (it[0] as String) to (it[1] as Number).toLong() }

    /** Os mais recentes do coletor, opcionalmente só de uma versão do app. */
    fun recentes(coletor: String, versaoApp: String? = null): List<ErroColetorEntity> =
        repo.findTop200ByColetorOrderByMomentoDesc(coletor)
            .filter { versaoApp.isNullOrBlank() || it.versaoApp == versaoApp }

    /** Versões do app que aparecem nos erros do coletor (filtro da tela). */
    fun versoesComErro(coletor: String): List<String> =
        repo.findTop200ByColetorOrderByMomentoDesc(coletor).mapNotNull { it.versaoApp }.distinct()

    // Retenção: todo dia às 04:15 (longe do backup das 02:00 e da retenção das leituras às 03:30)
    @Scheduled(cron = "\${rfid.coletores.erros-retencao-cron:0 15 4 * * *}")
    @Transactional
    fun apagarAntigos() {
        runCatching {
            val apagados = repo.apagarRecebidosAntesDe(LocalDateTime.now(relogio).minusDays(diasRetencao))
            if (apagados > 0) log.info("Retenção de erros dos coletores: {} erro(s) com mais de {} dias apagado(s)", apagados, diasRetencao)
        }.onFailure { log.error("Retenção de erros dos coletores falhou: tenta de novo amanhã", it) }
    }

    companion object {
        const val MAXIMO_POR_ENVIO = 50
        const val TIPO_TRAVAMENTO = "FALHA_GERAL"
    }
}
