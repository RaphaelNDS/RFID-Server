package org.example.service

import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ItemCatalogo(val id: Long, val nome: String)
data class MarcaCatalogo(val id: Long, val nome: String, val modelos: List<ItemCatalogo>)
data class TipoCatalogo(val id: Long, val nome: String, val marcas: List<MarcaCatalogo>)

/** O que o coletor recebe para montar o formulário de cadastro (e guardar para usar offline). */
data class CatalogoColetor(val podeCadastrar: Boolean, val tipos: List<TipoCatalogo>)

enum class ResultadoCadastroColetor { CADASTRADA, JA_CADASTRADA }

class ColetorSemPermissaoException(dispositivo: String) :
    RuntimeException("O coletor $dispositivo não tem permissão para cadastrar etiquetas")

/**
 * Cadastro de etiquetas feito pelo coletor Android. As regras do cadastro são as mesmas da web
 * (RfidService.cadastrarTag); aqui ficam só as do coletor:
 *  - apenas os coletores listados em rfid.dispositivos.cadastro podem cadastrar;
 *  - o coletor reenvia o que não teve confirmação (cadastro feito offline, rede caindo no meio),
 *    então reenviar o MESMO cadastro responde sucesso em vez de "etiqueta já cadastrada".
 */
@Service
class CadastroColetorService(
    @Value("\${rfid.dispositivos.cadastro:}") coletoresLiberados: String,
    private val rfidService: RfidService,
    private val catalogo: CatalogoService,
    private val tags: TagRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val liberados: Set<String> =
        coletoresLiberados.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    init {
        if (liberados.isEmpty()) {
            log.info("Nenhum coletor liberado para cadastrar etiquetas (rfid.dispositivos.cadastro / RFID_COLETORES_CADASTRO)")
        } else {
            log.info("Coletores liberados para cadastrar etiquetas: {}", liberados.joinToString())
        }
    }

    fun podeCadastrar(dispositivo: String) = dispositivo in liberados

    @Transactional(readOnly = true)
    fun catalogo(dispositivo: String): CatalogoColetor {
        val modelosPorMarca = catalogo.listarModelos().groupBy { it.marca.id }
        val marcasPorTipo = catalogo.listarMarcas().groupBy { it.tipo.id }

        val tipos = catalogo.listarTipos().sortedBy { it.nome.lowercase() }.map { tipo ->
            TipoCatalogo(
                id = tipo.id,
                nome = tipo.nome,
                marcas = marcasPorTipo[tipo.id].orEmpty().sortedBy { it.nome.lowercase() }.map { marca ->
                    MarcaCatalogo(
                        id = marca.id,
                        nome = marca.nome,
                        modelos = modelosPorMarca[marca.id].orEmpty().sortedBy { it.nome.lowercase() }
                            .map { ItemCatalogo(it.id, it.nome) }
                    )
                }
            )
        }

        log.debug("Catálogo enviado ao coletor {} ({} tipo(s))", dispositivo, tipos.size)
        return CatalogoColetor(podeCadastrar = podeCadastrar(dispositivo), tipos = tipos)
    }

    @Transactional
    fun cadastrar(pedido: CadastroRequest, dispositivo: String, ip: String = "?", operador: String? = null): ResultadoCadastroColetor {
        if (!podeCadastrar(dispositivo)) {
            log.warn("Cadastro da tag {} recusado: o coletor {} não está liberado para cadastrar (IP: {})", pedido.tag, dispositivo, ip)
            throw ColetorSemPermissaoException(dispositivo)
        }

        val req = pedido.copy(
            tag = pedido.tag.trim(),
            codigoInterno = pedido.codigoInterno.trim(),
            patrimonio = pedido.patrimonio.trim(),
            numeroSerie = pedido.numeroSerie.trim()
        )

        require(req.tag.isNotBlank()) { "Informe a TAG RFID" }
        require(req.codigoInterno.isNotBlank()) { "Informe o código interno" }

        val existente = tags.findByTagReal(req.tag).orElse(null)
        if (existente != null) {
            val mesmosDados = existente.codigoInterno == req.codigoInterno &&
                existente.patrimonio == req.patrimonio &&
                existente.numeroSerie == req.numeroSerie &&
                existente.tipoEquipamentoId == req.tipoEquipamentoId &&
                existente.marcaId == req.marcaId &&
                existente.modeloId == req.modeloId

            if (mesmosDados) {
                log.info("Cadastro da tag {} reenviado pelo coletor {}: já estava cadastrada com os mesmos dados", req.tag, dispositivo)
                return ResultadoCadastroColetor.JA_CADASTRADA
            }

            log.warn(
                "Cadastro da tag {} pelo coletor {} recusado: já cadastrada com outros dados (patrimônio {})",
                req.tag, dispositivo, existente.patrimonio
            )
            throw IllegalStateException(
                "Esta etiqueta já está cadastrada" +
                    if (existente.patrimonio.isNotBlank()) " (patrimônio ${existente.patrimonio})" else ""
            )
        }

        // Valida o catálogo, grava, tira da lista de pendentes e registra no log quem cadastrou
        rfidService.cadastrarTag(req)
        log.info("Etiqueta {} cadastrada pelo coletor {}, operador {}", req.tag, dispositivo, operador ?: "—")
        return ResultadoCadastroColetor.CADASTRADA
    }
}
