package org.example.controller

import org.example.model.EstadoDevolucao
import org.example.service.AcessorioService
import org.example.service.AuthService
import org.example.service.CatalogoService
import org.example.service.DadosPedido
import org.example.service.FiltroPedido
import org.example.service.LocacaoService
import org.example.service.PdfService
import org.example.service.QuantidadesAcessorio
import org.example.util.FormatoBr
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.LocalDate

/**
 * Pedidos de locação (1.12.0): lista, novo pedido, o pedido (linhas, saída e devolução pela web, conferência da
 * devolução, encerrar separação, cancelar), a fila de devoluções a conferir e o contrato em PDF. Módulo Locação (1.15.0):
 * Ver; criar pedido e acrescentar linha: Criar; saída, devolução, conferência e o resto: Editar. Mandar para a manutenção na
 * conferência pede também Manutenção · Criar.
 */
@Controller
@RequestMapping("/admin/pedidos")
class PedidoWebController(
    private val service: LocacaoService,
    private val catalogo: CatalogoService,
    private val pdf: PdfService,
    private val auth: AuthService,
    // 1.18.0: kits e acessórios
    private val acessorios: AcessorioService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PreAuthorize("@authService.temPermissao('LOCACAO','READ')")
    @GetMapping
    fun listar(
        @RequestParam(required = false) situacao: String?,
        @RequestParam(required = false) busca: String?,
        @RequestParam(defaultValue = "1") pagina: Int,
        model: Model
    ): String {
        val filtro = FiltroPedido.de(situacao)
        val resultado = service.listar(filtro, busca, pagina - 1)
        model.addAttribute("pedidos", resultado.content)
        model.addAttribute("filtro", filtro)
        model.addAttribute("filtros", FiltroPedido.entries)
        model.addAttribute("busca", busca?.trim().orEmpty())
        model.addAttribute("atrasados", service.contarAtrasados())
        model.addAttribute("aConferir", service.contarAguardandoConferencia())
        model.adicionarPaginacao(resultado, "/admin/pedidos", mapOf("situacao" to filtro.name, "busca" to busca))
        return "pedidos"
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','CREATE')")
    @GetMapping("/novo")
    fun novo(@RequestParam(required = false) cliente: Long?, model: Model): String {
        formulario(model, DadosPedido(cliente, null), null)
        return "pedido-novo"
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','CREATE')")
    @PostMapping
    fun criar(
        @RequestParam(required = false) cliente: Long?, @RequestParam(required = false) previsao: String?,
        @RequestParam(required = false) saida: String?, @RequestParam(required = false) localEntrega: String?,
        @RequestParam(required = false) observacao: String?, model: Model, redirect: RedirectAttributes
    ): String {
        var dados = DadosPedido(cliente, null, null, localEntrega, observacao)
        return try {
            dados = DadosPedido(cliente, dia(previsao, "devolução prevista"), dia(saida, "saída prevista"), localEntrega, observacao)
            val p = service.criarPedido(dados)
            redirect.addFlashAttribute("msg", "Pedido nº ${p.id} criado. Agora acrescente os equipamentos (linhas) do pedido.")
            "redirect:/admin/pedidos/${p.id}"
        } catch (e: IllegalArgumentException) {
            formulario(model, dados, e.message)
            model.addAttribute("previsaoTexto", previsao)
            model.addAttribute("saidaTexto", saida)
            "pedido-novo"
        }
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','READ')")
    @GetMapping("/{id}")
    fun detalhe(@PathVariable id: Long, model: Model): String {
        val d = service.detalhar(id)
        model.addAttribute("d", d)
        model.addAttribute("hoje", service.hoje())
        model.addAttribute("tipos", catalogo.listarTipos())
        model.addAttribute("estados", EstadoDevolucao.entries)
        model.addAttribute("clientes", service.clientesAtivos())
        model.addAttribute("acessoriosAtivos", acessorios.ativos())
        // 1.19.0: restrição do cliente
        model.addAttribute("impedimento", service.impedimentoSaida(d.pedido))
        model.addAttribute("podeAprovar", service.podeAprovar())
        model.addAttribute("restricaoCliente", service.cliente(d.pedido.clienteId).restricao)
        return "pedido"
    }

    /** 1.19.0: aprova o pedido de cliente "só com aprovação". Quem pode: ver LocacaoService.podeAprovar. */
    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @PostMapping("/{id}/aprovar")
    fun aprovar(@PathVariable id: Long, redirect: RedirectAttributes): String = acao(id, redirect) {
        service.aprovarPedido(id)
        "Pedido aprovado: a saída está liberada."
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @PostMapping("/{id}")
    fun editar(
        @PathVariable id: Long, @RequestParam(required = false) cliente: Long?, @RequestParam(required = false) previsao: String?,
        @RequestParam(required = false) saida: String?, @RequestParam(required = false) localEntrega: String?,
        @RequestParam(required = false) observacao: String?, redirect: RedirectAttributes
    ): String = acao(id, redirect) {
        service.editarPedido(id, DadosPedido(cliente, dia(previsao, "devolução prevista"), dia(saida, "saída prevista"), localEntrega, observacao))
        "Pedido alterado."
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','CREATE')")
    @PostMapping("/{id}/linhas")
    fun adicionarLinha(
        @PathVariable id: Long,
        @RequestParam(required = false) tipoEquipamentoId: Long?, @RequestParam(required = false) marcaId: Long?,
        @RequestParam(required = false) modeloId: Long?, @RequestParam(required = false) quantidade: Int?,
        @RequestParam(required = false) bem: String?, redirect: RedirectAttributes
    ): String = acao(id, redirect) {
        val l = service.adicionarLinha(id, tipoEquipamentoId, marcaId, modeloId, quantidade, bem)
        val kit = acessorios.doPedido(id).filter { it.linhaId == l.id }
        "Linha acrescentada: ${l.quantidade} × ${l.descricao}." + if (kit.isEmpty()) "" else " Com o kit: ${acessorios.resumo(kit)}."
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @PostMapping("/{id}/linhas/{linhaId}/excluir")
    fun removerLinha(@PathVariable id: Long, @PathVariable linhaId: Long, redirect: RedirectAttributes): String = acao(id, redirect) {
        service.removerLinha(id, linhaId)
        "Linha removida do pedido."
    }

    /** Saída pela web (sem coletor): etiquetas ou patrimônios, um por linha. */
    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @PostMapping("/{id}/saida")
    fun saida(@PathVariable id: Long, @RequestParam(required = false) codigos: String?, redirect: RedirectAttributes): String = acao(id, redirect) {
        val r = try {
            service.registrarSaida(id, separar(codigos))
        } catch (e: IllegalStateException) {
            throw IllegalArgumentException(e.message)
        } catch (e: DataIntegrityViolationException) {
            throw IllegalArgumentException("Um destes equipamentos acabou de sair em outro pedido. Confira e tente de novo.")
        }
        listOfNotNull(
            "Saída de ${r.aceitos.size} equipamento(s) registrada. Pedido ${r.pedido.situacao.rotulo.lowercase()}.",
            r.conferencia.itens.count { it.jaSaiu }.takeIf { it > 0 }?.let { "$it já tinha(m) saído." },
            r.recusados.takeIf { it.isNotEmpty() }?.let { lista -> "Recusado(s): " + lista.take(10).joinToString("; ") { "${it.codigo} (${it.problema!!.rotulo.lowercase()})" } + "." }
        ).joinToString(" ")
    }

    /** Devolução pela web (sem coletor). */
    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @PostMapping("/{id}/devolucao")
    fun devolucao(@PathVariable id: Long, @RequestParam(required = false) codigos: String?, redirect: RedirectAttributes): String = acao(id, redirect) {
        val r = service.registrarDevolucao(separar(codigos))
        listOfNotNull(
            "${r.recebidos.size} equipamento(s) recebido(s): ficam Em devolução até a conferência.",
            r.recebidos.filter { it.second.id != id }.takeIf { it.isNotEmpty() }?.let { o -> "${o.size} era(m) de outro pedido (nº ${o.map { it.second.id }.distinct().joinToString()})." },
            r.semLocacao.takeIf { it.isNotEmpty() }?.let { s -> "Sem locação: " + s.take(10).joinToString(", ") { it.first } + "." }
        ).joinToString(" ")
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @PostMapping("/{id}/itens/{itemId}/conferencia")
    fun conferirDevolucao(
        @PathVariable id: Long, @PathVariable itemId: Long,
        @RequestParam(required = false) estado: String?, @RequestParam(required = false) destino: String?,
        @RequestParam(required = false) observacao: String?, @RequestParam(required = false) voltar: String?,
        redirect: RedirectAttributes
    ): String {
        val destinoTela = if (voltar == "fila") "redirect:/admin/pedidos/devolucoes" else "redirect:/admin/pedidos/$id"
        try {
            // 1.15.0: mandar para a manutenção abre uma ordem: pede também Manutenção · Criar
            require(destino != "MANUTENCAO" || auth.temPermissao("MANUTENCAO", "CREATE")) {
                "Para mandar para a manutenção é preciso a permissão Manutenção · Criar. Mande para Disponível ou peça a quem tem."
            }
            val e = EstadoDevolucao.entries.firstOrNull { it.name == estado }
            val item = service.conferirDevolucao(itemId, e, destino == "MANUTENCAO", observacao)
            redirect.addFlashAttribute("msg", "Devolução de ${item.patrimonio ?: item.tagReal} conferida: " +
                (item.manutencaoId?.let { "foi para a manutenção nº $it." } ?: "está Disponível."))
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return destinoTela
    }

    /** Prorrogação (1.16.0): nova devolução prevista e o motivo; fica no histórico do pedido e no contrato. */
    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @PostMapping("/{id}/prorrogacao")
    fun prorrogar(
        @PathVariable id: Long, @RequestParam(required = false) data: String?, @RequestParam(required = false) motivo: String?,
        redirect: RedirectAttributes
    ): String = acao(id, redirect) {
        val p = service.prorrogar(id, dia(data, "devolução"), motivo)
        "Devolução prevista mudada para ${FormatoBr.data(p.previsaoDevolucao)}. A mudança ficou no histórico do pedido."
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @PostMapping("/{id}/separacao/encerrar")
    fun encerrarSeparacao(@PathVariable id: Long, redirect: RedirectAttributes): String = acao(id, redirect) {
        val p = service.encerrarSeparacao(id)
        "Separação encerrada com o que saiu. Pedido ${p.situacao.rotulo.lowercase()}."
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @PostMapping("/{id}/cancelamento")
    fun cancelar(@PathVariable id: Long, @RequestParam(required = false) motivo: String?, redirect: RedirectAttributes): String = acao(id, redirect) {
        service.cancelarPedido(id, motivo)
        "Pedido cancelado."
    }

    /**
     * Acessórios do pedido (1.18.0): quantos foram enviados e devolvidos de cada um (campos enviada_<id> e
     * devolvida_<id>), ou [acao] = enviarTudo / devolverTudo.
     */
    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @PostMapping("/{id}/acessorios")
    fun registrarAcessorios(@PathVariable id: Long, @RequestParam campos: Map<String, String>, redirect: RedirectAttributes): String = acao(id, redirect) {
        when (campos["acao"]) {
            "enviarTudo" -> { acessorios.enviarTudo(id); "Todos os acessórios pedidos foram marcados como enviados." }
            "devolverTudo" -> { acessorios.devolverTudo(id); "Todos os acessórios enviados foram marcados como devolvidos." }
            else -> {
                fun numero(nome: String): Int? = campos[nome]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    it.toIntOrNull() ?: throw IllegalArgumentException("Quantidade inválida: $it")
                }
                val ids = campos.keys.mapNotNull { Regex("^(enviada|devolvida)_(\\d+)$").find(it)?.groupValues?.get(2)?.toLong() }.toSet()
                val lista = acessorios.registrar(id, ids.associateWith { QuantidadesAcessorio(numero("enviada_$it"), numero("devolvida_$it")) })
                val faltam = lista.sumOf { it.faltando }
                "Acessórios registrados." + if (faltam > 0) " Faltam voltar $faltam." else ""
            }
        }
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','CREATE')")
    @PostMapping("/{id}/acessorios/avulso")
    fun acessorioAvulso(
        @PathVariable id: Long, @RequestParam(required = false) acessorioId: Long?, @RequestParam(required = false) quantidade: Int?,
        redirect: RedirectAttributes
    ): String = acao(id, redirect) {
        val a = acessorios.adicionarAvulso(id, acessorioId, quantidade)
        "Acessório acrescentado: ${a.quantidade} × ${a.descricao}."
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','UPDATE')")
    @PostMapping("/{id}/acessorios/{acessorioId}/excluir")
    fun removerAcessorio(@PathVariable id: Long, @PathVariable acessorioId: Long, redirect: RedirectAttributes): String = acao(id, redirect) {
        acessorios.removerDoPedido(id, acessorioId)
        "Acessório tirado do pedido."
    }

    /** Fila das devoluções que esperam a conferência, de todos os pedidos. */
    @PreAuthorize("@authService.temPermissao('LOCACAO','READ')")
    @GetMapping("/devolucoes")
    fun devolucoes(model: Model): String {
        model.addAttribute("fila", service.aguardandoConferencia())
        model.addAttribute("estados", EstadoDevolucao.entries)
        return "devolucoes"
    }

    /** Folha de separação (1.17.0): o que falta separar e os equipamentos disponíveis, com o local, para marcar. */
    @PreAuthorize("@authService.temPermissao('LOCACAO','READ')")
    @GetMapping("/{id}/separacao.pdf")
    @ResponseBody
    fun folhaSeparacao(@PathVariable id: Long, authentication: Authentication): ResponseEntity<ByteArray> {
        val folha = service.separacao(id)
        val bytes = pdf.gerar("pdf/folha-separacao", mapOf("f" to folha))
        log.info("{} gerou a folha de separação do pedido nº {} ({} a separar)", authentication.name, id, folha.faltam)
        return pdf.resposta(bytes, "separacao-pedido-$id.pdf")
    }

    @PreAuthorize("@authService.temPermissao('LOCACAO','READ')")
    @GetMapping("/{id}/contrato.pdf")
    @ResponseBody
    fun contrato(@PathVariable id: Long, auth: Authentication): ResponseEntity<ByteArray> {
        val d = service.detalhar(id)
        val cliente = service.cliente(d.pedido.clienteId)
        val bytes = pdf.gerar("pdf/contrato-locacao", mapOf("d" to d, "c" to cliente))
        log.info("{} gerou o contrato do pedido de locação nº {} em PDF ({} equipamento(s))", auth.name, id, d.itens.size)
        return pdf.resposta(bytes, "contrato-locacao-$id.pdf")
    }

    private fun acao(id: Long, redirect: RedirectAttributes, bloco: () -> String): String {
        try {
            redirect.addFlashAttribute("msg", bloco())
        } catch (e: IllegalArgumentException) {
            log.info("Pedido de locação nº {}: ação recusada: {}", id, e.message)
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:/admin/pedidos/$id"
    }

    private fun formulario(model: Model, dados: DadosPedido, erro: String?) {
        model.addAttribute("dados", dados)
        model.addAttribute("clientes", service.clientesAtivos())
        model.addAttribute("hoje", service.hoje())
        if (erro != null) model.addAttribute("erro", erro)
    }

    private fun separar(texto: String?): List<String> = texto.orEmpty().split(Regex("[\\r\\n,;\\t]+")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun dia(texto: String?, campo: String): LocalDate? = texto?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { LocalDate.parse(it) }.getOrElse { throw IllegalArgumentException("Data de $campo inválida") }
    }
}
