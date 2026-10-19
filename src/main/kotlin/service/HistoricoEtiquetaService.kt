package org.example.service

import org.example.model.AcaoHistorico
import org.example.model.HistoricoEtiquetaEntity
import org.example.model.TagEntity
import org.example.repository.HistoricoEtiquetaRepository
import org.example.repository.MarcaRepository
import org.example.repository.ModeloRepository
import org.example.repository.TipoEquipamentoRepository
import org.example.security.OperadorNoColetor
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/** Os campos de uma etiqueta num momento, com tipo/marca/modelo pelo NOME (legível mesmo se o catálogo mudar). */
data class RetratoEtiqueta(val campos: LinkedHashMap<String, String?>) {
    /** "Código interno: TI-1 · Patrimônio: P-1 · …" (só os preenchidos). */
    fun resumo(): String = campos.filterValues { !it.isNullOrBlank() }.entries.joinToString(" · ") { "${it.key}: ${it.value}" }
}

/**
 * Histórico do cadastro de etiquetas. Grava SEMPRE dentro da transação de quem altera a etiqueta
 * (Propagation.MANDATORY): se a alteração falhar, o histórico some junto; se o histórico falhar, a alteração
 * também não passa. Quem fez vem da sessão: na web, o email do usuário; no coletor, o operador e o coletor.
 */
@Service
class HistoricoEtiquetaService(
    private val repo: HistoricoEtiquetaRepository,
    private val tipos: TipoEquipamentoRepository,
    private val marcas: MarcaRepository,
    private val modelos: ModeloRepository,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun retrato(tag: TagEntity): RetratoEtiqueta = RetratoEtiqueta(
        linkedMapOf(
            "Código interno" to tag.codigoInterno,
            "Patrimônio" to tag.patrimonio,
            "Nº de série" to tag.numeroSerie,
            "Tipo" to tag.tipoEquipamentoId?.let { id -> tipos.findById(id).map { it.nome }.orElse("#$id") },
            "Marca" to tag.marcaId?.let { id -> marcas.findById(id).map { it.nome }.orElse("#$id") },
            "Modelo" to tag.modeloId?.let { id -> modelos.findById(id).map { it.nome }.orElse("#$id") }
        )
    )

    @Transactional(propagation = Propagation.MANDATORY)
    fun registrarCadastro(tag: TagEntity) {
        val (usuario, origem) = autor()
        salvar(tag.id, tag.tagReal, AcaoHistorico.CADASTRO, null, null, retrato(tag).resumo(), usuario, origem)
    }

    /** Uma linha por campo que realmente mudou. Devolve os campos alterados (vazio = nada mudou). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun registrarAlteracao(tag: TagEntity, antes: RetratoEtiqueta): List<String> {
        val depois = retrato(tag)
        val (usuario, origem) = autor()
        val mudaram = antes.campos.keys.filter { normalizar(antes.campos[it]) != normalizar(depois.campos[it]) }

        mudaram.forEach { campo ->
            salvar(tag.id, tag.tagReal, AcaoHistorico.ALTERACAO, campo, antes.campos[campo], depois.campos[campo], usuario, origem)
        }
        return mudaram
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun registrarExclusao(tagId: Long, tagReal: String, antes: RetratoEtiqueta) {
        val (usuario, origem) = autor()
        salvar(tagId, tagReal, AcaoHistorico.EXCLUSAO, null, antes.resumo(), null, usuario, origem)
    }

    fun historico(tagReal: String): List<HistoricoEtiquetaEntity> = repo.findByTagRealOrderByMomentoDescIdDesc(tagReal.trim())

    fun recentes(): List<HistoricoEtiquetaEntity> = repo.findTop200ByOrderByMomentoDescIdDesc()

    /** Quem fez e por onde: ("ana@x.com", "Web") ou ("Olga Operadora", "Coletor coletor-01"). */
    fun autor(): Pair<String, String> {
        val auth = SecurityContextHolder.getContext().authentication
        val principal = auth?.principal
        return when {
            principal is OperadorNoColetor -> (principal.operador?.nome ?: "sem operador") to "Coletor ${principal.dispositivo}"
            auth != null && auth.isAuthenticated -> auth.name to "Web"
            else -> "sistema" to "Sistema"
        }
    }

    private fun salvar(
        tagId: Long?, tagReal: String, acao: AcaoHistorico, campo: String?,
        antigo: String?, novo: String?, usuario: String, origem: String
    ) {
        repo.save(
            HistoricoEtiquetaEntity(
                tagId = tagId, tagReal = tagReal, acao = acao, campo = campo,
                valorAntigo = antigo?.take(TAMANHO_VALOR), valorNovo = novo?.take(TAMANHO_VALOR),
                usuario = usuario.take(255), origem = origem.take(120), momento = LocalDateTime.now(relogio)
            )
        )
        log.debug("Histórico da etiqueta {}: {} {} por {} ({})", tagReal, acao.rotulo, campo ?: "", usuario, origem)
    }

    private fun normalizar(valor: String?) = valor?.trim().orEmpty()

    private companion object {
        const val TAMANHO_VALOR = 500
    }
}
