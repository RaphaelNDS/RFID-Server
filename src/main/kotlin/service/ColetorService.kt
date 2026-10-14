package org.example.service

import org.example.model.ColetorEntity
import org.example.model.VersaoAppEntity
import org.example.repository.ColetorRepository
import org.example.repository.VersaoAppRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/** Como o coletor está, pelo último contato. */
enum class SituacaoContato(val rotulo: String) {
    EM_USO("Em uso agora"),
    PARADO("Sem uso no momento"),
    SEM_CONTATO("Sem contato há muito tempo"),
    NUNCA("Nunca conectou"),
    /** Aparece no banco, mas a chave não está mais em rfid.dispositivos.chaves. */
    CHAVE_REMOVIDA("Chave removida do servidor")
}

/** Uma linha da tela Coletores. */
data class SituacaoColetor(
    val nome: String,
    val situacao: SituacaoContato,
    val ultimoContato: LocalDateTime? = null,
    /** "há 5 min", "há 3 h", "há 2 dias". */
    val haQuantoTempo: String? = null,
    val primeiroContato: LocalDateTime? = null,
    val ultimoIp: String? = null,
    val versaoApp: String? = null,
    val codigoVersao: Int? = null,
    val ultimoOperador: String? = null,
    /** Versão mais antiga que a mais nova vista nos coletores (ou app sem versão, anterior à 1.4.0). */
    val desatualizado: Boolean = false
)

/**
 * Registra os contatos dos coletores em /api/android (ColetorContatoInterceptor) e monta a tela Coletores.
 *
 * O interceptor roda em toda requisição do coletor (cada lote, cada envio), então a gravação no banco é
 * limitada: no máximo uma por [INTERVALO_GRAVACAO] por coletor, ou na hora quando muda o IP, a versão do app
 * ou o operador. Os logs saem só quando algo muda (primeiro contato, versão nova, IP novo), nunca por requisição.
 */
@Service
class ColetorService(
    private val repo: ColetorRepository,
    private val relogio: Clock,
    @Value("\${rfid.dispositivos.chaves:}") chavesConfiguradas: String,
    @Value("\${rfid.coletores.dias-sem-contato:7}") diasSemContato: Long,
    /** Versões publicadas pelo servidor: a publicada é a referência de "desatualizado". */
    private val versoesApp: VersaoAppRepository? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Nomes dos coletores com chave configurada, na ordem da configuração (o mesmo formato do ChaveDispositivoFilter). */
    val configurados: List<String> = chavesConfiguradas.split(',')
        .map { it.substringBefore(':').trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    private val limiteSemContato: Duration = Duration.ofDays(diasSemContato.coerceAtLeast(1))

    private data class Contato(val ip: String?, val versaoApp: String?, val codigoVersao: Int?, val operador: String?)

    // Último contato GRAVADO de cada coletor (em memória: evita um UPDATE por requisição)
    private val gravados = ConcurrentHashMap<String, Pair<Contato, LocalDateTime>>()

    /**
     * Registra um contato aceito do coletor [nome]. Nunca lança erro: um problema aqui não pode derrubar a
     * requisição do coletor. [operador] null (login, ping) não apaga o último operador conhecido.
     */
    fun registrarContato(nome: String, ip: String?, versaoApp: String?, codigoVersao: Int?, operador: String?) {
        try {
            synchronized(this) { registrar(nome, ip?.take(64), versaoApp, codigoVersao, operador) }
        } catch (e: Exception) {
            log.warn("Não foi possível registrar o contato do coletor {}: {}", nome, e.message)
        }
    }

    private fun registrar(nome: String, ip: String?, versaoApp: String?, codigoVersao: Int?, operador: String?) {
        val agora = LocalDateTime.now(relogio)

        val anterior = gravados[nome] ?: repo.findById(nome).orElse(null)?.let {
            Contato(it.ultimoIp, it.versaoApp, it.codigoVersao, it.ultimoOperador) to it.ultimoContato
        }

        // A versão é sempre a informada agora (app antigo, sem os headers, fica sem versão).
        // Login e ping não têm operador: mantém o último operador conhecido.
        val novo = Contato(
            ip = ip,
            versaoApp = versaoApp,
            codigoVersao = codigoVersao,
            operador = operador ?: anterior?.first?.operador
        )

        if (anterior != null && anterior.first == novo && Duration.between(anterior.second, agora) < INTERVALO_GRAVACAO) return

        val entidade = repo.findById(nome).orElse(null)
        if (entidade == null) {
            repo.save(ColetorEntity(nome, agora, agora, novo.ip, novo.versaoApp, novo.codigoVersao, novo.operador))
            log.info("Coletor {} conectou pela primeira vez (app {}, IP {})", nome, descreverVersao(novo.versaoApp, novo.codigoVersao), novo.ip ?: "desconhecido")
        } else {
            registrarMudancas(nome, entidade, novo)
            entidade.ultimoContato = agora
            entidade.ultimoIp = novo.ip
            entidade.versaoApp = novo.versaoApp
            entidade.codigoVersao = novo.codigoVersao
            entidade.ultimoOperador = novo.operador
            repo.save(entidade)
        }

        gravados[nome] = novo to agora
    }

    private fun registrarMudancas(nome: String, antes: ColetorEntity, novo: Contato) {
        val versaoAntes = descreverVersao(antes.versaoApp, antes.codigoVersao)
        val versaoAgora = descreverVersao(novo.versaoApp, novo.codigoVersao)

        if (antes.codigoVersao != novo.codigoVersao || antes.versaoApp != novo.versaoApp) {
            val codigoAntes = antes.codigoVersao
            val codigoAgora = novo.codigoVersao
            when {
                codigoAgora == null ->
                    log.warn("Coletor {} voltou a usar um app sem versão (anterior à 1.4.0); antes estava com {}", nome, versaoAntes)
                codigoAntes != null && codigoAgora < codigoAntes ->
                    log.warn("Coletor {} voltou para uma versão anterior do app: de {} para {}", nome, versaoAntes, versaoAgora)
                else ->
                    log.info("Coletor {} atualizou o app de {} para {}", nome, versaoAntes, versaoAgora)
            }
        }

        if (antes.ultimoIp != null && novo.ip != null && antes.ultimoIp != novo.ip) {
            log.info("Coletor {} mudou de IP: {} → {}", nome, antes.ultimoIp, novo.ip)
        }

        val parado = Duration.between(antes.ultimoContato, LocalDateTime.now(relogio))
        if (parado >= limiteSemContato) {
            log.info("Coletor {} voltou a falar com o servidor depois de {} sem contato", nome, duracaoLegivel(parado))
        }
    }

    /** Versão do app publicada pelo servidor (tela "App dos coletores"), se houver. */
    fun versaoPublicada(): VersaoAppEntity? = versoesApp?.findFirstByArquivoRemovidoFalseOrderByCodigoDesc()

    /** Para os testes de integração: o banco volta ao estado inicial a cada teste, a memória não. */
    internal fun esquecerContatosGravados() = gravados.clear()

    /** Todos os coletores: os configurados (inclusive os que nunca conectaram) e os que só existem no banco. */
    fun listar(): List<SituacaoColetor> {
        val agora = LocalDateTime.now(relogio)
        val noBanco = repo.findAll().associateBy { it.nome }
        // Referência: a versão publicada no servidor e a mais nova vista nos coletores, o que for maior
        val maisNovo = (noBanco.values.mapNotNull { it.codigoVersao } + listOfNotNull(versaoPublicada()?.codigo)).maxOrNull()

        fun desatualizado(c: ColetorEntity) =
            maisNovo != null && (c.codigoVersao == null || c.codigoVersao!! < maisNovo)

        val configurados = configurados.map { nome ->
            val c = noBanco[nome] ?: return@map SituacaoColetor(nome, SituacaoContato.NUNCA)
            linha(c, situacaoPeloTempo(c, agora), agora, desatualizado(c))
        }
        val removidos = noBanco.values
            .filter { it.nome !in this.configurados }
            .sortedBy { it.nome }
            .map { linha(it, SituacaoContato.CHAVE_REMOVIDA, agora, desatualizado = false) }

        return configurados + removidos
    }

    private fun situacaoPeloTempo(c: ColetorEntity, agora: LocalDateTime): SituacaoContato {
        val parado = Duration.between(c.ultimoContato, agora)
        return when {
            parado < EM_USO -> SituacaoContato.EM_USO
            parado < limiteSemContato -> SituacaoContato.PARADO
            else -> SituacaoContato.SEM_CONTATO
        }
    }

    private fun linha(c: ColetorEntity, situacao: SituacaoContato, agora: LocalDateTime, desatualizado: Boolean) = SituacaoColetor(
        nome = c.nome,
        situacao = situacao,
        ultimoContato = c.ultimoContato,
        haQuantoTempo = haQuantoTempo(c.ultimoContato, agora),
        primeiroContato = c.primeiroContato,
        ultimoIp = c.ultimoIp,
        versaoApp = c.versaoApp,
        codigoVersao = c.codigoVersao,
        ultimoOperador = c.ultimoOperador,
        desatualizado = desatualizado
    )

    companion object {
        /** Gravação no banco: no máximo uma por minuto por coletor, se nada mudou. */
        val INTERVALO_GRAVACAO: Duration = Duration.ofMinutes(1)

        /** Contato há menos que isto = coletor em uso. */
        val EM_USO: Duration = Duration.ofMinutes(10)

        fun descreverVersao(versaoApp: String?, codigoVersao: Int?): String = when {
            versaoApp == null && codigoVersao == null -> "sem versão (anterior à 1.4.0)"
            codigoVersao == null -> versaoApp!!
            else -> "${versaoApp ?: "?"} (código $codigoVersao)"
        }

        fun haQuantoTempo(momento: LocalDateTime, agora: LocalDateTime): String {
            val passou = Duration.between(momento, agora)
            return if (passou < Duration.ofMinutes(1)) "agora há pouco" else "há ${duracaoLegivel(passou)}"
        }

        fun duracaoLegivel(d: Duration): String = when {
            d < Duration.ofHours(1) -> "${d.toMinutes().coerceAtLeast(1)} min"
            d < Duration.ofDays(1) -> "${d.toHours()} h"
            else -> d.toDays().let { if (it == 1L) "1 dia" else "$it dias" }
        }
    }
}
