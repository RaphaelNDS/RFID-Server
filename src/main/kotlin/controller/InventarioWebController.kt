package org.example.controller

import org.example.model.StatusInventario
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
    private val esperados: EsperadoInventarioService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @GetMapping
    fun listar(@RequestParam(required = false) local: Long?, model: Model): String {
        model.addAttribute("inventarios", service.listar(local))
        model.addAttribute("locais", service.locaisCadastrados())
        model.addAttribute("localSelecionado", local)
        return "inventarios"
    }

    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @GetMapping("/{id}")
    fun detalhe(@PathVariable id: String, model: Model): String {
        val detalhe = service.detalhar(id)
        model.addAttribute("inventario", detalhe.resumo)
        model.addAttribute("itens", detalhe.itens)
        model.addAttribute("comparacao", esperados.faltandoNoInventario(id))
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
        val faltando = comparacao.faltando.map {
            listOf(null, it.tag, situacaoFaltando, it.patrimonio, it.codigoInterno, it.tipo, it.marca, it.modelo, null)
        }

        val planilha = Planilha(
            nome = "Inventário ${inventario.local}",
            colunas = listOf("Lida em", "Etiqueta (EPC)", "Situação", "Patrimônio", "Código", "Tipo", "Marca", "Modelo", "Operador"),
            linhas = lidas + faltando
        )

        log.info("{} exportou o inventário {} de '{}' em {}: {} etiqueta(s) lida(s){}",
            auth.name, id, inventario.local, tipoArquivo.extensao.uppercase(), detalhe.itens.size,
            if (comparacao.referencia.temReferencia) " e ${comparacao.faltando.size} faltando em relação ao inventário ${comparacao.referencia.inventarioReferencia}"
            else " (primeiro inventário do local, sem comparação)")

        // Ex.: inventario-almoxarifado-2026-10-14 (dia em que o inventário começou)
        return respostaPlanilha(planilha, tipoArquivo, "inventario-${inventario.local}-${inventario.iniciadoEm.toLocalDate()}")
    }
}
