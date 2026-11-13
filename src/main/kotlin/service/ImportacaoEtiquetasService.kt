package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.SituacaoBem
import org.example.model.TagEntity
import org.example.repository.MarcaRepository
import org.example.repository.ModeloRepository
import org.example.repository.TagRepository
import org.example.repository.TipoEquipamentoRepository
import org.example.request.CadastroRequest
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.example.util.LeitorPlanilha
import org.example.util.Planilha
import org.example.util.normalizarEpc
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.text.Normalizer

enum class AcaoLinha(val rotulo: String) { NOVA("Nova"), ATUALIZA("Atualiza"), SEM_MUDANCA("Sem mudança"), ERRO("Erro") }

/** Uma linha da planilha: o que acontece (ou aconteceu) com ela. [numero] é o da linha no Excel. */
data class LinhaImportacao(val numero: Int, val epc: String, val patrimonio: String, val acao: AcaoLinha, val detalhe: String)

data class ResultadoImportacao(val linhas: List<LinhaImportacao>, val gravado: Boolean, val criados: List<String>) {
    fun quantas(acao: AcaoLinha) = linhas.count { it.acao == acao }
    val novas: Int get() = quantas(AcaoLinha.NOVA)
    val atualizadas: Int get() = quantas(AcaoLinha.ATUALIZA)
    val semMudanca: Int get() = quantas(AcaoLinha.SEM_MUDANCA)
    val erros: Int get() = quantas(AcaoLinha.ERRO)
    val temErro: Boolean get() = linhas.any { it.acao == AcaoLinha.ERRO }
    val temMudanca: Boolean get() = linhas.any { it.acao == AcaoLinha.NOVA || it.acao == AcaoLinha.ATUALIZA }
}

/**
 * Importação de etiquetas por planilha (1.6.0): carga inicial e conferência com a lista do ERP. Em duas etapas: a
 * simulação ([processar] com gravar = false) mostra linha a linha o que vai acontecer sem gravar nada; a confirmação
 * grava. Cada linha vale sozinha: a que tiver erro fica de fora e as outras entram.
 *
 * Colunas pelo cabeçalho (sem diferenciar maiúsculas nem acento; as outras são ignoradas, então a planilha exportada
 * pela tela de Etiquetas pode ser corrigida e importada de volta): Etiqueta (EPC) (obrigatória), Código, Patrimônio,
 * Tipo, Marca, Modelo, Nº série, Unidade, Local, Situação.
 *
 * EPC que já existe: atualiza. Célula vazia mantém o que já está no cadastro (não apaga). Com [criarFaltantes], tipo,
 * marca, modelo, unidade e local que não existirem são cadastrados; sem, a linha dá erro.
 */
@Service
class ImportacaoEtiquetasService(
    private val rfid: RfidService,
    private val tags: TagRepository,
    private val tipos: TipoEquipamentoRepository,
    private val marcas: MarcaRepository,
    private val modelos: ModeloRepository,
    private val catalogo: CatalogoService,
    private val cadastros: PatrimonioCadastrosService,
    private val auditoria: AuditoriaService? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private enum class Coluna(vararg val nomes: String) {
        EPC("ETIQUETA_EPC", "EPC", "ETIQUETA", "TAG", "ETIQUETA_RFID"),
        CODIGO("CODIGO", "CODIGO_INTERNO"),
        PATRIMONIO("PATRIMONIO"),
        TIPO("TIPO", "TIPO_DE_EQUIPAMENTO"),
        MARCA("MARCA"),
        MODELO("MODELO"),
        SERIE("N_SERIE", "NO_SERIE", "N_DE_SERIE", "NUMERO_DE_SERIE", "SERIE"),
        UNIDADE("UNIDADE"),
        LOCAL("LOCAL"),
        SITUACAO("SITUACAO")
    }

    fun processar(nomeArquivo: String, conteudo: ByteArray, criarFaltantes: Boolean, podeAlterar: Boolean, gravar: Boolean): ResultadoImportacao {
        val linhas = LeitorPlanilha.ler(nomeArquivo, conteudo, MAXIMO_LINHAS)
        require(linhas.size >= 2) { "A planilha está vazia: a primeira linha é o cabeçalho e as etiquetas vêm a partir da segunda" }
        val colunas = colunas(linhas.first())
        require(Coluna.EPC in colunas) { "A planilha não tem a coluna \"Etiqueta (EPC)\" no cabeçalho (primeira linha)" }

        val criados = linkedSetOf<String>()
        val vistos = mutableMapOf<String, Int>()
        val patrimonios = mutableMapOf<String, Int>()
        val resultado = linhas.drop(1).mapIndexed { i, celulas ->
            val numero = i + 2
            fun valor(c: Coluna) = colunas[c]?.let { celulas.getOrNull(it) }?.trim().orEmpty()
            val epcBruto = valor(Coluna.EPC)
            val epc = normalizarEpc(epcBruto)
            try {
                when {
                    epc.isEmpty() -> throw IllegalArgumentException("sem a etiqueta (EPC)")
                    Regex("^\\d+(\\.\\d+)?E\\+?\\d+$").matches(epc) ->
                        throw IllegalArgumentException("o Excel transformou o EPC em número ($epcBruto): formate a coluna como Texto e digite de novo")
                }
                vistos[epc]?.let { throw IllegalArgumentException("etiqueta repetida na planilha (também na linha $it)") }
                vistos[epc] = numero
                valor(Coluna.PATRIMONIO).takeIf { it.isNotEmpty() }?.let { p ->
                    patrimonios[p]?.let { throw IllegalArgumentException("patrimônio $p repetido na planilha (também na linha $it)") }
                    patrimonios[p] = numero
                }
                linha(numero, epc, ::valor, criarFaltantes, podeAlterar, gravar, criados)
            } catch (e: IllegalArgumentException) {
                LinhaImportacao(numero, epc.ifEmpty { "—" }, valor(Coluna.PATRIMONIO), AcaoLinha.ERRO, e.message ?: "erro")
            }
        }

        val final = ResultadoImportacao(resultado, gravar, criados.toList())
        if (gravar) {
            log.info("Importação de etiquetas ({}) por {}: {} nova(s), {} atualizada(s), {} sem mudança, {} com erro{}", nomeArquivo,
                org.springframework.security.core.context.SecurityContextHolder.getContext().authentication?.name ?: "?",
                final.quantas(AcaoLinha.NOVA), final.quantas(AcaoLinha.ATUALIZA), final.quantas(AcaoLinha.SEM_MUDANCA), final.quantas(AcaoLinha.ERRO),
                if (criados.isEmpty()) "" else "; cadastrados: ${criados.joinToString()}")
            auditoria?.registrar(AreaAuditoria.CATALOGO, "Etiquetas importadas", nomeArquivo.take(200),
                "${final.quantas(AcaoLinha.NOVA)} nova(s), ${final.quantas(AcaoLinha.ATUALIZA)} atualizada(s), ${final.quantas(AcaoLinha.ERRO)} com erro" +
                    if (criados.isEmpty()) "" else "; cadastrados: ${criados.joinToString()}")
        }
        return final
    }

    private fun linha(numero: Int, epc: String, valor: (Coluna) -> String, criar: Boolean, podeAlterar: Boolean, gravar: Boolean,
                      criados: MutableSet<String>): LinhaImportacao {
        val existente = tags.findByTagReal(epc).orElse(null)
        if (existente != null && !podeAlterar) throw IllegalArgumentException("a etiqueta já está cadastrada e você não tem permissão para alterar etiquetas")

        val pedido = existente?.let(::pedidoDe) ?: CadastroRequest(tag = epc)
        valor(Coluna.CODIGO).takeIf { it.isNotEmpty() }?.let { pedido.codigoInterno = it }
        valor(Coluna.PATRIMONIO).takeIf { it.isNotEmpty() }?.let { pedido.patrimonio = it }
        valor(Coluna.SERIE).takeIf { it.isNotEmpty() }?.let { pedido.numeroSerie = it }
        if (pedido.codigoInterno.isBlank()) throw IllegalArgumentException("sem o código interno (obrigatório numa etiqueta nova)")

        // Catálogo: tipo → marca → modelo, pelo nome. Trocar o tipo sem informar a marca limpa a marca e o modelo
        val nomeTipo = valor(Coluna.TIPO)
        val nomeMarca = valor(Coluna.MARCA)
        val nomeModelo = valor(Coluna.MODELO)
        if (nomeTipo.isNotEmpty()) {
            val id = tipo(nomeTipo, criar, gravar, criados)
            if (id != pedido.tipoEquipamentoId) { pedido.marcaId = null; pedido.modeloId = null }
            pedido.tipoEquipamentoId = id
        }
        if (nomeMarca.isNotEmpty()) {
            val tipoId = pedido.tipoEquipamentoId ?: throw IllegalArgumentException("a marca $nomeMarca precisa do tipo de equipamento")
            val id = marca(nomeMarca, tipoId, criar, gravar, criados)
            if (id != pedido.marcaId) pedido.modeloId = null
            pedido.marcaId = id
        }
        if (nomeModelo.isNotEmpty()) {
            val marcaId = pedido.marcaId ?: throw IllegalArgumentException("o modelo $nomeModelo precisa da marca")
            pedido.modeloId = modelo(nomeModelo, marcaId, criar, gravar, criados)
        }

        // Local (com a unidade)
        val nomeUnidade = valor(Coluna.UNIDADE)
        val nomeLocal = valor(Coluna.LOCAL)
        if (nomeLocal.isNotEmpty()) {
            val unidade = nomeUnidade.takeIf { it.isNotEmpty() }?.let { n ->
                cadastros.unidadePorNome(n, criar && gravar)
                    ?: if (criar) { criados += "unidade $n"; null } else throw IllegalArgumentException("a unidade $n não existe")
            }
            val local = cadastros.localPorNome(nomeLocal, unidade, criar && gravar, atualizar = gravar)
            pedido.localId = when {
                local != null -> local.id
                criar -> { criados += "local $nomeLocal"; -1L }
                else -> throw IllegalArgumentException("o local $nomeLocal não existe")
            }
        } else if (nomeUnidade.isNotEmpty()) throw IllegalArgumentException("a unidade $nomeUnidade foi informada sem o local")

        // Situação: BAIXADO só pela baixa (na tela da etiqueta); FORA só pela saída e pela entrada (Movimentações)
        valor(Coluna.SITUACAO).takeIf { it.isNotEmpty() }?.let { texto ->
            val s = SituacaoBem.de(texto) ?: throw IllegalArgumentException("situação inválida: $texto")
            val estaFora = existente?.situacao == SituacaoBem.FORA
            when {
                s == SituacaoBem.BAIXADO && existente?.baixado == true -> {}   // já baixado: mantém
                s == SituacaoBem.BAIXADO -> throw IllegalArgumentException("para baixar um bem use \"Dar baixa\" na tela da etiqueta")
                existente?.baixado == true -> throw IllegalArgumentException("o bem está baixado: desfaça a baixa na tela da etiqueta antes de mudar a situação")
                s == SituacaoBem.FORA && estaFora -> {}   // já fora do estoque: mantém
                s == SituacaoBem.FORA -> throw IllegalArgumentException("para tirar um equipamento do estoque use \"Nova saída\" em Movimentações")
                estaFora -> throw IllegalArgumentException("o equipamento está fora do estoque: registre a entrada em Movimentações antes de mudar a situação")
                else -> pedido.situacao = s.name
            }
        }

        val patrimonio = pedido.patrimonio
        val acao = when {
            existente == null -> AcaoLinha.NOVA
            pedidoDe(existente) == pedido -> AcaoLinha.SEM_MUDANCA
            else -> AcaoLinha.ATUALIZA
        }
        val detalhe = when (acao) {
            AcaoLinha.NOVA -> "cadastra"
            AcaoLinha.SEM_MUDANCA -> "igual ao cadastro"
            else -> "altera: " + diferencas(pedidoDe(existente!!), pedido).joinToString()
        }
        if (!gravar) {
            // Simulação: patrimônio de outra etiqueta é conferido aqui (na gravação, o próprio cadastro recusa)
            if (patrimonio.isNotBlank() && tags.findAllByPatrimonio(patrimonio).any { it.tagReal != epc }) {
                throw IllegalArgumentException("o patrimônio $patrimonio já está em outra etiqueta")
            }
            return LinhaImportacao(numero, epc, patrimonio, acao, detalhe)
        }
        when (acao) {
            AcaoLinha.NOVA -> rfid.cadastrarTag(pedido)
            AcaoLinha.ATUALIZA -> rfid.editarTag(existente!!.id, pedido)
            else -> {}
        }
        return LinhaImportacao(numero, epc, patrimonio, acao, if (acao == AcaoLinha.NOVA) "cadastrada" else detalhe.replace("altera:", "alterada:"))
    }

    /** O cadastro atual como pedido (para atualizar só o que a planilha traz). */
    private fun pedidoDe(t: TagEntity) = CadastroRequest(
        tag = t.tagReal, codigoInterno = t.codigoInterno, patrimonio = t.patrimonio, numeroSerie = t.numeroSerie,
        tipoEquipamentoId = t.tipoEquipamentoId, marcaId = t.marcaId, modeloId = t.modeloId,
        localId = t.localId, situacao = if (t.baixado) null else t.situacao.name
    )

    private fun diferencas(antes: CadastroRequest, depois: CadastroRequest): List<String> = listOfNotNull(
        "código".takeIf { antes.codigoInterno != depois.codigoInterno },
        "patrimônio".takeIf { antes.patrimonio != depois.patrimonio },
        "nº série".takeIf { antes.numeroSerie != depois.numeroSerie },
        "tipo".takeIf { antes.tipoEquipamentoId != depois.tipoEquipamentoId },
        "marca".takeIf { antes.marcaId != depois.marcaId },
        "modelo".takeIf { antes.modeloId != depois.modeloId },
        "local".takeIf { antes.localId != depois.localId },
        "situação".takeIf { antes.situacao != depois.situacao }
    )

    // Catálogo pelo nome. Na simulação, o que seria criado volta -1 (não existe ainda) e entra em [criados]
    private fun tipo(nome: String, criar: Boolean, gravar: Boolean, criados: MutableSet<String>): Long =
        tipos.findByNomeIgnoreCase(nome).orElse(null)?.id ?: criarOuRecusar("tipo $nome", criar, gravar, criados) {
            catalogo.salvarTipo(TipoEquipamentoRequest(nome = nome)).id
        }

    private fun marca(nome: String, tipoId: Long, criar: Boolean, gravar: Boolean, criados: MutableSet<String>): Long =
        (if (tipoId > 0) marcas.findByNomeIgnoreCaseAndTipoId(nome, tipoId).orElse(null)?.id else null)
            ?: criarOuRecusar("marca $nome", criar, gravar, criados) { catalogo.salvarMarca(MarcaRequest(nome = nome, tipoId = tipoId)).id }

    private fun modelo(nome: String, marcaId: Long, criar: Boolean, gravar: Boolean, criados: MutableSet<String>): Long =
        (if (marcaId > 0) modelos.findByNomeIgnoreCaseAndMarcaId(nome, marcaId).orElse(null)?.id else null)
            ?: criarOuRecusar("modelo $nome", criar, gravar, criados) { catalogo.salvarModelo(ModeloRequest(nome = nome, marcaId = marcaId)).id }

    private fun criarOuRecusar(oque: String, criar: Boolean, gravar: Boolean, criados: MutableSet<String>, salvar: () -> Long): Long {
        if (!criar) throw IllegalArgumentException("$oque não existe no catálogo")
        criados += oque
        return if (gravar) salvar() else -1L
    }

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
        nome = "Etiquetas",
        colunas = listOf("Etiqueta (EPC)", "Código", "Patrimônio", "Tipo", "Marca", "Modelo", "Nº série",
            "Unidade", "Local", "Situação"),
        linhas = listOf(listOf("E2801170000002001234ABCD", "TI-0001", "000123", "Notebook", "Dell", "Latitude 5440", "ABC123",
            "Matriz", "Sala 101", "No estoque"))
    )

    companion object {
        const val MAXIMO_LINHAS = 5_000
        const val MAXIMO_BYTES = 10L * 1024 * 1024
    }
}
