package org.example.util

import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * A regra da hora informada pelo coletor (X-Momento, lidoEm das leituras e do inventário, hora dos erros do app), num
 * lugar só (1.5.2). Antes ela estava repetida em seis lugares, com três constantes de 5 min, e a 1.4.4 precisou mudar
 * três deles juntos.
 *
 * A ação pode ter sido feita offline e chegar depois: vale a hora do coletor, no fuso do servidor. Mas o relógio do
 * coletor pode estar adiantado, e uma hora "do futuro" deixaria uma ação usar a sessão além do horário real: acima de
 * [TOLERANCIA] no futuro, a hora não vale e quem chama usa a do recebimento.
 */
object HoraColetor {

    /** Diferença de relógio aceita entre o coletor e o servidor. */
    val TOLERANCIA: Duration = Duration.ofMinutes(5)

    /** A hora do coletor no fuso do servidor, ou null quando não veio ou está mais de [TOLERANCIA] no futuro. */
    fun aceita(hora: OffsetDateTime?, agora: LocalDateTime): LocalDateTime? =
        hora?.let(::noFusoDoServidor)?.takeUnless { noFuturo(it, agora) }

    /** Mais de [TOLERANCIA] depois de [agora]: relógio do coletor adiantado. */
    fun noFuturo(local: LocalDateTime, agora: LocalDateTime): Boolean = local.isAfter(agora.plus(TOLERANCIA))

    fun noFusoDoServidor(hora: OffsetDateTime): LocalDateTime = hora.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
}
