package org.example.controller

import org.example.model.AreaAuditoria
import org.example.model.MotivoBaixa
import org.example.model.StatusInventario
import org.example.service.AcaoDivergencia
import org.example.service.AuditoriaService
import org.example.service.DivergenciaInventarioService
import org.example.service.EsperadoInventarioService
import org.example.service.InventarioService
import org.example.service.TransferenciaService
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
    private val auditoria: AuditoriaService,
    // 1.6.1: ação sobre as divergências (bens que faltaram) e as transferências que saíram delas
    private val divergencias: DivergenciaInventarioService,
    private val transferencias: TransferenciaService
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
        val comparacao = esperados.faltandoNoInventario(id)
        model.addAttribute("comparacao", comparacao)
        model.addAttribute("justificativas", service.justificativasDo(id))
        // Divergências (1.6.1): como cada bem que faltou está AGORA no cadastro e o que já foi feito a partir daqui
        model.addAttribute("atuais", divergencias.situacaoAtual(comparacao.faltando.map { it.tag }))
        model.addAttribute("transferenciasDoInventario", transferencias.doInventario(id))
        model.addAttribute("motivosBaixa", MotivoBaixa.entries)
        model.addAttribute("idLocal", detalhe.resumo.localId)
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
     * Ação sobre os bens que faltaram (1.6.1): marcar como extraviados ou dar baixa nos marcados. A transferência vai
     * pelo botão "Transferir", que manda os marcados para o formulário de transferência (/admin/transferencias/nova).
     */
    @PreAuthorize("@authService.temPermissao('RFID','UPDATE')")
    @org.springframework.web.bind.annotation.PostMapping("/{id}/divergencias")
    fun tratarDivergencias(
        @PathVariable id: String,
        @RequestParam(required = false) tags: List<String>?,
        @RequestParam(required = false) acao: String?,
        @RequestParam(required = false) motivoBaixa: String?,
        @RequestParam(required = false) observacaoBaixa: String?,
        redirect: org.springframework.web.servlet.mvc.support.RedirectAttributes
    ): String {
        try {
            val escolhida = when (acao) {
                "extraviado" -> AcaoDivergencia.EXTRAVIADO
                "baixa" -> AcaoDivergencia.BAIXA
                else -> throw IllegalArgumentException("Ação inválida")
            }
            val motivoEscolhido = if (escolhida == AcaoDivergencia.BAIXA)
                MotivoBaixa.de(motivoBaixa) ?: throw IllegalArgumentException("Escolha o motivo da baixa") else null
            val r = divergencias.tratar(id, tags.orEmpty(), escolhida, motivoEscolhido, null, observacaoBaixa)
            val feito = if (escolhida == AcaoDivergencia.EXTRAVIADO) "marcado(s) como extraviado(s)"
                else "baixado(s): saíram da lista do que faltou e continuam no cadastro como baixados"
            redirect.addFlashAttribute("msg", "${r.tratados} bem(ns) $feito." +
                if (r.ignorados.isNotEmpty()) " ${r.ignorados.size} já estava(m) assim: ${r.ignorados.take(5).joinToString(", ")}." else "")
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:/admin/inventarios/" + org.springframework.web.util.UriUtils.encodePathSegment(id, Charsets.UTF_8)
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
