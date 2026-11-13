package org.example.controller

import org.example.model.AreaAuditoria
import org.example.model.SituacaoBem
import org.example.service.AuditoriaService
import org.example.service.BackupService
import org.example.service.FiltroEtiquetas
import org.example.service.PatrimonioCadastrosService
import org.example.service.RfidService
import org.example.service.StatusService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@PreAuthorize("hasRole('ADMIN')")
@Controller
@RequestMapping("/admin")
class AdminController(
    private val rfidService: RfidService,
    private val statusService: StatusService,
    private val backupService: BackupService,
    private val auditoria: AuditoriaService,
    private val cadastros: PatrimonioCadastrosService? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun dashboard(model: Model): String {
        model.addAttribute("info", statusService.getStatus())
        return "admin"
    }

    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping("/tags-pendentes")
    fun pendentes(
        @RequestParam(required = false) busca: String?,
        @RequestParam(defaultValue = "1") pagina: Int,
        model: Model
    ): String {
        // Na tela as páginas começam em 1; no serviço, em 0
        val resultado = rfidService.listarNaoCadastradas(busca, pagina - 1)
        model.addAttribute("pendentes", resultado.content)
        paginacao(model, resultado, "/admin/tags-pendentes", busca)
        return "admin-tags-pendentes"
    }

    /** Etiquetas, com os filtros: situação, local e os baixados. */
    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping("/tags-cadastradas")
    fun cadastradas(
        @RequestParam(required = false) busca: String?,
        @RequestParam(defaultValue = "1") pagina: Int,
        @RequestParam(required = false) situacao: String?,
        @RequestParam(required = false) local: Long?,
        @RequestParam(required = false) baixados: Boolean?,
        model: Model
    ): String {
        val filtro = filtro(situacao, local, baixados)
        val resultado = rfidService.listarParaAdmin(busca, pagina - 1, filtro)
        model.addAttribute("tags", resultado.content)
        model.addAttribute("filtro", filtro)
        model.addAttribute("situacoes", SituacaoBem.entries)
        model.addAttribute("opcoesLocal", cadastros?.opcoesDeLocal(incluirInativos = true).orEmpty())
        paginacao(model, resultado, "/admin/tags-cadastradas", busca, filtro.parametros())
        // Exportação com a mesma busca e os mesmos filtros da tela
        fun exportar(formato: String) = org.springframework.web.util.UriComponentsBuilder.fromPath("/admin/tags-cadastradas/exportar")
            .apply {
                busca?.trim()?.takeIf { it.isNotEmpty() }?.let { queryParam("busca", it) }
                filtro.parametros().forEach { (k, v) -> queryParam(k, v) }
            }.queryParam("formato", formato).encode().toUriString()
        model.addAttribute("linkExportarXlsx", exportar("xlsx"))
        model.addAttribute("linkExportarCsv", exportar("csv"))
        return "admin-tags"
    }

    /** Paginação das telas com busca: a busca e os filtros vão no modelo (campo e "Limpar") e nos links das páginas. */
    private fun paginacao(model: Model, resultado: Page<*>, caminho: String, busca: String?, filtros: Map<String, Any?> = emptyMap()) {
        val termo = busca?.trim().orEmpty()
        model.addAttribute("busca", termo)
        model.adicionarPaginacao(resultado, caminho, mapOf("busca" to termo) + filtros)
    }

    companion object {
        /** Filtros da tela (e da exportação): valor inválido é ignorado, nunca erro. */
        fun filtro(situacao: String?, local: Long?, baixados: Boolean?) = FiltroEtiquetas(
            situacao = SituacaoBem.de(situacao),
            localId = local?.takeIf { it > 0 },
            incluirBaixados = baixados == true
        )

        /** Os filtros como parâmetros de link (paginação e exportação); só os escolhidos. */
        fun FiltroEtiquetas.parametros(): Map<String, Any?> = linkedMapOf<String, Any?>(
            "situacao" to situacao?.name, "local" to localId, "baixados" to if (incluirBaixados) true else null
        ).filterValues { it != null }
    }

    // Backup manual (só ADMIN, pela regra da classe), ex.: antes de atualizar o sistema
    @PostMapping("/backup")
    fun fazerBackup(auth: Authentication, redirect: RedirectAttributes): String {
        log.info("Backup manual solicitado por {}", auth.name)

        try {
            val arquivo = backupService.fazerBackup("manual, por ${auth.name}")
            auditoria.registrar(AreaAuditoria.BACKUP, "Backup manual do certificado", arquivo.fileName.toString())
            redirect.addFlashAttribute("msg", "Backup concluído: ${arquivo.fileName}")
        } catch (e: IllegalStateException) {
            redirect.addFlashAttribute("erro", e.message)
        }

        return "redirect:/status"
    }
}
