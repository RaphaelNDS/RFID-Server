package org.example.controller

import org.example.model.AreaAuditoria
import org.example.service.AuditoriaService
import org.example.service.EtiquetaVista
import org.example.service.RelatorioEtiquetasService
import org.example.util.FormatoPlanilha
import org.example.util.Planilha
import org.example.util.respostaPlanilha
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Clock
import java.time.LocalDate

/** Exportação do cadastro de etiquetas e relatório das etiquetas não vistas há N dias. */
@Controller
class RelatorioEtiquetasController(
    private val relatorio: RelatorioEtiquetasService,
    private val relogio: Clock,
    private val auditoria: AuditoriaService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Todas as etiquetas da busca da tela (não só a página), com a última vez vista e o último local. */
    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping("/admin/tags-cadastradas/exportar")
    fun exportarCadastro(
        @RequestParam(required = false) busca: String?,
        @RequestParam(required = false) formato: String?,
        @RequestParam(required = false) situacao: String?,
        @RequestParam(required = false) local: Long?,
        @RequestParam(required = false) responsavel: Long?,
        @RequestParam(required = false) baixados: Boolean?,
        auth: Authentication
    ): ResponseEntity<ByteArray> {
        val tipoArquivo = FormatoPlanilha.de(formato)
        val etiquetas = relatorio.etiquetasComUltimaVez(busca, AdminController.filtro(situacao, local, responsavel, baixados))

        log.info("{} exportou o cadastro de {} etiqueta(s) em {}{}", auth.name, etiquetas.size,
            tipoArquivo.extensao.uppercase(), busca?.trim()?.takeIf { it.isNotEmpty() }?.let { " (busca: '$it')" } ?: "")
        auditoria.registrar(AreaAuditoria.EXPORTACAO, "Cadastro de etiquetas exportado", "${etiquetas.size} etiqueta(s) em ${tipoArquivo.extensao.uppercase()}",
            busca?.trim()?.takeIf { it.isNotEmpty() }?.let { "Busca: '$it'" })
        return respostaPlanilha(planilha("Etiquetas", etiquetas), tipoArquivo, "etiquetas-${LocalDate.now(relogio)}")
    }

    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping("/admin/etiquetas/nao-vistas")
    fun naoVistas(
        @RequestParam(defaultValue = "${RelatorioEtiquetasService.DIAS_PADRAO}") dias: Int,
        @RequestParam(defaultValue = "1") pagina: Int,
        model: Model
    ): String {
        val diasValidos = RelatorioEtiquetasService.diasValidos(dias)
        val resultado = relatorio.naoVistas(diasValidos, pagina - 1)

        model.addAttribute("etiquetas", resultado.content)
        model.addAttribute("dias", diasValidos)
        model.adicionarPaginacao(resultado, "/admin/etiquetas/nao-vistas", mapOf("dias" to diasValidos))
        return "etiquetas-nao-vistas"
    }

    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping("/admin/etiquetas/nao-vistas/exportar")
    fun exportarNaoVistas(
        @RequestParam(defaultValue = "${RelatorioEtiquetasService.DIAS_PADRAO}") dias: Int,
        @RequestParam(required = false) formato: String?,
        auth: Authentication
    ): ResponseEntity<ByteArray> {
        val tipoArquivo = FormatoPlanilha.de(formato)
        val diasValidos = RelatorioEtiquetasService.diasValidos(dias)
        val etiquetas = relatorio.naoVistasTodas(diasValidos)

        log.info("{} exportou {} etiqueta(s) não vista(s) há {} dias em {}", auth.name, etiquetas.size, diasValidos,
            tipoArquivo.extensao.uppercase())
        auditoria.registrar(AreaAuditoria.EXPORTACAO, "Etiquetas não vistas exportadas", "${etiquetas.size} etiqueta(s) em ${tipoArquivo.extensao.uppercase()}",
            "Não vistas há mais de $diasValidos dias")
        return respostaPlanilha(planilha("Não vistas há $diasValidos dias", etiquetas), tipoArquivo,
            "etiquetas-nao-vistas-${diasValidos}-dias-${LocalDate.now(relogio)}")
    }

    private fun planilha(nome: String, etiquetas: List<EtiquetaVista>): Planilha {
        return Planilha(
            nome = nome,
            // As colunas até "Nota fiscal" (menos a matrícula) são as mesmas da importação (ImportacaoEtiquetasService): a planilha exportada
            // pode ser corrigida e importada de volta
            colunas = listOf("Etiqueta (EPC)", "Código", "Patrimônio", "Tipo", "Marca", "Modelo", "Nº série",
                "Unidade", "Local", "Responsável", "Situação",
                "Valor de aquisição", "Data de aquisição", "Nota fiscal",
                "Última leitura", "Último inventário", "Último local", "Dias sem ser vista"),
            linhas = etiquetas.map { v ->
                val e = v.etiqueta
                listOf(e.tag, e.codigoInterno, e.patrimonio, e.tipoNome, e.marcaNome, e.modeloNome, e.numeroSerie,
                    e.unidadeNome, e.localNome, e.responsavelNome, e.situacao.rotulo,
                    e.valorAquisicao?.let { org.example.util.FormatoBr.numero(it) }, e.dataAquisicao?.let { org.example.util.FormatoBr.data(it) }, e.notaFiscal,
                    v.ultimaLeitura, v.ultimoInventario, v.ultimoLocal,
                    v.diasSemSerVista)
            }
        )
    }
}
