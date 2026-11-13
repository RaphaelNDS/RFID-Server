package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.InventarioCiclicoEntity
import org.example.model.StatusInventario
import org.example.repository.InventarioCiclicoRepository
import org.example.repository.InventarioRepository
import org.example.repository.TagRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

enum class SituacaoCiclo(val rotulo: String) {
    NUNCA("Nunca inventariado"),
    ATRASADO("Atrasado"),
    VENCE_ESTA_SEMANA("Vence esta semana"),
    EM_DIA("Em dia")
}

/** Um local no ciclo: quantos bens, o último inventário finalizado, quando vence e se entra na contagem desta semana. */
data class LocalCiclo(
    val local: OpcaoLocal, val bens: Long, val ultimo: LocalDateTime?, val inventarioId: String?,
    val venceEm: LocalDate?, val situacao: SituacaoCiclo, val estaSemana: Boolean
)

data class PlanoCiclico(
    val config: InventarioCiclicoEntity,
    val hoje: LocalDate,
    val inicioSemana: LocalDate,
    val fimSemana: LocalDate,
    val locais: List<LocalCiclo>,
    /** Quantos bens contar por semana para fechar o ciclo (o total dividido pelas semanas). */
    val cota: Long
) {
    val estaSemana: List<LocalCiclo> get() = locais.filter { it.estaSemana }
    val bensEstaSemana: Long get() = estaSemana.sumOf { it.bens }
    val totalBens: Long get() = locais.sumOf { it.bens }
    val bensEmDia: Long get() = locais.filter { it.situacao == SituacaoCiclo.EM_DIA || it.situacao == SituacaoCiclo.VENCE_ESTA_SEMANA }.sumOf { it.bens }
    val percentualEmDia: Int get() = if (totalBens == 0L) 100 else (bensEmDia * 100 / totalBens).toInt()
    val atrasados: Int get() = locais.count { it.situacao == SituacaoCiclo.ATRASADO || it.situacao == SituacaoCiclo.NUNCA }
}

/**
 * Inventário cíclico (1.27.0). Em vez de parar a operação para contar tudo, cada local com bens precisa ser inventariado
 * (pelo coletor, como sempre) pelo menos uma vez a cada [InventarioCiclicoEntity.semanas] semanas. A semana pede o que
 * está atrasado ou nunca foi contado, o que vence nela e, se ainda faltar para a cota semanal (o acervo dividido pelas
 * semanas), os que vencem mais cedo. Um inventário finalizado no local zera a contagem dele.
 */
@Service
class InventarioCiclicoService(
    private val config: InventarioCiclicoRepository,
    private val inventarios: InventarioRepository,
    private val tags: TagRepository,
    private val cadastros: PatrimonioCadastrosService,
    private val historico: HistoricoEtiquetaService,
    private val auditoria: AuditoriaService,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun configuracao(): InventarioCiclicoEntity = config.findById(1).orElseGet { InventarioCiclicoEntity() }

    @Transactional
    fun configurar(ativo: Boolean, semanas: Int?): InventarioCiclicoEntity {
        val s = semanas ?: throw IllegalArgumentException("Informe o ciclo em semanas")
        require(s in 1..52) { "O ciclo vai de 1 a 52 semanas" }
        val c = configuracao()
        c.ativo = ativo
        c.semanas = s
        c.alteradoPor = historico.autor().first
        c.alteradoEm = LocalDateTime.now(relogio)
        config.save(c)
        log.info("Inventário cíclico {} por {}: cada local a cada {} semana(s)", if (ativo) "ligado" else "desligado", c.alteradoPor, s)
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Inventário cíclico", if (ativo) "Ligado" else "Desligado", "A cada $s semana(s)")
        return c
    }

    @Transactional(readOnly = true)
    fun plano(): PlanoCiclico {
        val c = configuracao()
        val hoje = LocalDate.now(relogio)
        val inicio = hoje.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val fim = inicio.plusDays(6)
        val cicloDias = c.semanas * 7L
        val bensPorLocal = tags.contarPorLocal().associate { (it[0] as Number).toLong() to (it[1] as Number).toLong() }
        val base = cadastros.opcoesDeLocal().filter { (bensPorLocal[it.id] ?: 0) > 0 }.map { l ->
            val ultimo = inventarios.findFirstByLocalIdAndStatusOrderByFinalizadoEmDesc(l.id, StatusInventario.FINALIZADO)
            val quando = ultimo?.finalizadoEm
            val vence = quando?.toLocalDate()?.plusDays(cicloDias)
            val situacao = when {
                vence == null -> SituacaoCiclo.NUNCA
                vence.isBefore(hoje) -> SituacaoCiclo.ATRASADO
                !vence.isAfter(fim) -> SituacaoCiclo.VENCE_ESTA_SEMANA
                else -> SituacaoCiclo.EM_DIA
            }
            LocalCiclo(l, bensPorLocal[l.id] ?: 0, quando, ultimo?.id, vence, situacao, false)
        }
        val total = base.sumOf { it.bens }
        val cota = if (total == 0L) 0 else (total + c.semanas - 1) / c.semanas
        // Esta semana: o obrigatório (nunca, atrasado, vence nela) e, até a cota, os que vencem mais cedo
        val ordem = compareBy<LocalCiclo>({ it.situacao.ordinal }, { it.venceEm ?: LocalDate.MIN }, { it.local.descricao.lowercase() })
        val obrigatorios = base.filter { it.situacao != SituacaoCiclo.EM_DIA }.map { it.local.id }.toMutableSet()
        var soma = base.filter { it.local.id in obrigatorios }.sumOf { it.bens }
        // "Em dia" contado nesta semana não volta para a lista
        base.filter { it.situacao == SituacaoCiclo.EM_DIA && it.ultimo?.toLocalDate()?.isBefore(inicio) == true }
            .sortedBy { it.venceEm }
            .forEach { if (soma < cota) { obrigatorios += it.local.id; soma += it.bens } }
        val locais = base.map { it.copy(estaSemana = it.local.id in obrigatorios) }.sortedWith(ordem)
        return PlanoCiclico(c, hoje, inicio, fim, locais, cota)
    }

    /** Para o painel do dia: quantos locais contar nesta semana (null = ciclo desligado). */
    @Transactional(readOnly = true)
    fun locaisDaSemana(): Int? = if (!configuracao().ativo) null else plano().estaSemana.size

}
