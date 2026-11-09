package org.example.service

import org.example.model.InventarioEntity
import org.example.model.StatusInventario
import org.example.repository.InventarioItemRepository
import org.example.repository.InventarioRepository
import org.example.repository.LocalRepository
import org.example.repository.MarcaRepository
import org.example.repository.ModeloRepository
import org.example.repository.TagRepository
import org.example.repository.TipoEquipamentoRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Uma etiqueta que deveria estar no local, com os dados ATUAIS do cadastro (para o coletor localizar). */
data class ItemEsperado(
    val tag: String,
    val patrimonio: String?,
    val codigoInterno: String?,
    val tipo: String?,
    val marca: String?,
    val modelo: String?
)

/**
 * O que é esperado num local, para o coletor mostrar ao vivo o que falta durante o inventário.
 * [temReferencia] = false: o local nunca teve inventário finalizado (primeiro inventário dele), então
 * não há lista. Não é o mesmo que "nada esperado".
 */
data class EsperadoLocal(
    val local: String,
    val temReferencia: Boolean,
    val inventarioReferencia: String? = null,
    val finalizadoEm: OffsetDateTime? = null,
    val operador: String? = null,
    val itens: List<ItemEsperado> = emptyList()
)

/**
 * Lista do que é esperado num local = as etiquetas encontradas no último inventário FINALIZADO daquele local que
 * estão CADASTRADAS AGORA (decisão do usuário: não há campo de local no cadastro da etiqueta).
 *
 * Regras:
 *  - vale o cadastro de agora, e não a marca "cadastrada" do item do inventário: etiqueta que ainda não tinha
 *    cadastro no inventário de referência e foi cadastrada depois entra; a que segue sem cadastro não entra
 *    (não há o que localizar);
 *  - etiqueta que foi EXCLUÍDA do cadastro depois não entra (o patrimônio deixou de existir);
 *  - os dados (patrimônio, tipo, marca, modelo) são os do cadastro de AGORA, e não a cópia do inventário,
 *    para o coletor localizar pelo patrimônio atual. Cadastro incompleto continua entrando (o objeto existe),
 *    só com os campos que houver.
 */
@Service
class EsperadoInventarioService(
    private val locais: LocalRepository,
    private val inventarios: InventarioRepository,
    private val itens: InventarioItemRepository,
    private val tags: TagRepository,
    private val tipos: TipoEquipamentoRepository,
    private val marcas: MarcaRepository,
    private val modelos: ModeloRepository,
    private val relogio: java.time.Clock = java.time.Clock.systemDefaultZone()
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun esperadoNoLocal(nomeLocal: String, dispositivo: String): EsperadoLocal {
        val limpo = limparNomeLocal(nomeLocal)
        require(limpo.isNotEmpty()) { "Informe o local do inventário" }

        val local = locais.findFirstByNomeIgnoreCaseOrderByIdAsc(limpo).orElse(null)
        val referencia = local?.let { inventarios.findFirstByLocalIdAndStatusOrderByFinalizadoEmDesc(it.id, StatusInventario.FINALIZADO) }

        if (local == null) {
            log.info("Coletor {} pediu o esperado do local '{}': local ainda não usado em inventários, sem lista do que é esperado", dispositivo, limpo)
            return EsperadoLocal(local = limpo, temReferencia = false)
        }

        val montado = montarEsperado(local.nome, local.id, referencia, ate = LocalDateTime.now(relogio))
        val esperado = montado.esperado
        if (!esperado.temReferencia) {
            log.info("Coletor {} pediu o esperado do local '{}': nenhum inventário finalizado nem bem cadastrado no local, sem lista", dispositivo, local.nome)
            return esperado
        }
        log.info("Coletor {} pediu o esperado do local '{}': {} etiqueta(s) ({} do cadastro; referência: inventário {} finalizado em {}, operador {}){}{}",
            dispositivo, local.nome, esperado.itens.size, montado.doCadastro, referencia?.id ?: "nenhum", referencia?.finalizadoEm ?: "—", referencia?.operador ?: "—",
            if (montado.excluidas > 0) "; ${montado.excluidas} excluída(s) do cadastro depois ficaram de fora" else "",
            if (montado.emOutroLocal.isNotEmpty()) "; ${montado.emOutroLocal.size} encontrada(s) depois em outro local ficaram de fora" else "")

        return esperado
    }

    /**
     * O que faltou no inventário [inventarioId], comparando com o último inventário finalizado do mesmo local
     * ANTES do início dele (tela de detalhe e exportação). Um inventário antigo compara com o que veio antes dele,
     * e não com o mais recente. Em andamento: o que falta até agora.
     */
    @Transactional(readOnly = true)
    fun faltandoNoInventario(inventarioId: String): FaltandoInventario {
        val inventario = inventarios.findById(inventarioId).orElseThrow { NoSuchElementException("Inventário $inventarioId não encontrado") }
        val nomeLocal = locais.findById(inventario.localId).map { it.nome }.orElse("")

        val referencia = inventarios.findFirstByLocalIdAndStatusAndFinalizadoEmBeforeAndIdNotOrderByFinalizadoEmDescIdDesc(
            inventario.localId, StatusInventario.FINALIZADO, inventario.iniciadoEm, inventario.id
        )

        // Bens achados em outro local entre a referência e o início deste inventário não contam como faltando aqui
        val montado = montarEsperado(nomeLocal, inventario.localId, referencia, ate = inventario.iniciadoEm)
        val esperado = montado.esperado
        if (!esperado.temReferencia) {
            log.debug("Inventário {} ('{}') sem inventário finalizado anterior nem bem cadastrado no local: sem comparação", inventarioId, nomeLocal)
            return FaltandoInventario(esperado)
        }
        val lidas = itens.tagsDoInventario(inventarioId).toSet()
        val faltando = esperado.itens.filter { it.tag !in lidas }
        // Primeiro inventário do local: finalizá-lo põe no local os bens que ele leu, e a comparação com o cadastro seria
        // com ele mesmo. Sem referência e sem nada faltando, continua "primeiro inventário, sem comparação"
        if (referencia == null && faltando.isEmpty()) return FaltandoInventario(EsperadoLocal(local = nomeLocal, temReferencia = false))

        log.debug("Inventário {} ('{}') comparado com o inventário {} e o cadastro: {} de {} esperada(s) encontrada(s), {} faltando, {} em outro local",
            inventarioId, nomeLocal, referencia?.id ?: "nenhum", esperado.itens.size - faltando.size, esperado.itens.size, faltando.size, montado.emOutroLocal.size)

        return FaltandoInventario(esperado, esperado.itens.size, esperado.itens.size - faltando.size, faltando, montado.emOutroLocal)
    }

    private data class Montado(val esperado: EsperadoLocal, val excluidas: Int, val emOutroLocal: List<EncontradoEmOutroLocal>, val doCadastro: Int = 0)

    /**
     * Lista do esperado a partir do inventário de referência. Devolve também quantas foram excluídas do cadastro depois
     * e as que foram encontradas DEPOIS da referência (até [ate]) num inventário finalizado de outro local: essas
     * ficam de fora (1.5.0). Antes, o bem levado da sala A para a sala B continuava no esperado da sala A e aparecia
     * como "faltando" lá no inventário seguinte. Funciona como uma transferência: o local do bem é o do inventário
     * finalizado mais recente que o encontrou.
     */
    private fun montarEsperado(nomeLocal: String, localId: Long, referencia: InventarioEntity?, ate: LocalDateTime): Montado {
        // Todas as lidas na referência: o findAllByTagRealIn só devolve as que têm cadastro AGORA (inclusive as
        // cadastradas depois do inventário); as que seguem sem cadastro ficam de fora sozinhas
        val lidasNaReferencia = referencia?.let { itens.findByInventarioIdOrderByLidoEmAsc(it.id) }.orEmpty()
        val encontradas = lidasNaReferencia.map { it.tag }.distinct()
        val daReferencia = if (encontradas.isEmpty()) emptyList() else encontradas.chunked(LOTE_IN).flatMap { tags.findAllByTagRealIn(it) }
        val existentesAgora = daReferencia.map { it.tagReal }.toSet()
        // 1.6.0: fora da referência ficam as baixadas e as que o cadastro põe em outro local (o local do bem é o do
        // cadastro, atualizado também pelos inventários finalizados). Entram as cadastradas neste local, mesmo sem
        // terem aparecido na referência (bem novo, ou local que ainda não teve inventário)
        val doCadastro = tags.findAllByLocalIdAndSituacaoNot(localId, org.example.model.SituacaoBem.BAIXADO)
        val noCadastro = doCadastro.map { it.tagReal }.toSet()
        val referenciaNaoBaixada = daReferencia.filter { !it.baixado && it.tagReal !in noCadastro }

        // Em outro local: o cadastro põe o bem em outro local (mudado na web, ou por um inventário finalizado de lá), ou,
        // sem local no cadastro, um inventário finalizado de outro local o encontrou depois da referência
        val desde = referencia?.let { it.finalizadoEm ?: it.iniciadoEm }
        val achadasDepois: Map<String, Pair<String, LocalDateTime>> = if (desde == null || referenciaNaoBaixada.isEmpty() || !ate.isAfter(desde)) emptyMap() else
            referenciaNaoBaixada.map { it.tagReal }.chunked(LOTE_IN)
                .flatMap { itens.encontradasEmOutroLocal(it, localId, desde, ate) }
                .groupBy { it[0] as String }
                .mapValues { (_, linhas) -> linhas.maxBy { it[2] as LocalDateTime }.let { (it[1] as String) to (it[2] as LocalDateTime) } }
        val nomesLocais = referenciaNaoBaixada.mapNotNull { it.localId }.toSet().let { ids -> if (ids.isEmpty()) emptyMap() else locais.findAllById(ids).associate { it.id to it.nome } }
        val emOutro: Map<String, Pair<String, LocalDateTime?>> = referenciaNaoBaixada.mapNotNull { t ->
            val achada = achadasDepois[t.tagReal]
            when {
                t.localId != null && t.localId != localId -> t.tagReal to ((nomesLocais[t.localId] ?: achada?.first ?: "?") to achada?.second)
                t.localId == null && achada != null -> t.tagReal to (achada.first to achada.second)
                else -> null
            }
        }.toMap()
        val cadastro = (referenciaNaoBaixada.filter { it.tagReal !in emOutro } + doCadastro).distinctBy { it.tagReal }
        val cadastroAgora = cadastro + referenciaNaoBaixada.filter { it.tagReal in emOutro }
        // Excluídas = estavam cadastradas no inventário de referência e não estão mais no cadastro
        val excluidas = lidasNaReferencia.count { it.cadastrada && it.tag !in existentesAgora }

        val nomesTipo = tipos.findAllById(cadastroAgora.mapNotNull { it.tipoEquipamentoId }.toSet()).associate { it.id to it.nome }
        val nomesMarca = marcas.findAllById(cadastroAgora.mapNotNull { it.marcaId }.toSet()).associate { it.id to it.nome }
        val nomesModelo = modelos.findAllById(cadastroAgora.mapNotNull { it.modeloId }.toSet()).associate { it.id to it.nome }
        fun item(t: org.example.model.TagEntity) = ItemEsperado(
            tag = t.tagReal,
            patrimonio = t.patrimonio.ifBlank { null },
            codigoInterno = t.codigoInterno.ifBlank { null },
            tipo = t.tipoEquipamentoId?.let(nomesTipo::get),
            marca = t.marcaId?.let(nomesMarca::get),
            modelo = t.modeloId?.let(nomesModelo::get)
        )
        // Desempate pela etiqueta: a ordem tem que ser sempre a mesma para o ETag do esperado (InventarioAndroidController)
        // só mudar quando o conteúdo muda. Sem ele, patrimônio repetido ou vazio saía na ordem que o banco devolvesse
        val ordem = compareBy<ItemEsperado, String?>(nullsLast()) { it.patrimonio }.thenBy { it.tag }

        val esperados = cadastro.map(::item).sortedWith(ordem)
        val movidas = cadastroAgora.filter { it.tagReal in emOutro }.map(::item).sortedWith(ordem)
            .map { emOutro.getValue(it.tag).let { (local, quando) -> EncontradoEmOutroLocal(it, local, quando) } }

        val esperado = EsperadoLocal(
            local = nomeLocal,
            temReferencia = referencia != null || doCadastro.isNotEmpty(),
            inventarioReferencia = referencia?.id,
            finalizadoEm = referencia?.finalizadoEm?.atZone(ZoneId.systemDefault())?.toOffsetDateTime(),
            operador = referencia?.operador,
            itens = esperados
        )
        return Montado(esperado, excluidas, movidas, doCadastro.size)
    }

    private companion object {
        /** Etiquetas por consulta com IN: o driver do PostgreSQL aceita no máximo 32.767 parâmetros por comando. */
        const val LOTE_IN = 5_000
    }
}

/**
 * Bem do inventário de referência que está em outro local: encontrado depois num inventário finalizado de lá, ou
 * mudado de local no cadastro. [encontradoEm] null = mudado na web, sem inventário que o tenha encontrado lá.
 */
data class EncontradoEmOutroLocal(val item: ItemEsperado, val local: String, val encontradoEm: LocalDateTime?)

/**
 * Comparação de um inventário com o inventário finalizado anterior do mesmo local.
 * [referencia].temReferencia = false: primeiro inventário do local, sem comparação.
 * [emOutroLocal]: os da referência que foram achados depois em outro local (não contam como faltando).
 */
data class FaltandoInventario(
    val referencia: EsperadoLocal,
    val esperadas: Int = 0,
    val encontradas: Int = 0,
    val faltando: List<ItemEsperado> = emptyList(),
    val emOutroLocal: List<EncontradoEmOutroLocal> = emptyList()
)
