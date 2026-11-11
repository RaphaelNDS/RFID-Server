package org.example.service

import org.example.model.AcessorioEntity
import org.example.model.AreaAuditoria
import org.example.model.KitItemEntity
import org.example.model.PedidoAcessorioEntity
import org.example.model.PedidoLinhaEntity
import org.example.model.PedidoLocacaoEntity
import org.example.model.SituacaoPedido
import org.example.repository.AcessorioRepository
import org.example.repository.KitItemRepository
import org.example.repository.ModeloRepository
import org.example.repository.PedidoAcessorioRepository
import org.example.repository.PedidoLocacaoRepository
import org.example.repository.TipoEquipamentoRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/** Um acessório do kit, com o nome. */
data class KitLinha(val id: Long, val acessorioId: Long, val acessorio: String, val quantidade: Int, val acessorioAtivo: Boolean)

/** O kit de um tipo (modeloId nulo) ou de um modelo. */
data class Kit(val tipoId: Long, val modeloId: Long?, val descricao: String, val itens: List<KitLinha>) {
    val doTipo: Boolean get() = modeloId == null
}

/** Acessórios enviados que não voltaram, de um pedido. */
data class AcessorioPendente(
    val pedidoId: Long, val clienteId: Long, val cliente: String, val situacao: SituacaoPedido, val previsaoDevolucao: LocalDate,
    val descricao: String, val enviada: Int, val faltando: Int
)

/** O que veio da tela para um acessório do pedido: os novos enviados e devolvidos (nulo: não muda). */
data class QuantidadesAcessorio(val enviada: Int?, val devolvida: Int?)

/**
 * Kits e acessórios da locação (1.18.0). Os acessórios são contados (sem etiqueta). O kit de um tipo, ou de um modelo
 * (que vale antes do kit do tipo), entra no pedido junto com a linha: a quantidade do kit vezes a da linha. Cada
 * acessório do pedido guarda quanto foi pedido, enviado e devolvido; o que foi enviado e não voltou é o que falta.
 */
@Service
class AcessorioService(
    private val acessorios: AcessorioRepository,
    private val kits: KitItemRepository,
    private val doPedido: PedidoAcessorioRepository,
    private val pedidos: PedidoLocacaoRepository,
    private val tipos: TipoEquipamentoRepository,
    private val modelos: ModeloRepository,
    private val historico: HistoricoEtiquetaService,
    private val auditoria: AuditoriaService,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /* ================= ACESSÓRIOS ================= */

    fun listar(): List<AcessorioEntity> = acessorios.findAllByOrderByAtivoDescNomeAsc()

    fun ativos(): List<AcessorioEntity> = listar().filter { it.ativo }

    fun acessorio(id: Long): AcessorioEntity = acessorios.findById(id).orElseThrow { NoSuchElementException("Acessório $id não encontrado") }

    @Transactional
    fun salvar(id: Long?, nome: String?, ativo: Boolean = true): AcessorioEntity {
        val n = nome?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("Informe o nome do acessório")
        require(n.length <= 120) { "O nome pode ter no máximo 120 caracteres" }
        acessorios.findFirstByNomeIgnoreCase(n)?.takeIf { it.id != id }?.let { throw IllegalArgumentException("Já existe o acessório ${it.nome}") }
        val novo = id == null
        val a = if (novo) AcessorioEntity(nome = n) else acessorio(id!!)
        val antes = a.nome
        a.nome = n
        a.ativo = ativo
        val salvo = acessorios.save(a)
        val extra = listOfNotNull(if (!novo && antes != n) "antes: $antes" else null, if (!ativo) "desativado" else null)
        log.info("Acessório {} {} por {}{}", salvo.nome, if (novo) "cadastrado" else "alterado", autor(),
            if (extra.isEmpty()) "" else " (${extra.joinToString(", ")})")
        auditoria.registrar(AreaAuditoria.PATRIMONIO, if (novo) "Acessório cadastrado" else "Acessório alterado", salvo.nome, if (ativo) null else "desativado")
        return salvo
    }

    /* ================= KITS ================= */

    /** Todos os kits: primeiro o do tipo, depois os dos modelos, por nome. */
    @Transactional(readOnly = true)
    fun kits(): List<Kit> {
        val nomes = acessorios.findAll().associateBy { it.id }
        val nomeTipo = tipos.findAll().associate { it.id to it.nome }
        return kits.findAll().groupBy { it.tipoEquipamentoId to it.modeloId }.map { (chave, itens) ->
            val (tipoId, modeloId) = chave
            Kit(tipoId, modeloId, descricaoKit(nomeTipo[tipoId] ?: "Tipo $tipoId", modeloId),
                itens.map { KitLinha(it.id, it.acessorioId, nomes[it.acessorioId]?.nome ?: "?", it.quantidade, nomes[it.acessorioId]?.ativo ?: false) }.sortedBy { it.acessorio.lowercase() })
        }.sortedWith(compareBy<Kit> { it.descricao.substringBefore(" ·").lowercase() }.thenBy { !it.doTipo }.thenBy { it.descricao.lowercase() })
    }

    /**
     * Põe o acessório no kit do tipo, ou do modelo se [modeloId] vier (o tipo é o do modelo). Se ele já está no kit, a
     * quantidade muda.
     */
    @Transactional
    fun adicionarAoKit(tipoId: Long?, modeloId: Long?, acessorioId: Long?, quantidade: Int?): Kit {
        val modelo = modeloId?.let { id -> modelos.findById(id).orElseThrow { IllegalArgumentException("Modelo não encontrado") } }
        val tipo = modelo?.marca?.tipo ?: tipoId?.let { tipos.findById(it).orElse(null) } ?: throw IllegalArgumentException("Escolha o tipo de equipamento")
        require(tipoId == null || tipoId == tipo.id) { "O modelo ${modelo?.nome} não é do tipo escolhido" }
        val a = acessorioId?.let { acessorios.findById(it).orElse(null) } ?: throw IllegalArgumentException("Escolha o acessório")
        require(a.ativo) { "O acessório ${a.nome} está desativado" }
        val qtd = quantidade ?: 1
        require(qtd in 1..100) { "A quantidade vai de 1 a 100" }
        val existente = kits.findAllByTipoEquipamentoId(tipo.id).firstOrNull { it.modeloId == modelo?.id && it.acessorioId == a.id }
        val item = existente ?: KitItemEntity(tipoEquipamentoId = tipo.id, modeloId = modelo?.id, acessorioId = a.id, quantidade = qtd)
        item.quantidade = qtd
        kits.save(item)
        val descricao = descricaoKit(tipo.nome, modelo?.id)
        log.info("Kit {}: {} x {} {} por {}", descricao, qtd, a.nome, if (existente == null) "acrescentado" else "com a quantidade mudada", autor())
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Kit alterado", descricao, "$qtd × ${a.nome}")
        return kits().first { it.tipoId == tipo.id && it.modeloId == modelo?.id }
    }

    @Transactional
    fun removerDoKit(itemId: Long) {
        val item = kits.findById(itemId).orElseThrow { NoSuchElementException("Item do kit $itemId não encontrado") }
        kits.delete(item)
        val descricao = descricaoKit(tipos.findById(item.tipoEquipamentoId).map { it.nome }.orElse("Tipo ${item.tipoEquipamentoId}"), item.modeloId)
        val nome = acessorios.findById(item.acessorioId).map { it.nome }.orElse("?")
        log.info("Kit {}: {} removido por {}", descricao, nome, autor())
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Kit alterado", descricao, "$nome removido")
    }

    /** O kit que vale para o tipo e o modelo: o do modelo, se tiver; senão, o do tipo. */
    fun kitPara(tipoId: Long?, modeloId: Long?): List<KitItemEntity> {
        if (tipoId == null) return emptyList()
        val doTipo = kits.findAllByTipoEquipamentoId(tipoId)
        return doTipo.filter { modeloId != null && it.modeloId == modeloId }.ifEmpty { doTipo.filter { it.modeloId == null } }
    }

    /* ================= NO PEDIDO ================= */

    fun doPedido(pedidoId: Long): List<PedidoAcessorioEntity> = doPedido.findAllByPedidoIdOrderByIdAsc(pedidoId)

    /** A linha nova traz o kit (LocacaoService.adicionarLinha): kit × quantidade da linha. Só acessórios ativos. */
    @Transactional
    fun aplicarKit(linha: PedidoLinhaEntity): List<PedidoAcessorioEntity> {
        val nomes = acessorios.findAll().associateBy { it.id }
        val novos = kitPara(linha.tipoEquipamentoId, linha.modeloId).mapNotNull { k ->
            val a = nomes[k.acessorioId]?.takeIf { it.ativo } ?: return@mapNotNull null
            PedidoAcessorioEntity(pedidoId = linha.pedidoId, linhaId = linha.id, acessorioId = a.id, descricao = a.nome, quantidade = k.quantidade * linha.quantidade)
        }
        if (novos.isEmpty()) return emptyList()
        val salvos = doPedido.saveAll(novos)
        log.info("Pedido de locação nº {}: kit da linha {} acrescentado: {}", linha.pedidoId, linha.descricao, resumo(salvos))
        return salvos
    }

    /** A linha saiu do pedido: os acessórios dela que não foram enviados saem também; os enviados ficam como avulsos. */
    @Transactional
    fun aoRemoverLinha(linhaId: Long) {
        val lista = doPedido.findAllByLinhaId(linhaId)
        val (enviados, nao) = lista.partition { it.enviada > 0 }
        doPedido.deleteAll(nao)
        enviados.forEach { it.linhaId = null }
        doPedido.saveAll(enviados)
        doPedido.flush()
    }

    @Transactional
    fun adicionarAvulso(pedidoId: Long, acessorioId: Long?, quantidade: Int?): PedidoAcessorioEntity {
        val p = pedido(pedidoId)
        require(!p.situacao.finalizado) { "O pedido nº $pedidoId está ${p.situacao.rotulo.lowercase()}: não muda mais" }
        val a = acessorioId?.let { acessorios.findById(it).orElse(null) } ?: throw IllegalArgumentException("Escolha o acessório")
        require(a.ativo) { "O acessório ${a.nome} está desativado" }
        val qtd = quantidade ?: 1
        require(qtd in 1..1000) { "A quantidade vai de 1 a 1000" }
        require(doPedido.findAllByPedidoIdOrderByIdAsc(pedidoId).size < MAXIMO_POR_PEDIDO) { "O pedido pode ter no máximo $MAXIMO_POR_PEDIDO acessórios" }
        val salvo = doPedido.save(PedidoAcessorioEntity(pedidoId = pedidoId, acessorioId = a.id, descricao = a.nome, quantidade = qtd))
        log.info("Pedido de locação nº {}: acessório avulso {} x {} acrescentado por {}", pedidoId, qtd, a.nome, autor())
        return salvo
    }

    /** Tira o acessório do pedido: só se nada dele foi enviado. */
    @Transactional
    fun removerDoPedido(pedidoId: Long, id: Long) {
        val p = pedido(pedidoId)
        require(!p.situacao.finalizado) { "O pedido nº $pedidoId está ${p.situacao.rotulo.lowercase()}: não muda mais" }
        val a = item(pedidoId, id)
        require(a.enviada == 0) { "Já foram enviados ${a.enviada} de ${a.descricao}: registre a devolução em vez de tirar do pedido" }
        doPedido.delete(a)
        log.info("Pedido de locação nº {}: acessório {} x {} removido por {}", pedidoId, a.quantidade, a.descricao, autor())
    }

    /**
     * Registra quantos de cada acessório foram enviados e devolvidos. Devolvido não passa do enviado. Pedido cancelado
     * não muda; encerrado só recebe devolução (acessório que chegou depois dos equipamentos).
     */
    @Transactional
    fun registrar(pedidoId: Long, valores: Map<Long, QuantidadesAcessorio>): List<PedidoAcessorioEntity> {
        val p = pedido(pedidoId)
        require(p.situacao != SituacaoPedido.CANCELADO) { "O pedido nº $pedidoId está cancelado" }
        val lista = doPedido.findAllByPedidoIdOrderByIdAsc(pedidoId)
        val porId = lista.associateBy { it.id }
        valores.keys.firstOrNull { it !in porId }?.let { throw IllegalArgumentException("Acessório $it não é deste pedido") }
        val mudancas = mutableListOf<String>()
        valores.forEach { (id, q) ->
            val a = porId.getValue(id)
            val enviada = q.enviada ?: a.enviada
            val devolvida = q.devolvida ?: a.devolvida
            require(enviada in 0..100000 && devolvida >= 0) { "Quantidade inválida em ${a.descricao}" }
            require(devolvida <= enviada) { "${a.descricao}: devolvidos ($devolvida) não pode passar dos enviados ($enviada)" }
            require(enviada == a.enviada || !p.situacao.finalizado) { "O pedido nº $pedidoId está encerrado: só a devolução dos acessórios muda" }
            if (enviada != a.enviada || devolvida != a.devolvida) {
                mudancas += "${a.descricao}: enviados ${a.enviada}→$enviada, devolvidos ${a.devolvida}→$devolvida"
                a.enviada = enviada
                a.devolvida = devolvida
                a.atualizadoEm = LocalDateTime.now(relogio)
                a.atualizadoPor = autor()
            }
        }
        if (mudancas.isNotEmpty()) {
            doPedido.saveAll(lista)
            log.info("Pedido de locação nº {}: acessórios registrados por {}: {}", pedidoId, autor(), mudancas.joinToString("; "))
            auditoria.registrar(AreaAuditoria.PATRIMONIO, "Acessórios do pedido", "Pedido nº $pedidoId", mudancas.joinToString("; ").take(1000))
        }
        return lista
    }

    /** Tudo o que foi pedido sai (enviados = pedidos). */
    @Transactional
    fun enviarTudo(pedidoId: Long): List<PedidoAcessorioEntity> =
        registrar(pedidoId, doPedido(pedidoId).filter { it.enviada < it.quantidade }.associate { it.id to QuantidadesAcessorio(it.quantidade, null) })

    /** Tudo o que foi enviado voltou (devolvidos = enviados). */
    @Transactional
    fun devolverTudo(pedidoId: Long): List<PedidoAcessorioEntity> =
        registrar(pedidoId, doPedido(pedidoId).filter { it.faltando > 0 }.associate { it.id to QuantidadesAcessorio(null, it.enviada) })

    /** Enviados que não voltaram, de todos os pedidos não cancelados (relatórios). */
    @Transactional(readOnly = true)
    fun pendentes(): List<AcessorioPendente> {
        val lista = doPedido.pendentes()
        val ps = pedidos.findAllById(lista.map { it.pedidoId }.toSet()).associateBy { it.id }
        return lista.mapNotNull { a ->
            val p = ps[a.pedidoId]?.takeIf { it.situacao != SituacaoPedido.CANCELADO } ?: return@mapNotNull null
            AcessorioPendente(p.id, p.clienteId, p.cliente, p.situacao, p.previsaoDevolucao, a.descricao, a.enviada, a.faltando)
        }
    }

    /** Para cobrar do cliente: o que não voltou de pedido encerrado (os equipamentos voltaram) ou com a devolução vencida. */
    fun aCobrar(): List<AcessorioPendente> {
        val hoje = LocalDate.now(relogio)
        return pendentes().filter { it.situacao == SituacaoPedido.ENCERRADO || it.previsaoDevolucao.isBefore(hoje) }
    }

    /** "2 × Carregador, 1 × Bateria". */
    fun resumo(lista: List<PedidoAcessorioEntity>): String = lista.joinToString(", ") { "${it.quantidade} × ${it.descricao}" }

    private fun item(pedidoId: Long, id: Long): PedidoAcessorioEntity =
        doPedido.findById(id).filter { it.pedidoId == pedidoId }.orElseThrow { NoSuchElementException("Acessório $id não encontrado no pedido") }

    private fun pedido(id: Long): PedidoLocacaoEntity = pedidos.findById(id).orElseThrow { NoSuchElementException("Pedido de locação nº $id não encontrado") }

    /** "Coletor" (kit do tipo) ou "Coletor · Zebra MC3300". */
    private fun descricaoKit(tipo: String, modeloId: Long?): String =
        if (modeloId == null) tipo else modelos.findById(modeloId).map { "$tipo · ${it.marca.nome} ${it.nome}" }.orElse("$tipo · modelo $modeloId")

    private fun autor(): String = historico.autor().first

    companion object {
        const val MAXIMO_POR_PEDIDO = 200
    }
}
