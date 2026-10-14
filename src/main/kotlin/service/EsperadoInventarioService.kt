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
 * Lista do que é esperado num local = as etiquetas CADASTRADAS encontradas no último inventário FINALIZADO
 * daquele local (decisão do usuário: não há campo de local no cadastro da etiqueta).
 *
 * Regras:
 *  - etiquetas não cadastradas no inventário de referência não entram (não há o que localizar);
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
    private val modelos: ModeloRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun esperadoNoLocal(nomeLocal: String, dispositivo: String): EsperadoLocal {
        val limpo = limparNomeLocal(nomeLocal)
        require(limpo.isNotEmpty()) { "Informe o local do inventário" }

        val local = locais.findByNomeIgnoreCase(limpo).orElse(null)
        val referencia = local?.let { inventarios.findFirstByLocalIdAndStatusOrderByFinalizadoEmDesc(it.id, StatusInventario.FINALIZADO) }

        if (local == null || referencia == null) {
            log.info("Coletor {} pediu o esperado do local '{}': {}, sem lista do que é esperado",
                dispositivo, limpo, if (local == null) "local ainda não usado em inventários" else "nenhum inventário finalizado")
            return EsperadoLocal(local = local?.nome ?: limpo, temReferencia = false)
        }

        val (esperado, excluidas) = montarEsperado(local.nome, referencia)
        log.info("Coletor {} pediu o esperado do local '{}': {} etiqueta(s) do inventário {} finalizado em {} (operador {}){}",
            dispositivo, local.nome, esperado.itens.size, referencia.id, referencia.finalizadoEm, referencia.operador ?: "—",
            if (excluidas > 0) "; $excluidas excluída(s) do cadastro depois ficaram de fora" else "")

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

        val (esperado, _) = montarEsperado(nomeLocal, referencia)
        val lidas = itens.tagsDoInventario(inventarioId).toSet()
        val faltando = esperado.itens.filter { it.tag !in lidas }

        log.debug("Inventário {} ('{}') comparado com o inventário {}: {} de {} esperada(s) encontrada(s), {} faltando",
            inventarioId, nomeLocal, referencia.id, esperado.itens.size - faltando.size, esperado.itens.size, faltando.size)

        return FaltandoInventario(esperado, esperado.itens.size, esperado.itens.size - faltando.size, faltando)
    }

    /** Lista do esperado a partir do inventário de referência. Devolve também quantas foram excluídas do cadastro depois. */
    private fun montarEsperado(nomeLocal: String, referencia: InventarioEntity): Pair<EsperadoLocal, Int> {
        val encontradas = itens.findByInventarioIdOrderByLidoEmAsc(referencia.id).filter { it.cadastrada }.map { it.tag }
        val cadastro = if (encontradas.isEmpty()) emptyList() else tags.findAllByTagRealIn(encontradas)

        val nomesTipo = tipos.findAllById(cadastro.mapNotNull { it.tipoEquipamentoId }.toSet()).associate { it.id to it.nome }
        val nomesMarca = marcas.findAllById(cadastro.mapNotNull { it.marcaId }.toSet()).associate { it.id to it.nome }
        val nomesModelo = modelos.findAllById(cadastro.mapNotNull { it.modeloId }.toSet()).associate { it.id to it.nome }

        val esperados = cadastro
            .map {
                ItemEsperado(
                    tag = it.tagReal,
                    patrimonio = it.patrimonio.ifBlank { null },
                    codigoInterno = it.codigoInterno.ifBlank { null },
                    tipo = it.tipoEquipamentoId?.let(nomesTipo::get),
                    marca = it.marcaId?.let(nomesMarca::get),
                    modelo = it.modeloId?.let(nomesModelo::get)
                )
            }
            .sortedWith(compareBy(nullsLast()) { it.patrimonio })

        val esperado = EsperadoLocal(
            local = nomeLocal,
            temReferencia = true,
            inventarioReferencia = referencia.id,
            finalizadoEm = referencia.finalizadoEm?.atZone(ZoneId.systemDefault())?.toOffsetDateTime(),
            operador = referencia.operador,
            itens = esperados
        )
        return esperado to (encontradas.size - esperados.size)
    }
}

/**
 * Comparação de um inventário com o inventário finalizado anterior do mesmo local.
 * [referencia].temReferencia = false: primeiro inventário do local, sem comparação.
 */
data class FaltandoInventario(
    val referencia: EsperadoLocal,
    val esperadas: Int = 0,
    val encontradas: Int = 0,
    val faltando: List<ItemEsperado> = emptyList()
)
