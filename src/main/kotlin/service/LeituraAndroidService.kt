package org.example.service

import org.example.model.LeituraAndroidEntity
import org.example.model.TagEntity
import org.example.repository.*
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Nomes do catálogo por id, já carregados (o lote carrega uma vez para todas as leituras). */
data class NomesCatalogo(
    val tipos: Map<Long, String>,
    val marcas: Map<Long, String>,
    val modelos: Map<Long, String>
)

/** O que aconteceu com uma leitura recebida do coletor. */
enum class ResultadoLeitura {
    REGISTRADA,
    /** Mesma tag com a mesma hora de leitura já gravada: reenvio do coletor (ex.: a resposta se perdeu na rede). */
    REPETIDA,
    /** Tag não cadastrada ou com cadastro incompleto: não vira leitura. */
    IGNORADA
}

@Service
class LeituraAndroidService(
    private val leituraRepo: LeituraAndroidRepository,
    private val tagRepo: TagRepository,
    private val tipoRepo: TipoEquipamentoRepository,
    private val marcaRepo: MarcaRepository,
    private val modeloRepo: ModeloRepository,
    private val eventos: LeituraEventosService,
    // Padrão para os testes que montam o service à mão; no Spring vem o Clock da aplicação
    private val relogio: Clock = Clock.systemDefaultZone()
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun salvarLeitura(tagBruta: String, lidoEm: OffsetDateTime? = null, operador: String? = null): ResultadoLeitura {
        val tagReal = org.example.util.normalizarEpc(tagBruta)

        require(tagReal.isNotBlank()) { "Informe a TAG RFID" }

        // Leituras descartadas ficam em DEBUG: o leitor pode repetir a mesma tag várias vezes por segundo
        val tag = tagRepo.findByTagReal(tagReal).orElse(null) ?: run {
            log.debug("Leitura ignorada: tag {} não está cadastrada", tagReal)
            return ResultadoLeitura.IGNORADA
        }
        // Cadastro incompleto (sem tipo, marca, modelo, patrimônio ou série) também grava, com o que houver: antes a
        // leitura era descartada calada, o coletor recebia "cadastrada" e a etiqueta aparecia como "nunca vista"
        val nomes = NomesCatalogo(
            tipos = tag.tipoEquipamentoId?.let { id -> tipoRepo.findById(id).orElse(null)?.let { mapOf(id to it.nome) } }.orEmpty(),
            marcas = tag.marcaId?.let { id -> marcaRepo.findById(id).orElse(null)?.let { mapOf(id to it.nome) } }.orEmpty(),
            modelos = tag.modeloId?.let { id -> modeloRepo.findById(id).orElse(null)?.let { mapOf(id to it.nome) } }.orEmpty()
        )
        return gravar(tag, nomes, lidoEm, operador,
            jaGravada = { momento -> leituraRepo.existsByTagAndLidoEmColetor(tagReal, momento) })
    }

    /**
     * Lote: mesma regra do [salvarLeitura], com a etiqueta e o catálogo já carregados e as leituras já gravadas
     * em [gravadas] (tag, hora informada pelo coletor), que é atualizado a cada gravação. Uma consulta (o INSERT) por leitura, em vez de 6.
     */
    fun salvarLeituraDoLote(
        tag: TagEntity,
        nomes: NomesCatalogo,
        lidoEm: OffsetDateTime?,
        operador: String?,
        gravadas: MutableSet<Pair<String, LocalDateTime>>
    ): ResultadoLeitura {
        return gravar(tag, nomes, lidoEm, operador,
            jaGravada = { momento -> (tag.tagReal to momento) in gravadas },
            aoGravar = { momento -> gravadas += tag.tagReal to momento })
    }

    /**
     * (tag, hora informada pelo coletor) das leituras dessas tags já gravadas desde [inicio]: o lote confere reenvios
     * sem uma consulta por item.
     */
    fun gravadasDesde(tags: Collection<String>, inicio: LocalDateTime): MutableSet<Pair<String, LocalDateTime>> {
        if (tags.isEmpty()) return mutableSetOf()
        return leituraRepo.gravadasDesde(tags, inicio).mapTo(HashSet()) { (it[0] as String) to (it[1] as LocalDateTime) }
    }

    private fun gravar(
        tag: TagEntity,
        nomes: NomesCatalogo,
        lidoEm: OffsetDateTime?,
        operador: String?,
        jaGravada: (LocalDateTime) -> Boolean,
        aoGravar: (LocalDateTime) -> Unit = {}
    ): ResultadoLeitura {
        val tagReal = tag.tagReal
        val dataHora = dataHoraDaLeitura(tagReal, lidoEm)
        // A hora que o coletor informou, mesmo no futuro (relógio adiantado): é ela que identifica o reenvio. A
        // [dataHora] de uma leitura "do futuro" vira a hora do recebimento, que muda a cada reenvio, e antes a mesma
        // leitura era gravada de novo a cada reenvio
        val momentoColetor = lidoEm?.let(::paraHoraDoServidor)

        // O coletor reenvia o que não teve resposta: a mesma tag com a mesma hora de leitura (até o milissegundo)
        // é o mesmo registro chegando de novo. Sem hora informada não dá para saber, então grava.
        if (momentoColetor != null && jaGravada(momentoColetor)) {
            log.debug("Leitura repetida ignorada: tag {} lida em {} já estava registrada", tagReal, momentoColetor)
            return ResultadoLeitura.REPETIDA
        }

        val leitura = LeituraAndroidEntity(
            tag = tagReal,
            codigoInterno = tag.codigoInterno,
            tipo = tag.tipoEquipamentoId?.let { nomes.tipos[it] },
            marca = tag.marcaId?.let { nomes.marcas[it] },
            modelo = tag.modeloId?.let { nomes.modelos[it] },
            patrimonio = tag.patrimonio,
            numeroSerie = tag.numeroSerie,
            dataHora = dataHora,
            lidoEmColetor = momentoColetor,
            operador = operador
        )

        // A conferência acima não pega duas requisições ao mesmo tempo (o coletor reenvia o lote quando a resposta
        // demora): o índice único (tag, lido_em_coletor) da V16 recusa a segunda gravação, e ela conta como repetida.
        // Só a violação de índice único: outro erro de integridade (coluna obrigatória, texto grande demais...) não é
        // reenvio e sobe, para o lote marcar ERRO em vez de sumir com a leitura como "repetida"
        try {
            leituraRepo.saveAndFlush(leitura)
        } catch (e: DataIntegrityViolationException) {
            if (!org.example.util.violacaoDeUnicidade(e)) {
                log.error("Leitura da tag {} lida em {} recusada pelo banco por erro de integridade (não é reenvio)", tagReal, dataHora, e)
                throw e
            }
            log.debug("Leitura repetida recusada pelo banco: tag {} lida em {} (envio simultâneo)", tagReal, dataHora)
            return ResultadoLeitura.REPETIDA
        }

        log.debug("Leitura registrada: tag {} (patrimônio {}, operador {})", tagReal, tag.patrimonio, operador ?: "—")

        momentoColetor?.let(aoGravar)
        eventos.publicar(leitura)
        return ResultadoLeitura.REGISTRADA
    }

    /**
     * Usa a hora informada pelo coletor (leituras offline chegam depois de feitas), convertida
     * para o fuso do servidor. Sem ela, ou se estiver no futuro (relógio do coletor adiantado),
     * vale a hora do recebimento.
     */
    private fun paraHoraDoServidor(lidoEm: OffsetDateTime): LocalDateTime =
        lidoEm.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()

    private fun dataHoraDaLeitura(tagReal: String, lidoEm: OffsetDateTime?): LocalDateTime {
        val agora = LocalDateTime.now(relogio)
        if (lidoEm == null) return agora

        val lida = paraHoraDoServidor(lidoEm)

        if (lida.isAfter(agora.plus(TOLERANCIA_RELOGIO))) {
            log.warn(
                "Leitura da tag {} com data no futuro ({}): relógio do coletor adiantado? Usando a hora do recebimento",
                tagReal, lidoEm
            )
            return agora
        }

        val atraso = Duration.between(lida, agora)
        if (atraso > ATRASO_LEITURA_OFFLINE) {
            log.info("Leitura offline recebida: tag {} lida em {} ({} min atrás)", tagReal, lida, atraso.toMinutes())
        }

        return lida
    }

    private companion object {
        val TOLERANCIA_RELOGIO: Duration = Duration.ofMinutes(5)
        val ATRASO_LEITURA_OFFLINE: Duration = Duration.ofMinutes(1)
    }
}
