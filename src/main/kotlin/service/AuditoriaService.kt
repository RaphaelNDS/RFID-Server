package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.AuditoriaEntity
import org.example.repository.AuditoriaRepository
import org.example.security.OperadorNoColetor
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Clock
import java.time.LocalDateTime

/**
 * Auditoria de usuários e configurações (tabela auditoria, V13): criação e edição de usuários, desativação,
 * senhas temporárias, trocas de senha e email, liberação de PIN, catálogo, publicação do app, exportações e
 * backup manual. O log continua igual; a auditoria é o registro que não some depois de 30 dias.
 *
 * Grava na mesma transação da ação, quando há uma: se a ação for desfeita, o registro também é.
 */
@Service
class AuditoriaService(
    private val repo: AuditoriaRepository,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun registrar(area: AreaAuditoria, acao: String, alvo: String? = null, detalhe: String? = null) {
        val (usuario, origem) = autor()
        repo.save(
            AuditoriaEntity(
                momento = LocalDateTime.now(relogio),
                usuario = usuario.take(255), origem = origem.take(120),
                area = area, acao = acao.take(80), alvo = alvo?.take(255), detalhe = detalhe?.take(1000)
            )
        )
        log.debug("Auditoria: {} / {} ({}) por {} ({})", area, acao, alvo ?: "—", usuario, origem)
    }

    /** Tela de auditoria: uma página (mais recentes primeiro). [pagina] começa em 0. */
    fun listar(area: AreaAuditoria?, busca: String?, pagina: Int): Page<AuditoriaEntity> {
        val ordem = Sort.by(Sort.Direction.DESC, "momento").and(Sort.by(Sort.Direction.DESC, "id"))
        return RfidService.paginaValida(pagina) { p ->
            repo.buscar(area, RfidService.padraoBusca(busca), PageRequest.of(p, RfidService.TAMANHO_PAGINA, ordem))
        }
    }

    /** Quem fez e por onde: ("ana@x.com", "Web 10.0.0.5"), ("Olga Operadora", "Coletor coletor-01") ou "sistema". */
    private fun autor(): Pair<String, String> {
        val auth = SecurityContextHolder.getContext().authentication
        val principal = auth?.principal
        val ip = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.remoteAddr
        return when {
            principal is OperadorNoColetor -> (principal.operador?.nome ?: "sem operador") to "Coletor ${principal.dispositivo}"
            auth != null && auth.isAuthenticated && auth.name != "anonymousUser" -> auth.name to listOfNotNull("Web", ip).joinToString(" ")
            ip != null -> "anônimo" to "Web $ip"
            else -> "sistema" to "Sistema"
        }
    }
}
