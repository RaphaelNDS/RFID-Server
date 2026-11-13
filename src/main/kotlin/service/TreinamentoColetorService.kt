package org.example.service

import org.example.model.AreaAuditoria
import org.example.repository.ColetorRepository
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Modo treinamento por coletor (1.31.0, V42). O coletor em treinamento continua lendo e fazendo inventário, mas nada
 * muda de verdade: as leituras não são gravadas nem viram pendentes, o inventário fica marcado como treino (não muda o
 * local dos bens nem conta no inventário cíclico) e o cadastro e a transferência pelo coletor são recusados com aviso.
 */
@Service
class TreinamentoColetorService(
    private val coletores: ColetorRepository,
    private val auditoria: AuditoriaService? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Coletor que ainda não falou com o servidor não está em treinamento. */
    @Transactional(readOnly = true)
    fun emTreinamento(coletor: String?): Boolean =
        coletor != null && coletores.findById(coletor).map { it.treinamento }.orElse(false)

    /** Tela Coletores: os que estão em treinamento. */
    @Transactional(readOnly = true)
    fun nomesEmTreinamento(): Set<String> = coletores.findAll().filter { it.treinamento }.map { it.nome }.toSet()

    @Transactional
    fun definir(coletor: String, ligado: Boolean) {
        val c = coletores.findById(coletor).orElseThrow { NoSuchElementException("Coletor $coletor não encontrado") }
        if (c.treinamento == ligado) return
        c.treinamento = ligado
        coletores.save(c)
        val quem = SecurityContextHolder.getContext().authentication?.name ?: "?"
        log.info("Coletor {} {} modo treinamento por {}", coletor, if (ligado) "entrou no" else "saiu do", quem)
        auditoria?.registrar(AreaAuditoria.COLETORES, if (ligado) "Modo treinamento ligado" else "Modo treinamento desligado", coletor)
    }

    companion object {
        const val AVISO_CADASTRO = "Coletor em modo treinamento: o cadastro não foi gravado. Peça ao administrador para tirar o coletor do treinamento."
        const val AVISO_TRANSFERENCIA = "Coletor em modo treinamento: a transferência não foi gravada. Peça ao administrador para tirar o coletor do treinamento."
    }
}

/** 1.31.0: ação do coletor que mudaria dados de verdade, recusada porque ele está em treinamento (HTTP 409). */
class ColetorEmTreinamentoException(mensagem: String) : RuntimeException(mensagem)
