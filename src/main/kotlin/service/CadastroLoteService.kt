package org.example.service

import org.example.model.AreaAuditoria
import org.example.repository.TagNaoCadastradaRepository
import org.example.request.CadastroRequest
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Numeração em sequência para o lote: prefixo + número com os zeros do início ("GER-" e "001" → GER-001, GER-002...).
 * O número de dígitos é o do [inicio] digitado.
 */
data class Sequencia(val prefixo: String, val inicio: String) {
    init {
        require(inicio.isNotEmpty() && inicio.length <= 12 && inicio.all(Char::isDigit)) { "O número inicial precisa ter só dígitos (até 12), como 1 ou 001" }
        require(prefixo.length <= 100) { "O prefixo pode ter no máximo 100 caracteres" }
    }

    fun valor(i: Int): String = prefixo + (inicio.toLong() + i).toString().padStart(inicio.length, '0')

    companion object {
        /** Prefixo e início vindos da tela; os dois vazios = sem sequência. */
        fun de(prefixo: String?, inicio: String?): Sequencia? {
            val p = prefixo?.trim().orEmpty()
            val i = inicio?.trim().orEmpty()
            if (p.isEmpty() && i.isEmpty()) return null
            return Sequencia(p, i.ifEmpty { "1" })
        }
    }
}

/**
 * Cadastro em lote das etiquetas pendentes (1.29.0): as etiquetas escolhidas recebem o mesmo tipo, marca, modelo, local e
 * situação, com o código interno (obrigatório) e o patrimônio (opcional) em sequência. Tudo ou nada: se uma etiqueta
 * não puder ser cadastrada (patrimônio repetido, já cadastrada...), nenhuma é, e a mensagem diz qual e por quê.
 */
@Service
class CadastroLoteService(
    private val rfid: RfidService,
    private val pendentes: TagNaoCadastradaRepository,
    private val auditoria: AuditoriaService? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Só as que ainda estão pendentes, na ordem em que vieram, sem repetir. */
    @Transactional(readOnly = true)
    fun pendentesDentre(tags: Collection<String>): List<String> {
        val unicas = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (unicas.isEmpty()) return emptyList()
        val ainda = pendentes.jaPendentes(unicas).toSet()
        return unicas.filter { it in ainda }
    }

    /** Todas as pendentes, das mais antigas para as mais novas (até [MAXIMO]). */
    @Transactional(readOnly = true)
    fun todasPendentes(): List<String> =
        pendentes.findAll(org.springframework.data.domain.Sort.by("dataHora", "id")).map { it.tag }.take(MAXIMO)

    @Transactional
    fun cadastrar(tags: List<String>, base: CadastroRequest, codigo: Sequencia?, patrimonio: Sequencia?): Int {
        require(tags.isNotEmpty()) { "Escolha pelo menos uma etiqueta pendente" }
        require(tags.size <= MAXIMO) { "No máximo $MAXIMO etiquetas por lote" }
        require(tags.distinct().size == tags.size) { "Há etiquetas repetidas no lote" }
        requireNotNull(codigo) { "Informe o código interno inicial (obrigatório)" }

        tags.forEachIndexed { i, tag ->
            val pedido = base.copy(tag = tag, codigoInterno = codigo.valor(i), patrimonio = patrimonio?.valor(i) ?: "", versao = null)
            try {
                rfid.cadastrarTag(pedido)
            } catch (e: IllegalArgumentException) {
                log.warn("Cadastro em lote desfeito na etiqueta {} ({} de {}): {}", tag, i + 1, tags.size, e.message)
                throw IllegalArgumentException("Etiqueta $tag (${i + 1}ª do lote): ${e.message}. Nenhuma etiqueta foi cadastrada.")
            }
        }

        val faixa = "códigos ${codigo.valor(0)} a ${codigo.valor(tags.size - 1)}" +
            (patrimonio?.let { ", patrimônios ${it.valor(0)} a ${it.valor(tags.size - 1)}" } ?: "")
        log.info("Cadastro em lote de {} etiqueta(s) pendente(s) por {}: {}", tags.size,
            SecurityContextHolder.getContext().authentication?.name ?: "?", faixa)
        auditoria?.registrar(AreaAuditoria.CATALOGO, "Etiquetas cadastradas em lote", "${tags.size} etiqueta(s)", faixa)
        return tags.size
    }

    companion object {
        const val MAXIMO = 500
    }
}
