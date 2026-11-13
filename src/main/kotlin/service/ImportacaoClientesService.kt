package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.ClienteEntity
import org.example.repository.ClienteRepository
import org.example.util.LeitorPlanilha
import org.example.util.Planilha
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import java.text.Normalizer

/** Uma linha da planilha de clientes: o que acontece (ou aconteceu) com ela. [numero] é o da linha no Excel. */
data class LinhaImportacaoCliente(val numero: Int, val nome: String, val documento: String, val acao: AcaoLinha, val detalhe: String)

data class ResultadoImportacaoClientes(val linhas: List<LinhaImportacaoCliente>, val gravado: Boolean) {
    fun quantas(acao: AcaoLinha) = linhas.count { it.acao == acao }
    val novas: Int get() = quantas(AcaoLinha.NOVA)
    val atualizadas: Int get() = quantas(AcaoLinha.ATUALIZA)
    val semMudanca: Int get() = quantas(AcaoLinha.SEM_MUDANCA)
    val erros: Int get() = quantas(AcaoLinha.ERRO)
    val temErro: Boolean get() = erros > 0
    val temMudanca: Boolean get() = novas + atualizadas > 0
}

/**
 * Importação de clientes por planilha (1.29.0), como a de etiquetas: o envio simula ([processar] com gravar = false) e
 * mostra linha a linha o que vai acontecer; a confirmação grava. Cada linha vale sozinha: a que tiver erro fica de fora.
 *
 * Colunas pelo cabeçalho (sem diferenciar maiúsculas nem acento; as outras são ignoradas): Nome (obrigatória),
 * CPF/CNPJ, Contato, Telefone, Email, Endereço, Observação.
 *
 * O cliente que já existe é achado pelo CPF/CNPJ; sem documento na linha, pelo nome (igual, sem diferenciar maiúsculas).
 * Achado: atualiza, e a célula vazia mantém o que já está no cadastro (não apaga). Atualizar pede Editar na Locação.
 */
@Service
class ImportacaoClientesService(
    private val locacao: LocacaoService,
    private val clientes: ClienteRepository,
    private val auditoria: AuditoriaService? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private enum class Coluna(vararg val nomes: String) {
        NOME("NOME", "RAZAO_SOCIAL", "NOME_OU_RAZAO_SOCIAL", "CLIENTE"),
        DOCUMENTO("CPF_CNPJ", "CPF", "CNPJ", "DOCUMENTO", "CPF_OU_CNPJ", "CNPJ_CPF"),
        CONTATO("CONTATO", "PESSOA_DE_CONTATO"),
        TELEFONE("TELEFONE", "FONE", "CELULAR", "WHATSAPP"),
        EMAIL("EMAIL", "E_MAIL"),
        ENDERECO("ENDERECO", "ENDERECO_DE_ENTREGA"),
        OBSERVACAO("OBSERVACAO", "OBSERVACOES", "OBS")
    }

    fun processar(nomeArquivo: String, conteudo: ByteArray, podeAlterar: Boolean, gravar: Boolean): ResultadoImportacaoClientes {
        val linhas = LeitorPlanilha.ler(nomeArquivo, conteudo, MAXIMO_LINHAS)
        require(linhas.size >= 2) { "A planilha está vazia: a primeira linha é o cabeçalho e os clientes vêm a partir da segunda" }
        val colunas = colunas(linhas.first())
        require(Coluna.NOME in colunas) { "A planilha não tem a coluna \"Nome\" no cabeçalho (primeira linha)" }

        val existentes = clientes.findAll()
        val porDocumento = existentes.filter { it.documento != null }.associateBy { it.documento!! }
        val porNome = existentes.groupBy { it.nome.trim().lowercase() }
        val documentosVistos = mutableMapOf<String, Int>()
        val nomesVistos = mutableMapOf<String, Int>()

        val resultado = linhas.drop(1).mapIndexedNotNull { i, celulas ->
            val numero = i + 2
            fun valor(c: Coluna) = colunas[c]?.let { celulas.getOrNull(it) }?.trim().orEmpty()
            if (Coluna.entries.all { valor(it).isEmpty() }) return@mapIndexedNotNull null   // linha em branco
            val nome = valor(Coluna.NOME)
            val documento = valor(Coluna.DOCUMENTO).filter(Char::isDigit)
            try {
                if (valor(Coluna.DOCUMENTO).isNotEmpty() && documento.length != 11 && documento.length != 14) {
                    throw IllegalArgumentException("o documento ${valor(Coluna.DOCUMENTO)} não é um CPF (11 dígitos) nem um CNPJ (14 dígitos)" +
                        if (documento.length in 9..13) "; no Excel, formate a coluna como Texto para não perder os zeros do início" else "")
                }
                if (documento.isNotEmpty()) {
                    documentosVistos[documento]?.let { throw IllegalArgumentException("CPF/CNPJ repetido na planilha (também na linha $it)") }
                    documentosVistos[documento] = numero
                } else if (nome.isNotEmpty()) {
                    nomesVistos[nome.lowercase()]?.let { throw IllegalArgumentException("nome repetido na planilha sem CPF/CNPJ (também na linha $it)") }
                    nomesVistos[nome.lowercase()] = numero
                }

                // Com documento: pelo documento ou, se não achar, pelo mesmo nome ainda sem documento (completa o cadastro)
                val existente: ClienteEntity? = if (documento.isNotEmpty()) {
                    porDocumento[documento] ?: porNome[nome.lowercase()].orEmpty().filter { it.documento == null }.singleOrNull()
                } else {
                    val mesmos = porNome[nome.lowercase()].orEmpty()
                    if (mesmos.size > 1) throw IllegalArgumentException("há ${mesmos.size} clientes com este nome: informe o CPF/CNPJ")
                    mesmos.firstOrNull()
                }
                if (existente != null && !podeAlterar) throw IllegalArgumentException("o cliente já existe e você não tem permissão para alterar clientes")
                if (existente == null && nome.isEmpty()) throw IllegalArgumentException("sem o nome (obrigatório num cliente novo)")

                fun manter(c: Coluna, atual: String?) = valor(c).ifEmpty { atual.orEmpty() }.ifEmpty { null }
                val dados = DadosCliente(
                    nome = nome.ifEmpty { existente!!.nome },
                    documento = documento.ifEmpty { existente?.documento.orEmpty() }.ifEmpty { null },
                    contato = manter(Coluna.CONTATO, existente?.contato),
                    telefone = manter(Coluna.TELEFONE, existente?.telefone),
                    email = manter(Coluna.EMAIL, existente?.email)?.lowercase(),
                    endereco = manter(Coluna.ENDERECO, existente?.endereco),
                    observacao = manter(Coluna.OBSERVACAO, existente?.observacao)
                )
                validar(dados)

                val mudancas = existente?.let { diferencas(it, dados) }.orEmpty()
                val acao = when {
                    existente == null -> AcaoLinha.NOVA
                    mudancas.isEmpty() -> AcaoLinha.SEM_MUDANCA
                    else -> AcaoLinha.ATUALIZA
                }
                if (gravar && acao != AcaoLinha.SEM_MUDANCA) locacao.salvarCliente(existente?.id, dados)
                val detalhe = when (acao) {
                    AcaoLinha.NOVA -> if (gravar) "cadastrado" else "cadastra"
                    AcaoLinha.SEM_MUDANCA -> "igual ao cadastro"
                    else -> (if (gravar) "alterado: " else "altera: ") + mudancas.joinToString()
                }
                LinhaImportacaoCliente(numero, dados.nome!!, dados.documento.orEmpty(), acao, detalhe)
            } catch (e: IllegalArgumentException) {
                LinhaImportacaoCliente(numero, nome.ifEmpty { "—" }, documento, AcaoLinha.ERRO, e.message ?: "erro")
            }
        }
        require(resultado.isNotEmpty()) { "A planilha não tem nenhum cliente (só o cabeçalho e linhas em branco)" }

        val final = ResultadoImportacaoClientes(resultado, gravar)
        if (gravar) {
            log.info("Importação de clientes ({}) por {}: {} novo(s), {} atualizado(s), {} sem mudança, {} com erro", nomeArquivo,
                SecurityContextHolder.getContext().authentication?.name ?: "?", final.novas, final.atualizadas, final.semMudanca, final.erros)
            auditoria?.registrar(AreaAuditoria.PATRIMONIO, "Clientes importados", nomeArquivo.take(200),
                "${final.novas} novo(s), ${final.atualizadas} atualizado(s), ${final.erros} com erro")
        }
        return final
    }

    /** As mesmas regras do cadastro na tela (LocacaoService.salvarCliente), conferidas já na simulação. */
    private fun validar(d: DadosCliente) {
        fun limite(v: String?, rotulo: String, max: Int) = require((v?.length ?: 0) <= max) { "$rotulo pode ter no máximo $max caracteres" }
        limite(d.nome, "o nome", 160)
        limite(d.contato, "o contato", 160)
        limite(d.telefone, "o telefone", 40)
        limite(d.email, "o email", 160)
        limite(d.endereco, "o endereço", 300)
        limite(d.observacao, "a observação", 500)
        d.email?.let { require('@' in it) { "email inválido: $it" } }
    }

    private fun diferencas(c: ClienteEntity, d: DadosCliente): List<String> = listOfNotNull(
        "nome".takeIf { c.nome != d.nome },
        "CPF/CNPJ".takeIf { c.documento != d.documento },
        "contato".takeIf { c.contato != d.contato },
        "telefone".takeIf { c.telefone != d.telefone },
        "email".takeIf { c.email != d.email },
        "endereço".takeIf { c.endereco != d.endereco },
        "observação".takeIf { c.observacao != d.observacao }
    )

    private fun colunas(cabecalho: List<String>): Map<Coluna, Int> {
        fun limpar(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')
        val mapa = mutableMapOf<Coluna, Int>()
        cabecalho.forEachIndexed { i, h ->
            val nome = limpar(h)
            Coluna.entries.firstOrNull { nome in it.nomes }?.let { mapa.putIfAbsent(it, i) }
        }
        return mapa
    }

    /** Modelo para baixar: o cabeçalho e uma linha de exemplo. */
    fun modelo(): Planilha = Planilha(
        nome = "Clientes",
        colunas = listOf("Nome", "CPF/CNPJ", "Contato", "Telefone", "Email", "Endereço", "Observação"),
        linhas = listOf(listOf("Construtora Exemplo Ltda", "12.345.678/0001-90", "João Silva", "(11) 99999-0000",
            "compras@exemplo.com.br", "Rua das Obras, 100 - Centro", ""))
    )

    companion object {
        const val MAXIMO_LINHAS = 5_000
        const val MAXIMO_BYTES = 10L * 1024 * 1024
    }
}
