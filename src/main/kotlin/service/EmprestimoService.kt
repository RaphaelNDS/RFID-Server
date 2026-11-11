package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.EmprestimoEntity
import org.example.model.EmprestimoItemEntity
import org.example.model.SituacaoBem
import org.example.repository.EmprestimoItemRepository
import org.example.repository.EmprestimoRepository
import org.example.repository.ResponsavelRepository
import org.example.repository.TagRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** O que a tela de empréstimo pede. [codigos]: etiquetas (EPC) ou patrimônios, um por item. */
data class PedidoEmprestimo(
    val codigos: List<String>,
    val responsavelId: Long?,
    val previsaoDevolucao: LocalDate?,
    val dataSaida: LocalDate? = null,
    val finalidade: String? = null
)

/** Filtro da lista de empréstimos. */
enum class FiltroEmprestimo(val rotulo: String) {
    ABERTOS("Abertos"),
    ATRASADOS("Atrasados"),
    DEVOLVIDOS("Devolvidos"),
    TODOS("Todos");

    companion object {
        fun de(texto: String?): FiltroEmprestimo = entries.firstOrNull { it.name.equals(texto?.trim(), ignoreCase = true) } ?: ABERTOS
    }
}

/** Um empréstimo na lista: quantos bens, quantos ainda não voltaram e se o prazo venceu. */
data class EmprestimoResumo(val emprestimo: EmprestimoEntity, val quantidade: Int, val pendentes: Int, val diasAtraso: Long) {
    val aberto: Boolean get() = pendentes > 0
    val atrasado: Boolean get() = diasAtraso > 0
}

data class EmprestimoDetalhe(val emprestimo: EmprestimoEntity, val itens: List<EmprestimoItemEntity>, val diasAtraso: Long) {
    val pendentes: List<EmprestimoItemEntity> get() = itens.filter { !it.devolvido }
    val aberto: Boolean get() = pendentes.isNotEmpty()
    val atrasado: Boolean get() = diasAtraso > 0
}

/**
 * Empréstimo e devolução de bens (1.10.0). Um empréstimo numerado leva um ou mais bens para um responsável, com a
 * data de saída e a previsão de devolução; cada bem volta sozinho ou junto com os outros.
 *
 * Regras:
 *  - só sai bem "Em uso" ou "Em estoque" (não baixado, extraviado, em manutenção nem já emprestado);
 *  - na saída o bem fica "Emprestado"; na devolução volta para a situação de antes;
 *  - local e responsável do bem não mudam (o empréstimo é temporário: para mudar de vez, use a transferência);
 *  - enquanto emprestado, o bem não recebe baixa, não é excluído e a situação não muda pela edição (RfidService);
 *  - tudo vai para o histórico de cada bem ("Empréstimo nº X", "Devolução do empréstimo nº X") e para a auditoria.
 */
@Service
class EmprestimoService(
    private val emprestimos: EmprestimoRepository,
    private val itens: EmprestimoItemRepository,
    private val tags: TagRepository,
    private val responsaveis: ResponsavelRepository,
    private val transferencias: TransferenciaService,
    private val historico: HistoricoEtiquetaService,
    private val auditoria: AuditoriaService,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun hoje(): LocalDate = LocalDate.now(relogio)

    /** Acha os bens dos códigos (mesma busca da transferência: EPC ou patrimônio). */
    @Transactional(readOnly = true)
    fun localizar(codigos: Collection<String>): BensLocalizados {
        require(codigos.size <= MAXIMO_BENS) { "No máximo $MAXIMO_BENS bens por empréstimo (informados ${codigos.size})" }
        return transferencias.localizar(codigos)
    }

    @Transactional
    fun emprestar(pedido: PedidoEmprestimo): EmprestimoEntity {
        val localizados = localizar(pedido.codigos)
        require(localizados.bens.isNotEmpty() || localizados.naoEncontrados.isNotEmpty() || localizados.ambiguos.isNotEmpty()) {
            "Informe os bens a emprestar (etiqueta ou patrimônio)"
        }
        require(localizados.naoEncontrados.isEmpty()) { "Não encontrado(s) no cadastro: ${lista(localizados.naoEncontrados)}" }
        require(localizados.ambiguos.isEmpty()) { "Patrimônio de mais de um bem (use a etiqueta): ${lista(localizados.ambiguos)}" }

        val responsavel = pedido.responsavelId?.let { id ->
            responsaveis.findById(id).orElseThrow { IllegalArgumentException("Responsável não encontrado") }
                .also { require(it.ativo) { "O responsável ${it.nome} está desativado" } }
        } ?: throw IllegalArgumentException("Escolha para quem o bem vai (responsável)")
        val hoje = hoje()
        val saida = pedido.dataSaida ?: hoje
        require(!saida.isAfter(hoje)) { "A data de saída não pode ser no futuro" }
        val previsao = pedido.previsaoDevolucao ?: throw IllegalArgumentException("Informe a previsão de devolução")
        require(!previsao.isBefore(saida)) { "A previsão de devolução não pode ser antes da saída" }
        require(!previsao.isAfter(saida.plusYears(PRAZO_MAXIMO_ANOS))) { "A previsão de devolução pode ser de no máximo $PRAZO_MAXIMO_ANOS anos" }
        val finalidade = pedido.finalidade?.trim()?.takeIf { it.isNotEmpty() }
            ?.also { require(it.length <= 500) { "A finalidade pode ter no máximo 500 caracteres" } }

        val entidades = tags.findAllById(localizados.bens.map { it.id }).associateBy { it.id }
        val bens = localizados.bens.map { entidades.getValue(it.id) to it }
        val naoPodem = bens.filter { it.first.situacao !in PODEM_SAIR }
        require(naoPodem.isEmpty()) {
            "Só sai bem Em uso ou Em estoque: " + lista(naoPodem.map { "${rotulo(it.second)} (${it.first.situacao.rotulo})" })
        }

        val usuario = historico.autor().first
        val emprestimo = emprestimos.save(EmprestimoEntity(
            responsavelId = responsavel.id, responsavel = responsavel.descricao.take(255), dataSaida = saida,
            previsaoDevolucao = previsao, finalidade = finalidade, registradoPor = usuario
        ))
        bens.forEach { (tag, bem) ->
            val antes = historico.retrato(tag)
            itens.save(EmprestimoItemEntity(
                emprestimoId = emprestimo.id, tagId = tag.id, tagReal = tag.tagReal, patrimonio = bem.patrimonio,
                descricao = bem.descricao?.take(255), situacaoAnterior = tag.situacao
            ))
            tag.situacao = SituacaoBem.EMPRESTADO
            tags.save(tag)
            historico.registrarAlteracao(tag, antes, "Empréstimo nº ${emprestimo.id}")
        }
        // Grava já: o índice único (um empréstimo aberto por bem) recusa aqui, dentro da transação, se outra tela
        // emprestou o mesmo bem ao mesmo tempo
        itens.flush()

        log.info("Empréstimo nº {} registrado por {}: {} bem(ns) para {}, devolução prevista em {}{}", emprestimo.id, usuario,
            bens.size, responsavel.nome, previsao, finalidade?.let { "; finalidade: $it" } ?: "")
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Empréstimo de bens", "Empréstimo nº ${emprestimo.id}",
            "${bens.size} bem(ns) para ${responsavel.nome}, devolução prevista em ${org.example.util.FormatoBr.data(previsao)}")
        return emprestimo
    }

    /**
     * Devolve os itens [itemIds] do empréstimo (vazio = todos os que faltam). Cada bem volta para a situação de antes
     * do empréstimo, se ainda estiver "Emprestado" (se alguém mudou a situação nesse meio tempo, ela fica).
     */
    @Transactional
    fun devolver(id: Long, itemIds: Collection<Long>, data: LocalDate?, condicao: String?): List<EmprestimoItemEntity> {
        val emprestimo = emprestimos.findById(id).orElseThrow { NoSuchElementException("Empréstimo nº $id não encontrado") }
        val pendentes = itens.findAllByEmprestimoIdOrderByIdAsc(id).filter { !it.devolvido }
        require(pendentes.isNotEmpty()) { "Todos os bens deste empréstimo já foram devolvidos" }
        val escolhidos = if (itemIds.isEmpty()) pendentes else pendentes.filter { it.id in itemIds }
        require(escolhidos.isNotEmpty()) { "Marque os bens que voltaram" }
        val hoje = hoje()
        val dia = data ?: hoje
        require(!dia.isAfter(hoje)) { "A data da devolução não pode ser no futuro" }
        require(!dia.isBefore(emprestimo.dataSaida)) { "A data da devolução não pode ser antes da saída (${org.example.util.FormatoBr.data(emprestimo.dataSaida)})" }
        val obs = condicao?.trim()?.takeIf { it.isNotEmpty() }
            ?.also { require(it.length <= 500) { "A condição na devolução pode ter no máximo 500 caracteres" } }

        val usuario = historico.autor().first
        val bens = tags.findAllById(escolhidos.mapNotNull { it.tagId }).associateBy { it.id }
        escolhidos.forEach { item ->
            item.devolvidoEm = dia
            item.devolvidoPor = usuario
            item.condicaoDevolucao = obs
            itens.save(item)
            val tag = item.tagId?.let(bens::get) ?: return@forEach
            if (tag.situacao == SituacaoBem.EMPRESTADO) {
                val antes = historico.retrato(tag)
                tag.situacao = item.situacaoAnterior
                tags.save(tag)
                historico.registrarAlteracao(tag, antes, "Devolução do empréstimo nº $id")
            }
        }

        val faltam = pendentes.size - escolhidos.size
        log.info("Devolução do empréstimo nº {} registrada por {}: {} bem(ns) em {}{}{}", id, usuario, escolhidos.size, dia,
            if (faltam > 0) "; ainda falta(m) $faltam" else "; empréstimo encerrado", obs?.let { "; condição: $it" } ?: "")
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Devolução de empréstimo", "Empréstimo nº $id",
            "${escolhidos.size} bem(ns) devolvido(s)" + if (faltam > 0) ", falta(m) $faltam" else ", empréstimo encerrado")
        return escolhidos
    }

    @Transactional(readOnly = true)
    fun listar(filtro: FiltroEmprestimo, busca: String?, pagina: Int): Page<EmprestimoResumo> {
        val hoje = hoje()
        val situacao = if (filtro == FiltroEmprestimo.TODOS) "" else filtro.name
        val texto = busca?.trim().orEmpty().take(255)
        val resultado = RfidService.paginaValida(pagina) { emprestimos.buscar(texto, situacao, hoje, PageRequest.of(it, POR_PAGINA)) }
        val porEmprestimo = if (resultado.isEmpty) emptyMap() else
            itens.findAllByEmprestimoIdIn(resultado.content.map { it.id }).groupBy { it.emprestimoId }
        return resultado.map { e ->
            val lista = porEmprestimo[e.id].orEmpty()
            val pendentes = lista.count { !it.devolvido }
            EmprestimoResumo(e, lista.size, pendentes, if (pendentes > 0) atraso(e, hoje) else 0)
        }
    }

    @Transactional(readOnly = true)
    fun detalhar(id: Long): EmprestimoDetalhe {
        val e = emprestimos.findById(id).orElseThrow { NoSuchElementException("Empréstimo nº $id não encontrado") }
        val lista = itens.findAllByEmprestimoIdOrderByIdAsc(id)
        return EmprestimoDetalhe(e, lista, if (lista.any { !it.devolvido }) atraso(e, hoje()) else 0)
    }

    /** O empréstimo aberto do bem, para a edição da etiqueta (null = não está emprestado). */
    @Transactional(readOnly = true)
    fun abertoDoBem(tagId: Long): EmprestimoDetalhe? =
        itens.findFirstByTagIdAndDevolvidoEmIsNull(tagId)?.let { detalhar(it.emprestimoId) }

    @Transactional(readOnly = true)
    fun contarAtrasados(): Long = emprestimos.contarAtrasados(hoje())

    private fun atraso(e: EmprestimoEntity, hoje: LocalDate): Long =
        ChronoUnit.DAYS.between(e.previsaoDevolucao, hoje).coerceAtLeast(0)

    private fun rotulo(b: BemTransferivel) = b.patrimonio ?: b.codigoInterno ?: b.tag

    private fun lista(itens: List<String>) = itens.take(10).joinToString(", ") + if (itens.size > 10) " e mais ${itens.size - 10}" else ""

    companion object {
        const val MAXIMO_BENS = 200
        const val POR_PAGINA = 50
        const val PRAZO_MAXIMO_ANOS = 5L
        val PODEM_SAIR = setOf(SituacaoBem.EM_USO, SituacaoBem.EM_ESTOQUE)
    }
}
