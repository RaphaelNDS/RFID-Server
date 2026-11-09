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

        if (local == null || referencia == null) {
            log.info("Coletor {} pediu o esperado do local '{}': {}, sem lista do que é esperado",
                dispositivo, limpo, if (local == null) "local ainda não usado em inventários" else "nenhum inventário finalizado")
            return EsperadoLocal(local = local?.nome ?: limpo, temReferencia = false)
        }

        val montado = montarEsperado(local.nome, local.id, referencia, ate = LocalDateTime.now(relogio))
        val esperado = montado.esperado
        log.info("Coletor {} pediu o esperado do local '{}': {} etiqueta(s) do inventário {} finalizado em {} (operador {}){}{}",
            dispositivo, local.nome, esperado.itens.size, referencia.id, referencia.finalizadoEm, referencia.operador ?: "—",
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
        ) ?: run {
            log.debug("Inventário {} ('{}') sem inventário finalizado anterior no local: sem comparação", inventarioId, nomeLocal)
            return FaltandoInventario(EsperadoLocal(local = nomeLocal, temReferencia = false))
        }

        // Bens achados em outro local entre a referência e o início deste inventário não contam como faltando aqui
        val montado = montarEsperado(nomeLocal, inventario.localId, referencia, ate = inventario.iniciadoEm)
        val esperado = montado.esperado
        val lidas = itens.tagsDoInventario(inventarioId).toSet()
        val faltando = esperado.itens.filter { it.tag !in lidas }

        log.debug("Inventário {} ('{}') comparado com o inventário {}: {} de {} esperada(s) encontrada(s), {} faltando, {} em outro local",
            inventarioId, nomeLocal, referencia.id, esperado.itens.size - faltando.size, esperado.itens.size, faltando.size, montado.emOutroLocal.size)

        return FaltandoInventario(esperado, esperado.itens.size, esperado.itens.size - faltando.size, faltando, montado.emOutroLocal)
    }

    private data class Montado(val esperado: EsperadoLocal, val excluidas: Int, val emOutroLocal: List<EncontradoEmOutroLocal>)

    /**
     * Lista do esperado a partir do inventário de referência. Devolve também quantas foram excluídas do cadastro depois
     * e as que foram encontradas DEPOIS da referência (até [ate]) num inventário finalizado de outro local: essas
     * ficam de fora (1.5.0). Antes, o bem levado da sala A para a sala B continuava no esperado da sala A e aparecia
     * como "faltando" lá no inventário seguinte. Funciona como uma transferência: o local do bem é o do inventário
     * finalizado mais recente que o encontrou.
     */
    private fun montarEsperado(nomeLocal: String, localId: Long, referencia: InventarioEntity, ate: LocalDateTime): Montado {
        // Todas as lidas na referência: o findAllByTagRealIn só devolve as que têm cadastro AGORA (inclusive as
        // cadastradas depois do inventário); as que seguem sem cadastro ficam de fora sozinhas
        val lidasNaReferencia = itens.findByInventarioIdOrderByLidoEmAsc(referencia.id)
        val encontradas = lidasNaReferencia.map { it.tag }.distinct()
        val cadastroAgora = if (encontradas.isEmpty()) emptyList() else encontradas.chunked(LOTE_IN).flatMap { tags.findAllByTagRealIn(it) }

        // Encontradas depois em outro local: vale o inventário mais recente de cada uma
        val desde = referencia.finalizadoEm ?: referencia.iniciadoEm
        val emOutro = if (cadastroAgora.isEmpty() || !ate.isAfter(desde)) emptyMap() else
            cadastroAgora.map { it.tagReal }.chunked(LOTE_IN)
                .flatMap { itens.encontradasEmOutroLocal(it, localId, desde, ate) }
                .groupBy { it[0] as String }
                .mapValues { (_, linhas) -> linhas.maxBy { it[2] as LocalDateTime }.let { (it[1] as String) to (it[2] as LocalDateTime) } }
        val cadastro = cadastroAgora.filter { it.tagReal !in emOutro }
        val comCadastroAgora = cadastro.map { it.tagReal }.toSet()
        // Excluídas = estavam cadastradas no inventário de referência e não estão mais
        val excluidas = lidasNaReferencia.count { it.cadastrada && it.tag !in comCadastroAgora && it.tag !in emOutro }

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
            temReferencia = true,
            inventarioReferencia = referencia.id,
            finalizadoEm = referencia.finalizadoEm?.atZone(ZoneId.systemDefault())?.toOffsetDateTime(),
            operador = referencia.operador,
            itens = esperados
        )
        return Montado(esperado, excluidas, movidas)
    }

    private companion object {
        /** Etiquetas por consulta com IN: o driver do PostgreSQL aceita no máximo 32.767 parâmetros por comando. */
        const val LOTE_IN = 5_000
    }
}

/** Bem do inventário de referência encontrado depois num inventário finalizado de outro local. */
data class EncontradoEmOutroLocal(val item: ItemEsperado, val local: String, val encontradoEm: LocalDateTime)

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
