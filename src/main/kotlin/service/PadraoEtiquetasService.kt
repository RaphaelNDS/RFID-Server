package org.example.service

import org.example.model.AreaAuditoria
import org.example.repository.TagNaoCadastradaRepository
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicLong

/** O padrão das etiquetas da empresa: começos aceitos e tamanhos aceitos (vazio = qualquer). */
data class PadraoEpc(val prefixos: List<String>, val tamanhos: List<Int>) {
    val ativo: Boolean get() = prefixos.isNotEmpty() || tamanhos.isNotEmpty()

    fun aceita(epc: String): Boolean =
        (prefixos.isEmpty() || prefixos.any { epc.startsWith(it, ignoreCase = true) }) &&
            (tamanhos.isEmpty() || epc.length in tamanhos)

    val descricao: String get() = listOfNotNull(
        prefixos.takeIf { it.isNotEmpty() }?.let { "começando com " + it.joinToString(" ou ") },
        tamanhos.takeIf { it.isNotEmpty() }?.let { "com " + it.joinToString(" ou ") + " caracteres" }
    ).joinToString(", ").ifEmpty { "qualquer etiqueta" }
}

/** O que foi ignorado desde que o servidor subiu (fica só em memória: é para conferir se o padrão está certo). */
data class EstrangeirasIgnoradas(val total: Long, val desde: LocalDateTime, val ultimas: List<String>)

/**
 * Padrão das etiquetas da empresa (1.31.0). Com o padrão configurado (Administração › Personalizar), a etiqueta lida
 * fora dele não vira pendente: é de outra coisa no ambiente (outra empresa, embalagem, teste). Só as pendentes mudam:
 * a etiqueta fora do padrão ainda pode ser cadastrada à mão, e a que já está cadastrada é lida normalmente.
 */
@Service
class PadraoEtiquetasService(
    private val config: ConfiguracaoSistemaService,
    private val pendentes: TagNaoCadastradaRepository,
    private val relogio: Clock,
    private val auditoria: AuditoriaService? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val ignoradas = AtomicLong()
    private val desde: LocalDateTime = LocalDateTime.now(relogio)
    private val ultimas = ArrayDeque<String>()

    fun padrao(): PadraoEpc = PadraoEpc(
        prefixos = config.valor(CHAVE_PREFIXOS)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
        tamanhos = config.valor(CHAVE_TAMANHOS)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
    )

    /** Fora do padrão: conta, guarda entre as últimas e devolve true (quem chama não grava a pendente). */
    fun estrangeira(epc: String): Boolean {
        val p = padrao()
        if (!p.ativo || p.aceita(epc)) return false
        ignoradas.incrementAndGet()
        synchronized(ultimas) {
            ultimas.remove(epc)
            ultimas.addFirst(epc)
            while (ultimas.size > 10) ultimas.removeLast()
        }
        log.debug("Etiqueta {} fora do padrão da empresa ({}): não vira pendente", epc, p.descricao)
        return true
    }

    fun ignoradas(): EstrangeirasIgnoradas = EstrangeirasIgnoradas(ignoradas.get(), desde, synchronized(ultimas) { ultimas.toList() })

    /** Quantas das pendentes de agora estão fora do [padrao]. */
    @Transactional(readOnly = true)
    fun pendentesForaDo(padrao: PadraoEpc): Int = if (!padrao.ativo) 0 else pendentes.findAll().count { !padrao.aceita(it.tag) }

    /**
     * Grava o padrão (texto da tela: "E280, 3034" e "24"). Começos só com 0-9 e A-F (o EPC é hexadecimal); tamanhos de 4
     * a 128. Os dois vazios desligam o padrão. Com [limparPendentes], apaga as pendentes que já estão fora dele.
     */
    @Transactional
    fun definir(prefixosTexto: String?, tamanhosTexto: String?, limparPendentes: Boolean): Pair<PadraoEpc, Int> {
        val prefixos = prefixosTexto.orEmpty().split(',', ';', ' ').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
        prefixos.forEach {
            require(it.length <= 32 && it.all { c -> c in '0'..'9' || c in 'A'..'F' }) { "Começo inválido: $it (use só 0 a 9 e A a F, até 32)" }
        }
        val tamanhos = tamanhosTexto.orEmpty().split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }.map {
            it.toIntOrNull()?.takeIf { n -> n in 4..128 } ?: throw IllegalArgumentException("Tamanho inválido: $it (de 4 a 128 caracteres)")
        }.distinct().sorted()
        require(prefixos.size <= 20 && tamanhos.size <= 10) { "No máximo 20 começos e 10 tamanhos" }

        config.gravarOuApagar(CHAVE_PREFIXOS, prefixos.joinToString(",").ifEmpty { null })
        config.gravarOuApagar(CHAVE_TAMANHOS, tamanhos.joinToString(",").ifEmpty { null })
        val novo = PadraoEpc(prefixos, tamanhos)

        var apagadas = 0
        if (limparPendentes && novo.ativo) {
            val fora = pendentes.findAll().filter { !novo.aceita(it.tag) }
            pendentes.deleteAllInBatch(fora)
            apagadas = fora.size
        }
        val quem = SecurityContextHolder.getContext().authentication?.name ?: "?"
        log.info("Padrão das etiquetas da empresa alterado por {}: {}{}", quem, novo.descricao,
            if (apagadas > 0) "; $apagadas pendente(s) fora do padrão apagada(s)" else "")
        auditoria?.registrar(AreaAuditoria.CATALOGO, "Padrão das etiquetas", novo.descricao,
            if (apagadas > 0) "$apagadas pendente(s) fora do padrão apagada(s)" else null)
        return novo to apagadas
    }

    companion object {
        const val CHAVE_PREFIXOS = "epc.prefixos"
        const val CHAVE_TAMANHOS = "epc.tamanhos"
    }
}
