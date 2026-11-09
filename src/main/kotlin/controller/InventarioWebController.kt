package org.example.controller

import org.example.model.StatusInventario
import org.example.model.AreaAuditoria
import org.example.service.AuditoriaService
import org.example.service.EsperadoInventarioService
import org.example.service.InventarioService
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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/** Telas de acompanhamento dos inventários feitos pelos coletores. Mesma permissão da tela de Leituras. */
@Controller
@RequestMapping("/admin/inventarios")
class InventarioWebController(
    private val service: InventarioService,
    private val esperados: EsperadoInventarioService,
    private val auditoria: AuditoriaService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @GetMapping
    fun listar(
        @RequestParam(required = false) local: Long?,
        @RequestParam(defaultValue = "1") pagina: Int,
        model: Model
    ): String {
        // Na tela as páginas começam em 1; no serviço, em 0
        val resultado = service.listar(local, pagina - 1)
        model.addAttribute("inventarios", resultado.content)
        model.adicionarPaginacao(resultado, "/admin/inventarios", mapOf("local" to local))
        model.addAttribute("locais", service.locaisCadastrados())
        model.addAttribute("localSelecionado", local)
        return "inventarios"
    }

    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @GetMapping("/{id}")
    fun detalhe(
        @PathVariable id: String,
        @RequestParam(required = false) local: String?,
        @RequestParam(required = false) pagina: String?,
        model: Model
    ): String {
        val detalhe = service.detalhar(id)
        model.addAttribute("inventario", detalhe.resumo)
        model.addAttribute("itens", detalhe.itens)
        model.addAttribute("comparacao", esperados.faltandoNoInventario(id))
        model.addAttribute("justificativas", service.justificativasDo(id))
        // "Voltar" leva ao mesmo filtro de local e página da lista (antes voltava para a lista limpa). Só esses dois
        // parâmetros, validados como números; texto inválido é ignorado e o destino é sempre a lista de inventários
        val localValido = local?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        val paginaValida = RetornoLista.paginaValida(pagina)
        model.addAttribute("linkVoltar", org.springframework.web.util.UriComponentsBuilder.fromPath("/admin/inventarios")
            .apply {
                localValido?.let { queryParam("local", it) }
                paginaValida?.let { queryParam("pagina", it) }
            }.encode().toUriString())
        return "inventario-detalhe"
    }

    /**
     * Etiquetas do inventário em Excel (padrão) ou CSV: as lidas, na ordem da leitura, e depois as que faltaram
     * em relação ao inventário anterior do mesmo local, com a situação "Faltando" (sem hora de leitura).
     */
    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @GetMapping("/{id}/exportar")
    fun exportar(@PathVariable id: String, @RequestParam(required = false) formato: String?, auth: Authentication): ResponseEntity<ByteArray> {
        val tipoArquivo = FormatoPlanilha.de(formato)
        val detalhe = service.detalhar(id)
        val inventario = detalhe.resumo
        val comparacao = esperados.faltandoNoInventario(id)

        val lidas = detalhe.itens.map {
            listOf(
                it.lidoEm, it.tag, if (it.cadastrada) "Cadastrada" else "Não cadastrada",
                it.patrimonio, it.codigoInterno, it.tipo, it.marca, it.modelo, it.operador
            )
        }
        // Em andamento, a lista é provisória: a situação diz isso na própria planilha
        val situacaoFaltando = if (inventario.status == StatusInventario.ABERTO) "Faltando até agora" else "Faltando"
        // Justificativa marcada no coletor (1.5.0): na situação ("Faltando: Emprestado (com o João)") e o operador dela
        val justificativas = service.justificativasDo(id)
        val faltando = comparacao.faltando.map {
            val j = justificativas[it.tag]
            val situacao = if (j == null) situacaoFaltando else "$situacaoFaltando: ${j.motivo.rotulo}${j.observacao?.let { o -> " ($o)" } ?: ""}"
            listOf(null, it.tag, situacao, it.patrimonio, it.codigoInterno, it.tipo, it.marca, it.modelo, j?.operador)
        }
        // Achadas depois em outro local: não contam como faltando, mas aparecem com o local em que foram achadas
        val emOutroLocal = comparacao.emOutroLocal.map {
            listOf(null, it.item.tag, "Encontrada em outro local: ${it.local}", it.item.patrimonio, it.item.codigoInterno,
                it.item.tipo, it.item.marca, it.item.modelo, null)
        }

        val planilha = Planilha(
            nome = "Inventário ${inventario.local}",
            colunas = listOf("Lida em", "Etiqueta (EPC)", "Situação", "Patrimônio", "Código", "Tipo", "Marca", "Modelo", "Operador"),
            linhas = lidas + faltando + emOutroLocal
        )

        log.info("{} exportou o inventário {} de '{}' em {}: {} etiqueta(s) lida(s){}",
            auth.name, id, inventario.local, tipoArquivo.extensao.uppercase(), detalhe.itens.size,
            if (comparacao.referencia.temReferencia) " e ${comparacao.faltando.size} faltando em relação ao inventário ${comparacao.referencia.inventarioReferencia}"
            else " (primeiro inventário do local, sem comparação)")
        auditoria.registrar(AreaAuditoria.EXPORTACAO, "Inventário exportado", "${inventario.local} ($id)",
            "${detalhe.itens.size} etiqueta(s) lida(s), ${comparacao.faltando.size} faltando, em ${tipoArquivo.extensao.uppercase()}")

        // Ex.: inventario-almoxarifado-2026-10-14 (dia em que o inventário começou)
        return respostaPlanilha(planilha, tipoArquivo, "inventario-${inventario.local}-${inventario.iniciadoEm.toLocalDate()}")
    }
}
