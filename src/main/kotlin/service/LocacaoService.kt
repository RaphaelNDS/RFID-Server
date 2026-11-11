package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.ClienteEntity
import org.example.model.EstadoDevolucao
import org.example.model.PedidoItemEntity
import org.example.model.PedidoLinhaEntity
import org.example.model.PedidoLocacaoEntity
import org.example.model.SituacaoBem
import org.example.model.SituacaoPedido
import org.example.model.TagEntity
import org.example.model.TipoManutencao
import org.example.repository.ClienteRepository
import org.example.repository.MarcaRepository
import org.example.repository.ModeloRepository
import org.example.repository.PedidoItemRepository
import org.example.repository.PedidoLinhaRepository
import org.example.repository.PedidoLocacaoRepository
import org.example.repository.TagRepository
import org.example.repository.TipoEquipamentoRepository
import org.example.util.normalizarEpc
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Locadora (1.12.0): clientes, pedidos de locação, saída e devolução (pela web) e a conferência da
 * devolução. Substitui o empréstimo da 1.10.0.
 *
 * Fluxo do equipamento: Disponível → (saída) Alugado → (devolução) Em devolução → (conferência) Disponível, ou
 * Em manutenção (abre a ordem de manutenção). Enquanto está num pedido (da saída até a conferência), ele não recebe
 * baixa, não é excluído e a situação não muda pela edição (RfidService.ocupacao). Um equipamento só está em um pedido
 * por vez (índice único no banco).
 *
 * O pedido pede linhas: N unidades de um tipo (marca e modelo opcionais) ou um bem específico. A saída é parcial e
 * soma ao que já saiu; quando todas as vagas são preenchidas (ou a web encerra a separação), o pedido fica Entregue.
 * Saída e devolução são idempotentes: o equipamento que já saiu (ou já voltou) neste pedido volta como aceito de novo,
 * sem duplicar, então o app pode reenviar quando a resposta se perde.
 */
@Service
class LocacaoService(
    private val clientes: ClienteRepository,
    private val pedidos: PedidoLocacaoRepository,
    private val linhas: PedidoLinhaRepository,
    private val itens: PedidoItemRepository,
    private val tags: TagRepository,
    private val tipos: TipoEquipamentoRepository,
    private val marcas: MarcaRepository,
    private val modelos: ModeloRepository,
    private val manutencoes: ManutencaoService,
    private val historico: HistoricoEtiquetaService,
    private val auditoria: AuditoriaService,
    private val relogio: Clock,
    // 1.16.0: histórico das prorrogações da devolução prevista
    private val prorrogacoes: org.example.repository.PedidoProrrogacaoRepository,
    // 1.17.0: folha de separação (descrição e local dos equipamentos candidatos)
    private val transferencias: TransferenciaService? = null,
    // 1.18.0: o kit de acessórios entra com a linha
    private val acessorios: AcessorioService? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun hoje(): LocalDate = LocalDate.now(relogio)

    /**
     * Prorroga (ou antecipa) a devolução prevista do pedido, com o motivo (1.16.0). Fica no histórico do pedido, no
     * contrato e na auditoria. Pedido encerrado ou cancelado não muda.
     */
    @Transactional
    fun prorrogar(pedidoId: Long, novaData: LocalDate?, motivo: String?): PedidoLocacaoEntity {
        val p = pedido(pedidoId)
        require(!p.situacao.finalizado) { "O pedido nº $pedidoId está ${p.situacao.rotulo.lowercase()}: a devolução não muda mais" }
        val data = novaData ?: throw IllegalArgumentException("Informe a nova data de devolução")
        require(data != p.previsaoDevolucao) { "A devolução já está prevista para ${org.example.util.FormatoBr.data(data)}" }
        require(!data.isBefore(hoje())) { "A nova devolução não pode ser no passado" }
        p.saidaPrevista?.let { require(!data.isBefore(it)) { "A devolução não pode ser antes da saída prevista" } }
        val obs = texto(motivo, "O motivo", 500) ?: throw IllegalArgumentException("Informe o motivo da prorrogação")
        registrarProrrogacao(p, data, obs)
        p.previsaoDevolucao = data
        pedidos.save(p)
        return p
    }

    fun prorrogacoesDo(pedidoId: Long): List<org.example.model.PedidoProrrogacaoEntity> = prorrogacoes.findAllByPedidoIdOrderByIdAsc(pedidoId)

    private fun registrarProrrogacao(p: PedidoLocacaoEntity, nova: LocalDate, motivo: String) {
        val anterior = p.previsaoDevolucao
        val usuario = autor()
        prorrogacoes.save(org.example.model.PedidoProrrogacaoEntity(pedidoId = p.id, dataAnterior = anterior, dataNova = nova, motivo = motivo, registradaPor = usuario))
        val verbo = if (nova.isAfter(anterior)) "prorrogada" else "antecipada"
        log.info("Pedido de locação nº {}: devolução {} de {} para {} por {} ({})", p.id, verbo, anterior, nova, usuario, motivo)
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Devolução $verbo", "Pedido nº ${p.id}",
            "${org.example.util.FormatoBr.data(anterior)} → ${org.example.util.FormatoBr.data(nova)}: $motivo")
    }

    /* ================= CLIENTES ================= */

    fun listarClientes(): List<ClienteEntity> = clientes.findAllByOrderByAtivoDescNomeAsc()

    fun clientesAtivos(): List<ClienteEntity> = listarClientes().filter { it.ativo }

    fun cliente(id: Long): ClienteEntity = clientes.findById(id).orElseThrow { NoSuchElementException("Cliente $id não encontrado") }

    fun pedidosDoCliente(id: Long): List<PedidoLocacaoEntity> = pedidos.findAllByClienteIdOrderByIdDesc(id)

    @Transactional
    fun salvarCliente(id: Long?, dados: DadosCliente): ClienteEntity {
        val nome = texto(dados.nome, "O nome", 160) ?: throw IllegalArgumentException("Informe o nome do cliente")
        val documento = dados.documento?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }?.also {
            require(it.length == 11 || it.length == 14) { "O documento precisa ser um CPF (11 dígitos) ou um CNPJ (14 dígitos)" }
        }
        documento?.let { d ->
            clientes.findFirstByDocumento(d)?.takeIf { it.id != id }?.let { throw IllegalArgumentException("Já existe o cliente ${it.nome} com este documento") }
        }
        val email = texto(dados.email, "O email", 160)?.lowercase()?.also { require('@' in it) { "Email inválido: $it" } }
        val c = if (id == null) ClienteEntity(nome = nome) else cliente(id)
        val novo = id == null
        c.nome = nome
        c.documento = documento
        c.contato = texto(dados.contato, "O contato", 160)
        c.telefone = texto(dados.telefone, "O telefone", 40)
        c.email = email
        c.endereco = texto(dados.endereco, "O endereço", 300)
        c.observacao = texto(dados.observacao, "A observação", 500)
        val salvo = clientes.save(c)
        log.info("Cliente {} {} por {}", salvo.descricao, if (novo) "cadastrado" else "alterado", autor())
        auditoria.registrar(AreaAuditoria.PATRIMONIO, if (novo) "Cliente cadastrado" else "Cliente alterado", salvo.descricao)
        return salvo
    }

    @Transactional
    fun ativarCliente(id: Long, ativo: Boolean): ClienteEntity {
        val c = cliente(id)
        c.ativo = ativo
        clientes.save(c)
        log.info("Cliente {} {} por {}", c.nome, if (ativo) "reativado" else "desativado", autor())
        auditoria.registrar(AreaAuditoria.PATRIMONIO, if (ativo) "Cliente reativado" else "Cliente desativado", c.descricao)
        return c
    }

    /* ================= PEDIDOS ================= */

    @Transactional
    fun criarPedido(dados: DadosPedido): PedidoLocacaoEntity {
        val (cliente, previsao) = validarCabecalho(dados, null)
        val p = pedidos.save(PedidoLocacaoEntity(
            clienteId = cliente.id, cliente = cliente.descricao.take(255), saidaPrevista = dados.saidaPrevista,
            previsaoDevolucao = previsao, localEntrega = texto(dados.localEntrega, "O local de entrega", 300) ?: cliente.endereco,
            observacao = texto(dados.observacao, "A observação", 500), registradoPor = autor()
        ))
        log.info("Pedido de locação nº {} criado por {}: cliente {}, devolução prevista em {}", p.id, p.registradoPor, cliente.nome, previsao)
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Pedido de locação criado", "Pedido nº ${p.id}", "${cliente.nome}, devolução prevista em ${org.example.util.FormatoBr.data(previsao)}")
        return p
    }

    @Transactional
    fun editarPedido(id: Long, dados: DadosPedido): PedidoLocacaoEntity {
        val p = pedido(id)
        require(!p.situacao.finalizado) { "O pedido nº $id está ${p.situacao.rotulo.lowercase()}: não muda mais" }
        val (cliente, previsao) = validarCabecalho(dados, p)
        val saiu = itens.findAllByPedidoIdOrderByIdAsc(id).isNotEmpty()
        require(!saiu || cliente.id == p.clienteId) { "Já saiu equipamento neste pedido: o cliente não muda (cancele e faça outro)" }
        p.clienteId = cliente.id
        p.cliente = cliente.descricao.take(255)
        p.saidaPrevista = dados.saidaPrevista
        // 1.16.0: mudar a devolução pela edição também fica no histórico das prorrogações
        if (previsao != p.previsaoDevolucao) registrarProrrogacao(p, previsao, "Alterada nos dados do pedido")
        p.previsaoDevolucao = previsao
        p.localEntrega = texto(dados.localEntrega, "O local de entrega", 300)
        p.observacao = texto(dados.observacao, "A observação", 500)
        pedidos.save(p)
        log.info("Pedido de locação nº {} alterado por {} (devolução prevista em {})", id, autor(), previsao)
        return p
    }

    private fun validarCabecalho(dados: DadosPedido, atual: PedidoLocacaoEntity?): Pair<ClienteEntity, LocalDate> {
        val cliente = dados.clienteId?.let { cliente(it) } ?: throw IllegalArgumentException("Escolha o cliente")
        require(cliente.ativo || cliente.id == atual?.clienteId) { "O cliente ${cliente.nome} está desativado" }
        val previsao = dados.previsaoDevolucao ?: throw IllegalArgumentException("Informe a previsão de devolução")
        dados.saidaPrevista?.let { require(!previsao.isBefore(it)) { "A devolução prevista não pode ser antes da saída prevista" } }
        if (atual == null) require(!previsao.isBefore(hoje())) { "A devolução prevista não pode ser no passado" }
        return cliente to previsao
    }

    /**
     * Linha nova: por tipo (marca e modelo opcionais) e quantidade, ou um bem específico pelo [codigoBem] (etiqueta ou
     * patrimônio). Pode ser acrescentada depois da saída (o cliente pediu mais): o pedido volta a "em separação".
     */
    @Transactional
    fun adicionarLinha(pedidoId: Long, tipoId: Long?, marcaId: Long?, modeloId: Long?, quantidade: Int?, codigoBem: String?): PedidoLinhaEntity {
        val p = pedido(pedidoId)
        require(!p.situacao.finalizado) { "O pedido nº $pedidoId está ${p.situacao.rotulo.lowercase()}: não muda mais" }
        val atuais = linhas.findAllByPedidoIdOrderByIdAsc(pedidoId)
        require(atuais.size < MAXIMO_LINHAS) { "O pedido pode ter no máximo $MAXIMO_LINHAS linhas" }
        val codigo = codigoBem?.trim()?.takeIf { it.isNotEmpty() }
        val linha = if (codigo != null) {
            val tag = acharBem(codigo) ?: throw IllegalArgumentException("Equipamento não encontrado no cadastro: $codigo")
            require(atuais.none { it.especifico && it.tagId == tag.id }) { "Este equipamento já está no pedido" }
            require(!tag.foraDoAcervo) { "Este equipamento está ${tag.situacao.rotulo.lowercase()}" }
            PedidoLinhaEntity(pedidoId = pedidoId, especifico = true, tagId = tag.id, tipoEquipamentoId = tag.tipoEquipamentoId,
                marcaId = tag.marcaId, modeloId = tag.modeloId, descricao = descricaoBem(tag).take(255), quantidade = 1)
        } else {
            val tipo = tipoId?.let { tipos.findById(it).orElse(null) } ?: throw IllegalArgumentException("Escolha o tipo de equipamento (ou informe um equipamento específico)")
            val marca = marcaId?.let { id -> marcas.findById(id).orElseThrow { IllegalArgumentException("Marca não encontrada") } }
            val modelo = modeloId?.let { id -> modelos.findById(id).orElseThrow { IllegalArgumentException("Modelo não encontrado") } }
            require(marca == null || marca.tipo.id == tipo.id) { "A marca ${marca!!.nome} não é do tipo ${tipo.nome}" }
            require(modelo == null || (marca != null && modelo.marca.id == marca.id)) { "Escolha a marca do modelo" }
            val qtd = quantidade ?: 1
            require(qtd in 1..1000) { "A quantidade vai de 1 a 1000" }
            PedidoLinhaEntity(pedidoId = pedidoId, tipoEquipamentoId = tipo.id, marcaId = marca?.id, modeloId = modelo?.id,
                descricao = listOfNotNull(tipo.nome, marca?.nome, modelo?.nome).joinToString(" ").take(255), quantidade = qtd)
        }
        val salva = linhas.save(linha)
        acessorios?.aplicarKit(salva)
        recalcular(p, abrirSeparacao = true)
        log.info("Pedido de locação nº {}: linha {} x {} acrescentada por {}", pedidoId, salva.quantidade, salva.descricao, autor())
        return salva
    }

    @Transactional
    fun removerLinha(pedidoId: Long, linhaId: Long) {
        val p = pedido(pedidoId)
        require(!p.situacao.finalizado) { "O pedido nº $pedidoId está ${p.situacao.rotulo.lowercase()}: não muda mais" }
        val linha = linhas.findById(linhaId).filter { it.pedidoId == pedidoId }.orElseThrow { NoSuchElementException("Linha $linhaId não encontrada no pedido") }
        require(itens.findAllByPedidoIdOrderByIdAsc(pedidoId).none { it.linhaId == linhaId }) { "Já saiu equipamento por esta linha: ela não pode sair do pedido" }
        acessorios?.aoRemoverLinha(linhaId)
        linhas.delete(linha)
        linhas.flush()
        recalcular(p)
        log.info("Pedido de locação nº {}: linha {} x {} removida por {}", pedidoId, linha.quantidade, linha.descricao, autor())
    }

    /** Encerra a separação com o que já saiu (o cliente levou menos): o pedido fica Entregue. */
    @Transactional
    fun encerrarSeparacao(pedidoId: Long): PedidoLocacaoEntity {
        val p = pedido(pedidoId)
        require(!p.situacao.finalizado) { "O pedido nº $pedidoId está ${p.situacao.rotulo.lowercase()}" }
        require(itens.findAllByPedidoIdOrderByIdAsc(pedidoId).isNotEmpty()) { "Nada saiu ainda: para desistir do pedido, cancele" }
        p.separacaoConcluida = true
        recalcular(p)
        log.info("Pedido de locação nº {}: separação encerrada por {} com o que saiu", pedidoId, autor())
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Separação encerrada", "Pedido nº $pedidoId", "com o que já saiu")
        return p
    }

    @Transactional
    fun cancelarPedido(pedidoId: Long, motivo: String?): PedidoLocacaoEntity {
        val p = pedido(pedidoId)
        require(!p.situacao.finalizado) { "O pedido nº $pedidoId já está ${p.situacao.rotulo.lowercase()}" }
        require(itens.findAllByPedidoIdOrderByIdAsc(pedidoId).isEmpty()) { "Já saiu equipamento neste pedido: registre a devolução em vez de cancelar" }
        val obs = texto(motivo, "O motivo", 500) ?: throw IllegalArgumentException("Informe o motivo do cancelamento")
        p.situacao = SituacaoPedido.CANCELADO
        p.cancelamentoMotivo = obs
        pedidos.save(p)
        log.warn("Pedido de locação nº {} cancelado por {}: {}", pedidoId, autor(), obs)
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Pedido de locação cancelado", "Pedido nº $pedidoId", obs)
        return p
    }

    /* ================= SAÍDA ================= */

    /**
     * Saída dos equipamentos: os aceitos ficam Alugado, ligados ao pedido. Recusados (sem cadastro, fora do pedido,
     * não disponível, excedente) não mudam. Pedido cancelado ou encerrado: [IllegalStateException].
     */
    @Transactional
    fun registrarSaida(pedidoId: Long, codigos: Collection<String>, observacao: String? = null, origem: String = "Web"): ResultadoSaida {
        val p = pedido(pedidoId)
        if (p.situacao.finalizado) throw IllegalStateException("O pedido nº $pedidoId está ${p.situacao.rotulo.lowercase()}: não sai mais nada")
        val conferencia = distribuir(p, codigos)
        val agora = LocalDateTime.now(relogio)
        val usuario = autor()
        val aceitos = conferencia.itens.filter { it.aceito }
        aceitos.forEach { c ->
            val tag = c.tag!!
            val antes = historico.retrato(tag)
            itens.save(PedidoItemEntity(
                pedidoId = p.id, linhaId = c.linhaId!!, tagId = tag.id, tagReal = tag.tagReal, patrimonio = tag.patrimonio.ifBlank { null },
                descricao = descricaoCatalogo(tag).take(255), saidaEm = agora, saidaPor = usuario
            ))
            tag.situacao = SituacaoBem.ALUGADO
            tags.save(tag)
            historico.registrarAlteracao(tag, antes, "Saída do pedido de locação nº ${p.id}")
        }
        // Grava já: o índice único recusa aqui se outra leitura pôs o mesmo equipamento em outro pedido ao mesmo tempo
        itens.flush()
        recalcular(p)
        val obs = observacao?.trim()?.takeIf { it.isNotEmpty() }
        log.info("Saída do pedido de locação nº {} por {} ({}): {} aceito(s), {} já tinha(m) saído, {} recusado(s); pedido {}{}", p.id, usuario, origem,
            aceitos.size, conferencia.itens.count { it.jaSaiu }, conferencia.itens.count { it.problema != null }, p.situacao.rotulo, obs?.let { "; $it" } ?: "")
        if (aceitos.isNotEmpty()) {
            auditoria.registrar(AreaAuditoria.PATRIMONIO, "Saída de locação", "Pedido nº ${p.id}", "${aceitos.size} equipamento(s) para ${p.cliente} ($origem)")
        }
        return ResultadoSaida(conferencia, aceitos, p)
    }

    /* ================= DEVOLUÇÃO ================= */

    /**
     * Devolução: cada equipamento com saída em aberto fica Em devolução até a conferência. O que já voltou e espera a
     * conferência entra de novo em [ResultadoDevolucao.recebidos] (reenvio do coletor).
     */
    @Transactional
    fun registrarDevolucao(codigos: Collection<String>, observacao: String? = null, origem: String = "Web"): ResultadoDevolucao {
        val limpos = limpar(codigos)
        val agora = LocalDateTime.now(relogio)
        val usuario = autor()
        val recebidos = mutableListOf<Pair<PedidoItemEntity, PedidoLocacaoEntity>>()
        val semLocacao = mutableListOf<Pair<String, TagEntity?>>()
        val tocados = linkedMapOf<Long, PedidoLocacaoEntity>()
        var novos = 0
        limpos.forEach { codigo ->
            val tag = acharBem(codigo)
            val item = tag?.let { itens.findFirstByTagIdAndConferidoEmIsNull(it.id) }
            if (tag == null || item == null) { semLocacao += codigo to tag; return@forEach }
            val p = tocados.getOrPut(item.pedidoId) { pedido(item.pedidoId) }
            if (!item.devolvido) {
                item.devolvidoEm = agora
                item.devolvidoPor = usuario
                itens.save(item)
                val antes = historico.retrato(tag)
                tag.situacao = SituacaoBem.EM_DEVOLUCAO
                tags.save(tag)
                historico.registrarAlteracao(tag, antes, "Devolução do pedido de locação nº ${p.id}")
                novos++
            }
            recebidos += item to p
        }
        tocados.values.forEach { recalcular(it) }
        val obs = observacao?.trim()?.takeIf { it.isNotEmpty() }
        log.info("Devolução registrada por {} ({}): {} recebido(s) ({} novo(s)), {} sem locação, pedido(s) {}{}", usuario, origem, recebidos.size, novos,
            semLocacao.size, tocados.keys.joinToString(), obs?.let { "; $it" } ?: "")
        if (novos > 0) {
            auditoria.registrar(AreaAuditoria.PATRIMONIO, "Devolução de locação", "Pedido(s) nº ${tocados.keys.joinToString()}", "$novos equipamento(s) recebido(s) ($origem)")
        }
        return ResultadoDevolucao(recebidos, semLocacao, tocados.values.toList())
    }

    /**
     * Conferência da devolução (web): o estado do equipamento e para onde ele vai. Disponível, ou Em manutenção (abre a
     * ordem de manutenção com o que foi observado). Encerra a ligação do equipamento com o pedido.
     */
    @Transactional
    fun conferirDevolucao(itemId: Long, estado: EstadoDevolucao?, paraManutencao: Boolean, observacao: String?): PedidoItemEntity {
        val item = itens.findById(itemId).orElseThrow { NoSuchElementException("Item $itemId não encontrado") }
        require(item.devolvido) { "Este equipamento ainda não foi devolvido" }
        require(!item.conferido) { "A devolução deste equipamento já foi conferida" }
        val e = estado ?: throw IllegalArgumentException("Escolha o estado do equipamento")
        val obs = texto(observacao, "A observação", 500)
        require(e == EstadoDevolucao.OK || obs != null || paraManutencao) { "Descreva a avaria ou o acessório que falta" }
        val usuario = autor()
        item.conferidoEm = LocalDateTime.now(relogio)
        item.conferidoPor = usuario
        item.estadoDevolucao = e
        item.observacaoDevolucao = obs
        itens.save(item)
        itens.flush()
        val tag = item.tagId?.let { tags.findById(it).orElse(null) }
        if (tag != null && tag.situacao == SituacaoBem.EM_DEVOLUCAO) {
            val antes = historico.retrato(tag)
            tag.situacao = SituacaoBem.DISPONIVEL
            tags.save(tag)
            historico.registrarAlteracao(tag, antes, "Devolução do pedido de locação nº ${item.pedidoId} conferida (${e.rotulo})")
            if (paraManutencao) {
                val ordem = manutencoes.abrir(PedidoManutencao(tag.tagReal, TipoManutencao.CORRETIVA,
                    listOfNotNull("Devolução do pedido nº ${item.pedidoId}: ${e.rotulo}", obs).joinToString(" · ").take(500)))
                item.manutencaoId = ordem.id
                itens.save(item)
            }
        }
        log.info("Devolução do item {} do pedido nº {} conferida por {}: {}{}{}", item.tagReal, item.pedidoId, usuario, e.rotulo,
            if (paraManutencao) ", para a manutenção nº ${item.manutencaoId}" else ", Disponível", obs?.let { " ($it)" } ?: "")
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Devolução conferida", "Pedido nº ${item.pedidoId}",
            "${item.patrimonio ?: item.tagReal}: ${e.rotulo}" + if (paraManutencao) ", para a manutenção" else "")
        return item
    }

    /* ================= CONSULTAS ================= */

    @Transactional(readOnly = true)
    fun listar(filtro: FiltroPedido, busca: String?, pagina: Int): Page<PedidoResumo> {
        val hoje = hoje()
        val texto = busca?.trim().orEmpty().take(255)
        val todas = filtro == FiltroPedido.TODOS || filtro == FiltroPedido.ATRASADOS
        val resultado = RfidService.paginaValida(pagina) {
            pedidos.buscar(todas, filtro.situacoes, filtro == FiltroPedido.ATRASADOS, hoje, texto, PageRequest.of(it, POR_PAGINA))
        }
        val ids = resultado.content.map { it.id }
        val todasLinhas = if (ids.isEmpty()) emptyMap() else linhas.findAllByPedidoIdIn(ids).groupBy { it.pedidoId }
        val todosItens = if (ids.isEmpty()) emptyMap() else itens.findAllByPedidoIdIn(ids).groupBy { it.pedidoId }
        return resultado.map { p ->
            val its = todosItens[p.id].orEmpty()
            val naRua = its.count { !it.devolvido }
            PedidoResumo(p, todasLinhas[p.id].orEmpty().sumOf { it.quantidade }, its.size, naRua, atraso(p, naRua, hoje))
        }
    }

    @Transactional(readOnly = true)
    fun detalhar(id: Long): PedidoDetalhe {
        val p = pedido(id)
        val ls = linhas.findAllByPedidoIdOrderByIdAsc(id)
        val its = itens.findAllByPedidoIdOrderByIdAsc(id)
        return PedidoDetalhe(p, ls.map { l -> LinhaConferida(l, its.count { it.linhaId == l.id }, 0) }, its, atraso(p, its.count { !it.devolvido }, hoje()),
            prorrogacoes.findAllByPedidoIdOrderByIdAsc(id), acessorios?.doPedido(id).orEmpty())
    }

    /** Os pedidos nestas situações, pela saída prevista (padrão: a separar). Painel do dia. */
    @Transactional(readOnly = true)
    fun porSituacao(situacoes: Collection<SituacaoPedido>): List<PedidoResumo> {
        val lista = pedidos.porSituacao(situacoes.ifEmpty { listOf(SituacaoPedido.ABERTO, SituacaoPedido.EM_SEPARACAO) })
        val ids = lista.map { it.id }
        val todasLinhas = if (ids.isEmpty()) emptyMap() else linhas.findAllByPedidoIdIn(ids).groupBy { it.pedidoId }
        val todosItens = if (ids.isEmpty()) emptyMap() else itens.findAllByPedidoIdIn(ids).groupBy { it.pedidoId }
        val hoje = hoje()
        return lista.map { p ->
            val its = todosItens[p.id].orEmpty()
            val naRua = its.count { !it.devolvido }
            PedidoResumo(p, todasLinhas[p.id].orEmpty().sumOf { it.quantidade }, its.size, naRua, atraso(p, naRua, hoje))
        }
    }

    @Transactional(readOnly = true)
    fun aguardandoConferencia(): List<Pair<PedidoItemEntity, PedidoLocacaoEntity>> {
        val lista = itens.findAllByDevolvidoEmIsNotNullAndConferidoEmIsNullOrderByDevolvidoEmAsc()
        val ps = pedidos.findAllById(lista.map { it.pedidoId }.toSet()).associateBy { it.id }
        return lista.mapNotNull { i -> ps[i.pedidoId]?.let { i to it } }
    }

    fun contarAguardandoConferencia(): Long = itens.countByDevolvidoEmIsNotNullAndConferidoEmIsNull()

    fun contarAtrasados(): Long = pedidos.contarAtrasados(hoje())

    /**
     * Folha de separação (1.17.0): para cada linha que ainda falta, os equipamentos Disponíveis que servem, com o local
     * de cada um (pelo local, depois pelo patrimônio), até [MAXIMO_CANDIDATOS] por linha. Um mesmo equipamento aparece só
     * na primeira linha que combina (a específica antes da por tipo), como na distribuição da saída.
     */
    @Transactional(readOnly = true)
    fun separacao(pedidoId: Long): FolhaSeparacao {
        val d = detalhar(pedidoId)
        val disponiveis = tags.findAll().filter { it.situacao == SituacaoBem.DISPONIVEL }
        val usados = mutableSetOf<Long>()
        val linhas = d.linhas.sortedBy { if (it.linha.especifico) 0 else 1 }.map { lc ->
            val candidatos = if (lc.faltam == 0) emptyList() else disponiveis
                .filter { it.id !in usados && lc.linha.combina(it) }
                .let { lista -> transferencias?.descrever(lista) ?: lista.map { BemTransferivel(it.id, it.tagReal, it.patrimonio.ifBlank { null }, it.codigoInterno, null, null, null, it.situacao) } }
                .sortedWith(compareBy<BemTransferivel, String?>(nullsLast()) { it.local }.thenBy(nullsLast()) { it.patrimonio })
                .take(MAXIMO_CANDIDATOS)
            // Reserva na folha só o que a linha precisa: o resto fica livre para as próximas linhas
            usados += candidatos.take(lc.faltam).map { it.id }
            LinhaSeparacao(lc, candidatos)
        }.sortedBy { it.linha.linha.id }
        return FolhaSeparacao(d, linhas)
    }

    fun pedido(id: Long): PedidoLocacaoEntity = pedidos.findById(id).orElseThrow { NoSuchElementException("Pedido de locação nº $id não encontrado") }

    /* ================= INTERNOS ================= */

    /**
     * Distribui os equipamentos lidos nas linhas do pedido: primeiro nas de bem específico, depois na primeira linha por
     * tipo que combine e ainda tenha vaga. O que já saiu neste pedido conta como jaSaiu (não ocupa vaga nova).
     */
    private fun distribuir(p: PedidoLocacaoEntity, codigos: Collection<String>): Conferencia {
        val ls = linhas.findAllByPedidoIdOrderByIdAsc(p.id).sortedBy { if (it.especifico) 0 else 1 }
        val its = itens.findAllByPedidoIdOrderByIdAsc(p.id)
        val saidosPorLinha = its.groupingBy { it.linhaId }.eachCount()
        val vagas = ls.associate { it.id to (it.quantidade - (saidosPorLinha[it.id] ?: 0)) }.toMutableMap()
        val doPedido = its.filter { it.tagId != null }.associateBy { it.tagId!! }
        val conferidosPorLinha = mutableMapOf<Long, Int>()
        val resultado = limpar(codigos).map { codigo ->
            val tag = acharBem(codigo) ?: return@map ItemConferido(codigo, null, null, false, ProblemaLeitura.SEM_CADASTRO)
            doPedido[tag.id]?.let { return@map ItemConferido(codigo, tag, it.linhaId, true, null) }
            val candidatas = ls.filter { it.combina(tag) }
            if (candidatas.isEmpty()) return@map ItemConferido(codigo, tag, null, false, ProblemaLeitura.FORA_DO_PEDIDO)
            if (tag.situacao != SituacaoBem.DISPONIVEL) return@map ItemConferido(codigo, tag, candidatas.first().id, false, ProblemaLeitura.NAO_DISPONIVEL)
            val linha = candidatas.firstOrNull { (vagas[it.id] ?: 0) > 0 }
                ?: return@map ItemConferido(codigo, tag, candidatas.first().id, false, ProblemaLeitura.EXCEDENTE)
            vagas[linha.id] = vagas.getValue(linha.id) - 1
            conferidosPorLinha.merge(linha.id, 1, Int::plus)
            ItemConferido(codigo, tag, linha.id, false, null)
        }
        return Conferencia(resultado, ls.sortedBy { it.id }.map { LinhaConferida(it, saidosPorLinha[it.id] ?: 0, conferidosPorLinha[it.id] ?: 0) })
    }

    /** Situação do pedido pelo que saiu e voltou. [abrirSeparacao]: linha nova depois da separação concluída. */
    private fun recalcular(p: PedidoLocacaoEntity, abrirSeparacao: Boolean = false) {
        if (p.situacao == SituacaoPedido.CANCELADO) return
        val ls = linhas.findAllByPedidoIdOrderByIdAsc(p.id)
        val its = itens.findAllByPedidoIdOrderByIdAsc(p.id)
        val total = ls.sumOf { it.quantidade }
        if (abrirSeparacao && its.size < total) p.separacaoConcluida = false
        if (total > 0 && its.size >= total) p.separacaoConcluida = true
        val antes = p.situacao
        p.situacao = when {
            its.isEmpty() -> SituacaoPedido.ABERTO
            !p.separacaoConcluida -> SituacaoPedido.EM_SEPARACAO
            its.none { it.devolvido } -> SituacaoPedido.ENTREGUE
            its.all { it.devolvido } -> SituacaoPedido.ENCERRADO
            else -> SituacaoPedido.DEVOLVIDO_PARCIAL
        }
        pedidos.save(p)
        if (antes != p.situacao) log.info("Pedido de locação nº {}: {} → {}", p.id, antes.rotulo, p.situacao.rotulo)
    }

    private fun atraso(p: PedidoLocacaoEntity, naRua: Int, hoje: LocalDate): Long =
        if (naRua == 0) 0 else ChronoUnit.DAYS.between(p.previsaoDevolucao, hoje).coerceAtLeast(0)

    /** Etiqueta (EPC) ou, se não achar, patrimônio de um bem só. */
    private fun acharBem(codigo: String): TagEntity? =
        tags.findByTagReal(normalizarEpc(codigo)).orElse(null) ?: tags.findAllByPatrimonio(codigo.trim()).singleOrNull()

    private fun limpar(codigos: Collection<String>): List<String> {
        val limpos = codigos.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { normalizarEpc(it) }
        require(limpos.isNotEmpty()) { "Informe as etiquetas (ou patrimônios)" }
        require(limpos.size <= MAXIMO_LEITURAS) { "No máximo $MAXIMO_LEITURAS etiquetas por envio (vieram ${limpos.size})" }
        return limpos
    }

    /** "Coletor Zebra MC3300": os nomes do catálogo do equipamento. */
    private fun descricaoCatalogo(tag: TagEntity): String = listOfNotNull(
        tag.tipoEquipamentoId?.let { tipos.findById(it).orElse(null)?.nome },
        tag.marcaId?.let { marcas.findById(it).orElse(null)?.nome },
        tag.modeloId?.let { modelos.findById(it).orElse(null)?.nome }
    ).joinToString(" ")

    private fun descricaoBem(tag: TagEntity): String =
        (tag.patrimonio.ifBlank { tag.tagReal }) + descricaoCatalogo(tag).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()

    private fun autor(): String = historico.autor().first

    private fun texto(valor: String?, campo: String, maximo: Int): String? =
        valor?.trim()?.takeIf { it.isNotEmpty() }?.also { require(it.length <= maximo) { "$campo pode ter no máximo $maximo caracteres" } }

    companion object {
        const val POR_PAGINA = 50
        const val MAXIMO_LINHAS = 100
        const val MAXIMO_LEITURAS = 500
        const val MAXIMO_CANDIDATOS = 30
    }
}
