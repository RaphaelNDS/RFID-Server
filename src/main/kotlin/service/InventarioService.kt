package org.example.service

import org.springframework.transaction.annotation.Transactional
import org.example.model.InventarioEntity
import org.example.model.InventarioItemEntity
import org.example.model.LocalEntity
import org.example.model.StatusInventario
import org.example.repository.InventarioItemRepository
import org.example.repository.InventarioRepository
import org.example.repository.LocalRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/** Linha da lista de inventários (tela web). */
data class InventarioResumo(
    val id: String,
    val local: String,
    val dispositivo: String,
    val status: StatusInventario,
    val iniciadoEm: LocalDateTime,
    val finalizadoEm: LocalDateTime?,
    val totalLidas: Long,
    val operador: String? = null,
    val cadastradas: Long
) {
    val naoCadastradas: Long get() = totalLidas - cadastradas
}

data class InventarioDetalhe(
    val resumo: InventarioResumo,
    val itens: List<InventarioItemEntity>
)

data class ItemLido(val tag: String, val lidoEm: OffsetDateTime?)

data class ResultadoItens(val recebidos: Int, val novos: Int)

/**
 * Inventário por local feito pelo coletor: abre (num local), recebe as etiquetas lidas e finaliza.
 * Todas as operações podem ser repetidas sem duplicar nada, porque o coletor reenvia o que não teve
 * confirmação (ex.: a rede caiu depois de o servidor gravar).
 */
@Service
class InventarioService(
    private val locais: LocalRepository,
    private val inventarios: InventarioRepository,
    private val itens: InventarioItemRepository,
    private val rfidService: RfidService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun listarLocais(): List<String> = locais.findAllByOrderByNomeAsc().map { it.nome }

    @Transactional
    fun abrir(id: String, nomeLocal: String, iniciadoEm: OffsetDateTime?, dispositivo: String, operador: String? = null): InventarioEntity {
        validarId(id)

        inventarios.findById(id).orElse(null)?.let {
            log.debug("Inventário {} reenviado pelo dispositivo {}: já existia", id, dispositivo)
            return it
        }

        val local = localPorNome(nomeLocal)
        val inventario = inventarios.save(
            InventarioEntity(
                id = id,
                localId = local.id,
                dispositivo = dispositivo,
                iniciadoEm = horaDoColetor(iniciadoEm, "abertura do inventário $id"),
                operador = operador
            )
        )

        log.info("Inventário {} aberto no local '{}' pelo dispositivo {} (operador {})", id, local.nome, dispositivo, operador ?: "—")
        return inventario
    }

    @Transactional
    fun registrarItens(id: String, lidos: List<ItemLido>, dispositivo: String, operador: String? = null): ResultadoItens {
        val inventario = buscar(id)

        require(lidos.size <= MAXIMO_ITENS_POR_ENVIO) { "Envie no máximo $MAXIMO_ITENS_POR_ENVIO etiquetas por vez" }
        require(lidos.all { it.tag.isNotBlank() }) { "Informe a TAG RFID de todos os itens" }

        if (inventario.status == StatusInventario.FINALIZADO) {
            // Leituras feitas antes da finalização que chegaram depois dela (ex.: reenvio): aceita
            log.info("Inventário {} já finalizado recebendo {} etiqueta(s) atrasada(s) do dispositivo {}", id, lidos.size, dispositivo)
        }

        val jaRegistradas = itens.tagsDoInventario(id).toMutableSet()
        var novos = 0

        for (lido in lidos.distinctBy { it.tag }) {
            if (!jaRegistradas.add(lido.tag)) continue

            val cadastro = rfidService.buscarPorTag(lido.tag)
            itens.save(
                InventarioItemEntity(
                    inventarioId = id,
                    tag = lido.tag,
                    lidoEm = horaDoColetor(lido.lidoEm, "leitura da tag ${lido.tag} no inventário $id"),
                    cadastrada = cadastro != null,
                    codigoInterno = cadastro?.codigoInterno,
                    patrimonio = cadastro?.patrimonio,
                    tipo = cadastro?.tipo,
                    marca = cadastro?.marca,
                    modelo = cadastro?.modelo,
                    operador = operador
                )
            )
            novos++
        }

        log.info(
            "Inventário {}: {} etiqueta(s) recebida(s) do dispositivo {}, {} nova(s), total {}",
            id, lidos.size, dispositivo, novos, jaRegistradas.size
        )
        return ResultadoItens(recebidos = lidos.size, novos = novos)
    }

    @Transactional
    fun finalizar(id: String, finalizadoEm: OffsetDateTime?, dispositivo: String): InventarioEntity {
        val inventario = buscar(id)

        if (inventario.status == StatusInventario.FINALIZADO) {
            log.debug("Finalização do inventário {} reenviada pelo dispositivo {}: já estava finalizado", id, dispositivo)
            return inventario
        }

        inventario.status = StatusInventario.FINALIZADO
        inventario.finalizadoEm = horaDoColetor(finalizadoEm, "finalização do inventário $id")

        val total = itens.tagsDoInventario(id).size
        log.info("Inventário {} finalizado pelo dispositivo {}: {} etiqueta(s) lida(s)", id, dispositivo, total)
        return inventarios.save(inventario)
    }

    fun listar(localId: Long? = null): List<InventarioResumo> {
        val lista = if (localId == null) inventarios.findAllByOrderByIniciadoEmDesc()
        else inventarios.findByLocalIdOrderByIniciadoEmDesc(localId)

        val nomes = locais.findAllById(lista.map { it.localId }.toSet()).associate { it.id to it.nome }
        val contagens = itens.contarPorInventario().associate {
            (it[0] as String) to Pair((it[1] as Number).toLong(), (it[2] as Number?)?.toLong() ?: 0L)
        }

        return lista.map { resumo(it, nomes[it.localId].orEmpty(), contagens[it.id]) }
    }

    fun detalhar(id: String): InventarioDetalhe {
        val inventario = buscar(id)
        val lista = itens.findByInventarioIdOrderByLidoEmAsc(id)
        val nome = locais.findById(inventario.localId).map { it.nome }.orElse("")

        return InventarioDetalhe(
            resumo = resumo(inventario, nome, Pair(lista.size.toLong(), lista.count { it.cadastrada }.toLong())),
            itens = lista
        )
    }

    fun locaisCadastrados(): List<LocalEntity> = locais.findAllByOrderByNomeAsc()

    // ================= INTERNOS =================

    private fun resumo(i: InventarioEntity, local: String, contagem: Pair<Long, Long>?) = InventarioResumo(
        id = i.id,
        local = local,
        dispositivo = i.dispositivo,
        status = i.status,
        iniciadoEm = i.iniciadoEm,
        finalizadoEm = i.finalizadoEm,
        totalLidas = contagem?.first ?: 0,
        cadastradas = contagem?.second ?: 0,
        operador = i.operador
    )

    private fun buscar(id: String): InventarioEntity =
        inventarios.findById(id).orElseThrow { NoSuchElementException("Inventário $id não encontrado") }

    /** Mesmo local com grafia diferente ("sala 101", " Sala 101 ") vira um só. */
    private fun localPorNome(nome: String): LocalEntity {
        val limpo = nome.trim().replace(Regex("\\s+"), " ")

        require(limpo.isNotEmpty()) { "Informe o local do inventário" }
        require(limpo.length <= TAMANHO_MAXIMO_LOCAL) { "O nome do local pode ter no máximo $TAMANHO_MAXIMO_LOCAL caracteres" }

        return locais.findByNomeIgnoreCase(limpo).orElseGet {
            log.info("Novo local cadastrado pelo inventário: '{}'", limpo)
            locais.save(LocalEntity(nome = limpo))
        }
    }

    private fun validarId(id: String) {
        try {
            UUID.fromString(id)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Identificador do inventário inválido: use um UUID")
        }
    }

    /**
     * Hora informada pelo coletor (pode ter sido offline), no fuso do servidor. Sem ela, ou no
     * futuro (relógio do coletor adiantado), vale a hora do recebimento.
     */
    private fun horaDoColetor(hora: OffsetDateTime?, descricao: String): LocalDateTime {
        val agora = LocalDateTime.now()
        if (hora == null) return agora

        val local = hora.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()

        if (local.isAfter(agora.plus(TOLERANCIA_RELOGIO))) {
            log.warn("Data no futuro na {} ({}): relógio do coletor adiantado? Usando a hora do recebimento", descricao, hora)
            return agora
        }

        return local
    }

    private companion object {
        const val MAXIMO_ITENS_POR_ENVIO = 500
        const val TAMANHO_MAXIMO_LOCAL = 120
        val TOLERANCIA_RELOGIO: Duration = Duration.ofMinutes(5)
    }
}
