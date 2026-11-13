package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.EntregaFotoEntity
import org.example.model.EntregaPedidoEntity
import org.example.model.TipoAnexo
import org.example.repository.EntregaFotoRepository
import org.example.repository.EntregaPedidoRepository
import org.example.repository.PedidoItemRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.Base64

/** Uma foto enviada na entrega (nome do arquivo e bytes). */
class FotoEnviada(val nome: String?, val bytes: ByteArray)

/** Um comprovante na tela, com os ids das fotos (sem os bytes). */
data class EntregaResumo(val entrega: EntregaPedidoEntity, val fotos: List<Long>)

/**
 * Comprovante de entrega do pedido de locação (1.23.0). Na entrega, quem recebe assina na tela (celular ou tablet) e
 * tira-se foto dos equipamentos no local; serve de prova numa discussão de avaria ou de quantidade. Guarda quem recebeu,
 * o documento, a assinatura (PNG), até [maximoFotos] fotos (JPEG, PNG ou WebP, reconhecidas pelos primeiros bytes) e a
 * lista dos equipamentos que estavam com o cliente na hora. Tudo no banco (V37), para ir no backup.
 */
@Service
class EntregaService(
    private val entregas: EntregaPedidoRepository,
    private val fotos: EntregaFotoRepository,
    private val itens: PedidoItemRepository,
    private val locacao: LocacaoService,
    private val historico: HistoricoEtiquetaService,
    private val auditoria: AuditoriaService,
    private val relogio: Clock,
    @Value("\${rfid.entregas.max-foto-bytes:10485760}") val maximoFotoBytes: Long = 10L * 1024 * 1024,
    @Value("\${rfid.entregas.max-fotos:5}") val maximoFotos: Int = 5
) {

    private val log = LoggerFactory.getLogger(javaClass)

    val maximoFotoRotulo: String get() = "${maximoFotoBytes / (1024 * 1024)} MB"

    @Transactional(readOnly = true)
    fun doPedido(pedidoId: Long): List<EntregaResumo> {
        val lista = entregas.findAllByPedidoIdOrderByIdAsc(pedidoId)
        if (lista.isEmpty()) return emptyList()
        val ids = fotos.idsPorEntrega(lista.map { it.id }).groupBy({ (it[1] as Number).toLong() }, { (it[0] as Number).toLong() })
        return lista.map { EntregaResumo(it, ids[it.id].orEmpty()) }
    }

    /** O que está com o cliente agora (saiu e não voltou), um por linha: o que o comprovante vai listar. */
    @Transactional(readOnly = true)
    fun naRua(pedidoId: Long): List<String> =
        itens.findAllByPedidoIdOrderByIdAsc(pedidoId).filter { it.devolvidoEm == null }
            .map { listOfNotNull(it.patrimonio ?: it.tagReal, it.descricao).joinToString(" · ") }

    @Transactional
    fun registrar(
        pedidoId: Long, recebidoPor: String?, documento: String?, observacao: String?, assinaturaDataUrl: String?,
        enviadas: List<FotoEnviada>
    ): EntregaPedidoEntity {
        val pedido = locacao.pedido(pedidoId)
        require(!pedido.situacao.finalizado) { "O pedido nº $pedidoId está ${pedido.situacao.rotulo.lowercase()}: não recebe comprovante de entrega" }
        val equipamentos = naRua(pedidoId)
        require(equipamentos.isNotEmpty()) { "Nenhum equipamento deste pedido está com o cliente: registre a saída antes do comprovante" }
        val nome = texto(recebidoPor, "O nome de quem recebeu", 160) ?: throw IllegalArgumentException("Informe o nome de quem recebeu")
        val doc = texto(documento, "O documento", 40)
        val obs = texto(observacao, "A observação", 500)
        val assinatura = lerAssinatura(assinaturaDataUrl)
        val validas = enviadas.filter { it.bytes.isNotEmpty() }
        require(validas.size <= maximoFotos) { "No máximo $maximoFotos fotos por comprovante" }
        val tipos = validas.map { f ->
            require(f.bytes.size <= maximoFotoBytes) { "A foto ${f.nome ?: ""} passa de $maximoFotoRotulo" }
            TipoAnexo.detectar(f.bytes)?.takeIf { it.imagem } ?: throw IllegalArgumentException("A foto ${f.nome ?: ""} não é JPEG, PNG nem WebP")
        }
        val usuario = historico.autor().first
        val e = entregas.save(EntregaPedidoEntity(
            pedidoId = pedidoId, recebidoPor = nome, documento = doc, observacao = obs,
            equipamentos = equipamentos.joinToString("\n").take(8000), assinatura = assinatura,
            registradaPor = usuario, registradaEm = LocalDateTime.now(relogio)
        ))
        validas.zip(tipos).forEach { (f, t) -> fotos.save(EntregaFotoEntity(entregaId = e.id, tipo = t, tamanho = f.bytes.size, conteudo = f.bytes)) }
        log.info("Comprovante de entrega nº {} do pedido de locação nº {} registrado por {}: recebido por {}, {} equipamento(s), {} foto(s)",
            e.id, pedidoId, usuario, nome, equipamentos.size, validas.size)
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Comprovante de entrega", "Pedido nº $pedidoId",
            "Recebido por $nome: ${equipamentos.size} equipamento(s), ${validas.size} foto(s)")
        return e
    }

    @Transactional(readOnly = true)
    fun entrega(pedidoId: Long, entregaId: Long): EntregaPedidoEntity =
        entregas.findById(entregaId).orElse(null)?.takeIf { it.pedidoId == pedidoId }
            ?: throw NoSuchElementException("Comprovante $entregaId não encontrado no pedido nº $pedidoId")

    @Transactional(readOnly = true)
    fun foto(pedidoId: Long, entregaId: Long, fotoId: Long): EntregaFotoEntity {
        entrega(pedidoId, entregaId)
        return fotos.findById(fotoId).orElse(null)?.takeIf { it.entregaId == entregaId }
            ?: throw NoSuchElementException("Foto $fotoId não encontrada no comprovante $entregaId")
    }

    @Transactional(readOnly = true)
    fun fotosDa(entregaId: Long): List<EntregaFotoEntity> = fotos.findAllByEntregaIdOrderByIdAsc(entregaId)

    /** "data:image/png;base64,...", desenhada no navegador: só PNG, até 1 MB. */
    private fun lerAssinatura(dataUrl: String?): ByteArray {
        val texto = dataUrl?.trim().orEmpty()
        require(texto.isNotEmpty()) { "Falta a assinatura de quem recebeu" }
        require(texto.startsWith(PREFIXO_PNG)) { "Assinatura inválida" }
        val bytes = try {
            Base64.getDecoder().decode(texto.removePrefix(PREFIXO_PNG))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Assinatura inválida")
        }
        require(bytes.size <= 1024 * 1024) { "Assinatura grande demais" }
        require(TipoAnexo.detectar(bytes) == TipoAnexo.PNG) { "Assinatura inválida" }
        return bytes
    }

    private fun texto(valor: String?, campo: String, maximo: Int): String? =
        valor?.trim()?.takeIf { it.isNotEmpty() }?.also { require(it.length <= maximo) { "$campo pode ter no máximo $maximo caracteres" } }

    companion object {
        const val PREFIXO_PNG = "data:image/png;base64,"

        /** Imagem como data URI, para o PDF. */
        fun dataUri(tipo: TipoAnexo, bytes: ByteArray): String = "data:${tipo.contentType};base64," + Base64.getEncoder().encodeToString(bytes)
    }
}
