package org.example.service

import org.example.repository.InventarioItemRepository
import org.example.repository.LeituraAndroidRepository
import org.example.response.TagResponse
import org.example.util.normalizarEpc
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Ficha do bem no coletor (GET /api/android/tags/{epc}, 1.5.0): além do cadastro, onde o bem foi visto por último.
 *  - último local: o do último inventário FINALIZADO em que a etiqueta foi encontrada (o inventário em andamento
 *    ainda pode ser cancelado no coletor);
 *  - última leitura: a mais recente da etiqueta (avulsa ou em lote) e o operador dela.
 * Tudo opcional: sem inventário ou sem leitura, o campo vai null.
 */
@Service
class FichaBemService(
    private val rfid: RfidService,
    private val itens: InventarioItemRepository,
    private val leituras: LeituraAndroidRepository
) {

    @Transactional(readOnly = true)
    fun ficha(epc: String): TagResponse? {
        val cadastro = rfid.buscarPorTag(epc) ?: return null
        val tag = normalizarEpc(epc)

        val inventario = itens.ultimoInventarioDaTag(tag, PageRequest.of(0, 1)).firstOrNull()
        val leitura = leituras.findFirstByTagOrderByDataHoraDesc(tag)

        return cadastro.copy(
            ultimoLocal = inventario?.get(0) as String?,
            ultimoInventarioEm = (inventario?.get(1) as LocalDateTime?)?.let(::comFuso),
            ultimaLeituraEm = leitura?.let { comFuso(it.dataHora) },
            ultimaLeituraPor = leitura?.operador
        )
    }

    private fun comFuso(momento: LocalDateTime): OffsetDateTime = momento.atZone(ZoneId.systemDefault()).toOffsetDateTime()
}
