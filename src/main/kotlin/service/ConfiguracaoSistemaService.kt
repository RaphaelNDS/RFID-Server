package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.ConfiguracaoSistemaEntity
import org.example.repository.ConfiguracaoSistemaRepository
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Telas do menu que podem sair dele (1.30.0). Desligar só tira do menu: a tela, as rotas e os dados continuam. */
enum class ModuloOpcional(val rotulo: String, val descricao: String) {
    INVENTARIO_CICLICO("Inventário cíclico", "Contar cada local pelo menos uma vez a cada N semanas"),
    TRANSFERENCIAS("Transferências", "Mudar bens de local com guia"),
    AGENDA("Agenda de entregas", "Entregas e retiradas do dia, com o roteiro do motorista"),
    PAINEL_TV("Painel para TV", "O painel do dia em tela cheia para o depósito"),
    DISPONIBILIDADE("Disponibilidade", "O que está livre por tipo e modelo"),
    RELATORIOS_LOCACAO("Relatórios da locação", "Ocupação, clientes e equipamentos mais alugados"),
    COMPRA_E_VENDA("Compra e venda", "Sugestão do que comprar e do que pôr à venda"),
    ACESSORIOS("Acessórios", "Acessórios e kits que acompanham os equipamentos"),
    MANUTENCOES("Manutenções", "Ordens de manutenção, revisões e garantias")
}

/**
 * Configurações do sistema em chave e valor (1.30.0, V41): os módulos tirados do menu e o quadro Primeiros passos.
 * Lidas a cada página (o menu): ficam em memória e só voltam ao banco depois de uma mudança. Bean "configuracaoSistema",
 * usado pelo menu: `@configuracaoSistema.noMenu('AGENDA')`.
 */
@Service("configuracaoSistema")
class ConfiguracaoSistemaService(
    private val repo: ConfiguracaoSistemaRepository,
    private val relogio: Clock,
    private val auditoria: AuditoriaService? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var cache: Map<String, String>? = null
    private val trava = Any()

    private fun valores(): Map<String, String> = cache ?: synchronized(trava) {
        cache ?: repo.findAll().associate { it.chave to it.valor }.also { cache = it }
    }

    fun valor(chave: String): String? = valores()[chave]

    /** O módulo aparece no menu? Nome desconhecido aparece (o menu nunca some por engano). */
    fun noMenu(modulo: String): Boolean = valor(CHAVE_MODULO + modulo) != DESLIGADO

    fun desligados(): Set<ModuloOpcional> = ModuloOpcional.entries.filter { !noMenu(it.name) }.toSet()

    @Transactional
    fun definirMenu(ligados: Set<ModuloOpcional>) {
        val antes = desligados()
        val depois = ModuloOpcional.entries.toSet() - ligados
        if (antes == depois) return
        depois.forEach { gravar(CHAVE_MODULO + it.name, DESLIGADO) }
        (antes - depois).forEach { apagar(CHAVE_MODULO + it.name) }
        val resumo = if (depois.isEmpty()) "todos no menu" else "fora do menu: " + depois.sortedBy { it.ordinal }.joinToString { it.rotulo }
        log.info("Módulos do menu alterados por {}: {}", autor(), resumo)
        auditoria?.registrar(AreaAuditoria.USUARIOS, "Módulos do menu", null, resumo)
    }

    fun primeirosPassosOcultos(): Boolean = valor(CHAVE_PRIMEIROS_PASSOS) == "sim"

    @Transactional
    fun ocultarPrimeirosPassos(ocultar: Boolean) {
        if (ocultar == primeirosPassosOcultos()) return
        if (ocultar) gravar(CHAVE_PRIMEIROS_PASSOS, "sim") else apagar(CHAVE_PRIMEIROS_PASSOS)
        log.info("Quadro Primeiros passos {} por {}", if (ocultar) "ocultado" else "mostrado de novo", autor())
        auditoria?.registrar(AreaAuditoria.USUARIOS, "Primeiros passos", null, if (ocultar) "Ocultado do painel" else "Mostrado no painel")
    }

    /** Grava a chave, ou apaga quando [novo] é nulo (sem linha = padrão). Dentro da transação de quem chama. */
    fun gravarOuApagar(chave: String, novo: String?) {
        if (novo == null) apagar(chave) else if (valor(chave) != novo) gravar(chave, novo)
    }

    private fun gravar(chave: String, valor: String) {
        val c = repo.findById(chave).orElseGet { ConfiguracaoSistemaEntity(chave, valor) }
        c.valor = valor
        c.alteradoPor = autor()
        c.alteradoEm = LocalDateTime.now(relogio)
        repo.save(c)
        invalidar()
    }

    private fun apagar(chave: String) {
        if (repo.existsById(chave)) repo.deleteById(chave)
        invalidar()
    }

    /** Esquece o que está em memória já (a própria transação lê o novo) e de novo depois do commit (outra leitura pode ter
     * guardado o valor antigo enquanto a transação não terminava). */
    private fun invalidar() {
        cache = null
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) { cache = null }
            })
        }
    }

    private fun autor() = SecurityContextHolder.getContext().authentication?.name ?: "sistema"

    companion object {
        const val CHAVE_MODULO = "modulo."
        const val CHAVE_PRIMEIROS_PASSOS = "primeiros-passos.oculto"
        const val DESLIGADO = "desligado"
    }
}
