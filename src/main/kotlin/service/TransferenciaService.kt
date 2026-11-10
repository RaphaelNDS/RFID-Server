package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.SituacaoBem
import org.example.model.StatusInventario
import org.example.model.TagEntity
import org.example.model.TransferenciaEntity
import org.example.model.TransferenciaItemEntity
import org.example.repository.InventarioRepository
import org.example.repository.LocalRepository
import org.example.repository.MarcaRepository
import org.example.repository.ModeloRepository
import org.example.repository.ResponsavelRepository
import org.example.repository.TagRepository
import org.example.repository.TipoEquipamentoRepository
import org.example.repository.TransferenciaItemRepository
import org.example.repository.TransferenciaRepository
import org.example.repository.UnidadeRepository
import org.example.util.normalizarEpc
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/** O que a tela de transferência pede. [codigos]: etiquetas (EPC) ou patrimônios, um por item. */
data class PedidoTransferencia(
    val codigos: List<String>,
    val localId: Long?,
    val responsavelId: Long?,
    val data: LocalDate? = null,
    val solicitante: String? = null,
    val motivo: String? = null,
    val inventarioId: String? = null
)

/** Um bem como está agora (para a tela conferir antes de transferir). */
data class BemTransferivel(
    val id: Long,
    val tag: String,
    val patrimonio: String?,
    val codigoInterno: String?,
    val descricao: String?,
    val local: String?,
    val responsavel: String?,
    val situacao: SituacaoBem
)

/** Os códigos digitados que viraram bens, e os que não acharam (ou acharam mais de um bem). */
data class BensLocalizados(val bens: List<BemTransferivel>, val naoEncontrados: List<String>, val ambiguos: List<String>)

/**
 * Resposta da transferência pelo coletor: o local e a situação atualizados, o número da guia (null quando o bem já
 * estava no local: nada mudou) e [jaEstava].
 */
data class ResultadoTransferenciaColetor(
    val local: String,
    val situacao: String,
    val situacaoRotulo: String,
    val transferencia: Long?,
    val jaEstava: Boolean
)

data class TransferenciaResumo(val transferencia: TransferenciaEntity, val quantidade: Long)

data class TransferenciaDetalhe(val transferencia: TransferenciaEntity, val itens: List<TransferenciaItemEntity>)

/**
 * Transferência de bens entre locais e responsáveis (1.6.1). Grava uma guia numerada com a origem de cada bem no
 * momento, muda o local e/ou o responsável dos bens e põe no histórico de cada um "Transferência nº X". Tudo numa
 * transação só: se um bem falhar, nada é transferido.
 *
 * Regras:
 *  - ao menos o local ou o responsável de destino; o outro fica como está em cada bem;
 *  - bem baixado não é transferido; bem que já está no destino (nada mudaria) é recusado, para a guia não listar
 *    bens que não saíram do lugar;
 *  - bem EXTRAVIADO que muda de local volta para "Em uso": foi achado em algum lugar;
 *  - local e responsável de destino precisam estar ativos.
 */
@Service
class TransferenciaService(
    private val transferencias: TransferenciaRepository,
    private val itens: TransferenciaItemRepository,
    private val tags: TagRepository,
    private val locais: LocalRepository,
    private val unidades: UnidadeRepository,
    private val responsaveis: ResponsavelRepository,
    private val tipos: TipoEquipamentoRepository,
    private val marcas: MarcaRepository,
    private val modelos: ModeloRepository,
    private val inventarios: InventarioRepository,
    private val historico: HistoricoEtiquetaService,
    private val auditoria: AuditoriaService,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Acha os bens dos [codigos] (EPC ou patrimônio, sem repetir). Patrimônio repetido no cadastro (mais de um bem)
     * vai para [BensLocalizados.ambiguos]: use o EPC.
     */
    @Transactional(readOnly = true)
    fun localizar(codigos: Collection<String>): BensLocalizados {
        val limpos = codigos.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(limpos.size <= MAXIMO_BENS) { "No máximo $MAXIMO_BENS bens por transferência (informados ${limpos.size})" }
        val achados = linkedMapOf<Long, TagEntity>()
        val naoEncontrados = mutableListOf<String>()
        val ambiguos = mutableListOf<String>()
        limpos.forEach { codigo ->
            val pelaEtiqueta = tags.findByTagReal(normalizarEpc(codigo)).orElse(null)
            if (pelaEtiqueta != null) { achados[pelaEtiqueta.id] = pelaEtiqueta; return@forEach }
            val pelosPatrimonios = tags.findAllByPatrimonio(codigo)
            when (pelosPatrimonios.size) {
                0 -> naoEncontrados += codigo
                1 -> achados[pelosPatrimonios[0].id] = pelosPatrimonios[0]
                else -> ambiguos += codigo
            }
        }
        return BensLocalizados(descrever(achados.values.toList()), naoEncontrados, ambiguos)
    }

    @Transactional
    fun transferir(pedido: PedidoTransferencia): TransferenciaEntity {
        require(pedido.localId != null || pedido.responsavelId != null) { "Escolha o local ou o responsável de destino" }
        val localizados = localizar(pedido.codigos)
        require(localizados.bens.isNotEmpty() || localizados.naoEncontrados.isNotEmpty() || localizados.ambiguos.isNotEmpty()) {
            "Informe os bens a transferir (etiqueta ou patrimônio)"
        }
        require(localizados.naoEncontrados.isEmpty()) { "Não encontrado(s) no cadastro: ${lista(localizados.naoEncontrados)}" }
        require(localizados.ambiguos.isEmpty()) { "Patrimônio de mais de um bem (use a etiqueta): ${lista(localizados.ambiguos)}" }

        val local = pedido.localId?.let { id ->
            locais.findById(id).orElseThrow { IllegalArgumentException("Local não encontrado") }
                .also { require(it.ativo) { "O local ${it.nome} está desativado" } }
        }
        val responsavel = pedido.responsavelId?.let { id ->
            responsaveis.findById(id).orElseThrow { IllegalArgumentException("Responsável não encontrado") }
                .also { require(it.ativo) { "O responsável ${it.nome} está desativado" } }
        }
        val hoje = LocalDate.now(relogio)
        val data = pedido.data ?: hoje
        require(!data.isAfter(hoje)) { "A data da transferência não pode ser no futuro" }
        val solicitante = opcional(pedido.solicitante, "O solicitante", 160)
        val motivo = opcional(pedido.motivo, "O motivo", 500)
        val inventario = pedido.inventarioId?.takeIf { it.isNotBlank() }?.let { id ->
            inventarios.findById(id).orElseThrow { IllegalArgumentException("Inventário não encontrado") }
                .also { require(it.status == StatusInventario.FINALIZADO) { "O inventário ainda não foi finalizado" } }
        }

        val entidades = tags.findAllById(localizados.bens.map { it.id }).associateBy { it.id }
        val bens = localizados.bens.map { entidades.getValue(it.id) to it }
        val baixados = bens.filter { it.first.baixado }
        require(baixados.isEmpty()) { "Bem baixado não pode ser transferido: ${lista(baixados.map { rotulo(it.second) })}" }
        val semMudanca = bens.filter { (t, _) -> (local == null || t.localId == local.id) && (responsavel == null || t.responsavelId == responsavel.id) }
        require(semMudanca.isEmpty()) { "Já está(ão) no destino: ${lista(semMudanca.map { rotulo(it.second) })}" }

        val nomeLocal = local?.let { l -> l.unidadeId?.let { unidades.findById(it).map { u -> "${u.nome} › ${l.nome}" }.orElse(null) } ?: l.nome }
        val usuario = historico.autor().first
        val guia = transferencias.save(
            TransferenciaEntity(
                data = data, localDestinoId = local?.id, localDestino = nomeLocal,
                responsavelDestinoId = responsavel?.id, responsavelDestino = responsavel?.descricao,
                solicitante = solicitante, motivo = motivo, inventarioId = inventario?.id, registradaPor = usuario
            )
        )

        bens.forEach { (tag, antes) ->
            val retrato = historico.retrato(tag)
            itens.save(
                TransferenciaItemEntity(
                    transferenciaId = guia.id, tagId = tag.id, tagReal = tag.tagReal, patrimonio = antes.patrimonio,
                    descricao = antes.descricao?.take(255), localAnterior = antes.local?.take(255), responsavelAnterior = antes.responsavel?.take(255)
                )
            )
            if (local != null && tag.localId != local.id && tag.situacao == SituacaoBem.EXTRAVIADO) tag.situacao = SituacaoBem.EM_USO
            local?.let { tag.localId = it.id }
            responsavel?.let { tag.responsavelId = it.id }
            tags.save(tag)
            historico.registrarAlteracao(tag, retrato, "Transferência nº ${guia.id}")
        }

        val destino = listOfNotNull(nomeLocal, responsavel?.nome).joinToString(" · ")
        log.info("Transferência nº {} registrada por {}: {} bem(ns) para {}{}{}", guia.id, usuario, bens.size, destino,
            inventario?.let { " (divergências do inventário ${it.id})" } ?: "", motivo?.let { "; motivo: $it" } ?: "")
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Transferência de bens", "Guia nº ${guia.id}",
            "${bens.size} bem(ns) para $destino" + (inventario?.let { " (divergências do inventário)" } ?: ""))
        return guia
    }

    /**
     * Transferência pelo coletor (1.7.0): o operador, no inventário, leva o bem lido para o local do inventário. Só o
     * local muda (o responsável fica). Mesma guia, histórico e auditoria da web; quem registra é o operador.
     *
     * Idempotente: o app reenvia quando a rede cai, e o bem que já está no local responde com [ResultadoTransferenciaColetor.jaEstava]
     * (sem guia nova), em vez de erro. Etiqueta sem cadastro: [NoSuchElementException] (404). Bem baixado:
     * [IllegalStateException] (409). Local desconhecido ou desativado: [IllegalArgumentException] (400).
     */
    @Transactional
    fun transferirPeloColetor(epc: String, nomeLocal: String?, observacao: String?, dispositivo: String): ResultadoTransferenciaColetor {
        val etiqueta = normalizarEpc(epc)
        val tag = tags.findByTagReal(etiqueta).orElseThrow { NoSuchElementException("Etiqueta $etiqueta não cadastrada") }
        val limpo = limparNomeLocal(nomeLocal.orEmpty())
        require(limpo.isNotEmpty()) { "Informe o local" }
        val local = locais.findFirstByNomeIgnoreCaseOrderByIdAsc(limpo).orElse(null)
            ?: throw IllegalArgumentException("Local $limpo não encontrado. Ele passa a existir quando um inventário é aberto nele ou quando é cadastrado na web")
        require(local.ativo) { "O local ${local.nome} está desativado" }
        if (tag.baixado) throw IllegalStateException("Bem baixado não pode ser transferido (patrimônio ${tag.patrimonio.ifBlank { tag.tagReal }})")

        if (tag.localId == local.id) {
            log.info("Transferência pelo coletor {}: o bem {} já estava em '{}' (reenvio ou nada a mudar)", dispositivo, tag.tagReal, local.nome)
            return ResultadoTransferenciaColetor(local.nome, tag.situacao.name, tag.situacao.rotulo, null, jaEstava = true)
        }
        val obs = observacao?.trim()?.takeIf { it.isNotEmpty() }
        val guia = transferir(PedidoTransferencia(listOf(tag.tagReal), local.id, null,
            motivo = listOfNotNull("Pelo coletor $dispositivo", obs).joinToString(": ")))
        val atual = tags.findById(tag.id).orElseThrow()
        return ResultadoTransferenciaColetor(local.nome, atual.situacao.name, atual.situacao.rotulo, guia.id, jaEstava = false)
    }

    @Transactional(readOnly = true)
    fun listar(tag: String?, pagina: Int): Page<TransferenciaResumo> {
        // A etiqueta é gravada em maiúsculas; o patrimônio é comparado sem diferenciar maiúsculas
        val filtro = normalizarEpc(tag.orEmpty())
        val resultado = RfidService.paginaValida(pagina) { transferencias.buscar(filtro, PageRequest.of(it, POR_PAGINA)) }
        val quantidades = if (resultado.isEmpty) emptyMap() else
            itens.contarPorTransferencia(resultado.content.map { it.id }).associate { (it[0] as Number).toLong() to (it[1] as Number).toLong() }
        return resultado.map { TransferenciaResumo(it, quantidades[it.id] ?: 0) }
    }

    @Transactional(readOnly = true)
    fun detalhar(id: Long): TransferenciaDetalhe {
        val guia = transferencias.findById(id).orElseThrow { NoSuchElementException("Transferência nº $id não encontrada") }
        return TransferenciaDetalhe(guia, itens.findAllByTransferenciaIdOrderByIdAsc(id))
    }

    @Transactional(readOnly = true)
    fun doInventario(inventarioId: String): List<TransferenciaEntity> = transferencias.findAllByInventarioIdOrderByIdDesc(inventarioId)

    /** Bens com o local, o responsável e a descrição pelo nome, na ordem do patrimônio. */
    fun descrever(lista: List<TagEntity>): List<BemTransferivel> {
        if (lista.isEmpty()) return emptyList()
        val nomesTipo = tipos.findAllById(lista.mapNotNull { it.tipoEquipamentoId }.toSet()).associate { it.id to it.nome }
        val nomesMarca = marcas.findAllById(lista.mapNotNull { it.marcaId }.toSet()).associate { it.id to it.nome }
        val nomesModelo = modelos.findAllById(lista.mapNotNull { it.modeloId }.toSet()).associate { it.id to it.nome }
        val nomesUnidade = unidades.findAll().associate { it.id to it.nome }
        val nomesLocal = locais.findAllById(lista.mapNotNull { it.localId }.toSet())
            .associate { l -> l.id to (l.unidadeId?.let(nomesUnidade::get)?.let { "$it › ${l.nome}" } ?: l.nome) }
        val nomesResponsavel = responsaveis.findAllById(lista.mapNotNull { it.responsavelId }.toSet()).associate { it.id to it.nome }
        return lista.map { t ->
            BemTransferivel(
                id = t.id, tag = t.tagReal, patrimonio = t.patrimonio.ifBlank { null }, codigoInterno = t.codigoInterno.ifBlank { null },
                descricao = listOfNotNull(t.tipoEquipamentoId?.let(nomesTipo::get), t.marcaId?.let(nomesMarca::get), t.modeloId?.let(nomesModelo::get))
                    .joinToString(" ").ifBlank { null },
                local = t.localId?.let(nomesLocal::get), responsavel = t.responsavelId?.let(nomesResponsavel::get), situacao = t.situacao
            )
        }.sortedWith(compareBy<BemTransferivel, String?>(nullsLast()) { it.patrimonio }.thenBy { it.tag })
    }

    private fun rotulo(b: BemTransferivel) = b.patrimonio ?: b.codigoInterno ?: b.tag

    private fun lista(itens: List<String>) = itens.take(10).joinToString(", ") + if (itens.size > 10) " e mais ${itens.size - 10}" else ""

    private fun opcional(valor: String?, campo: String, maximo: Int): String? =
        valor?.trim()?.takeIf { it.isNotEmpty() }?.also { require(it.length <= maximo) { "$campo pode ter no máximo $maximo caracteres" } }

    companion object {
        const val MAXIMO_BENS = 1000
        const val POR_PAGINA = 50
    }
}
