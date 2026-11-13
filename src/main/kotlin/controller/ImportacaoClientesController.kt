package org.example.controller

import jakarta.servlet.http.HttpSession
import org.example.service.AuthService
import org.example.service.ImportacaoClientesService
import org.example.util.FormatoPlanilha
import org.example.util.LeitorPlanilha
import org.example.util.respostaPlanilha
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile
import java.io.Serializable

/**
 * Importação de clientes por planilha (1.29.0), como a de etiquetas, em duas etapas: o envio simula e mostra o que vai acontecer (nada é
 * gravado); "Confirmar" grava. Entre as duas, a planilha fica na sessão de quem enviou (some ao confirmar ou ao enviar
 * outra). Quem cadastra clientes (Locação · Criar) importa; atualizar os que já existem pede também Locação · Editar.
 */
@Controller
@RequestMapping("/admin/clientes/importar")
class ImportacaoClientesController(
    private val importacao: ImportacaoClientesService,
    private val authService: AuthService
) {

    /** Serializável: o Tomcat grava as sessões ao desligar, e um atributo que não fosse dava erro no log. */
    private class Pendente(val nome: String, val conteudo: ByteArray) : Serializable

    @PreAuthorize("@authService.temPermissao('LOCACAO','CREATE')")
    @GetMapping
    fun formulario(): String = "importar-clientes"

    @PreAuthorize("@authService.temPermissao('LOCACAO','CREATE')")
    @GetMapping("/modelo")
    fun modelo(): ResponseEntity<ByteArray> = respostaPlanilha(importacao.modelo(), FormatoPlanilha.XLSX, "modelo-importacao-clientes")

    /** Envio da planilha: simula (não grava) e mostra linha a linha. */
    @PreAuthorize("@authService.temPermissao('LOCACAO','CREATE')")
    @PostMapping
    fun simular(@RequestParam("arquivo") arquivo: MultipartFile,
                sessao: HttpSession, model: Model): String {
        sessao.removeAttribute(CHAVE)
        try {
            require(!arquivo.isEmpty) { "Escolha a planilha (.xlsx ou .csv)" }
            require(arquivo.size <= ImportacaoClientesService.MAXIMO_BYTES) { "A planilha pode ter no máximo 10 MB" }
            val nome = arquivo.originalFilename?.substringAfterLast('/')?.substringAfterLast('\\')?.take(150).orEmpty().ifEmpty { "planilha" }
            val conteudo = arquivo.bytes
            val resultado = importacao.processar(nome, conteudo, podeAlterar(), gravar = false)
            sessao.setAttribute(CHAVE, Pendente(nome, conteudo))
            model.addAttribute("resultado", resultado)
            model.addAttribute("arquivo", nome)
        } catch (e: LeitorPlanilha.PlanilhaInvalidaException) {
            model.addAttribute("erro", e.message)
        } catch (e: IllegalArgumentException) {
            model.addAttribute("erro", e.message)
        }
        return "importar-clientes"
    }

    /** Confirmação: grava a planilha simulada (a que está na sessão). */
    @PreAuthorize("@authService.temPermissao('LOCACAO','CREATE')")
    @PostMapping("/confirmar")
    fun confirmar(sessao: HttpSession, model: Model): String {
        val pendente = sessao.getAttribute(CHAVE) as? Pendente
        if (pendente == null) {
            model.addAttribute("erro", "A planilha não está mais aqui (a sessão expirou ou ela já foi importada): envie de novo")
            return "importar-clientes"
        }
        sessao.removeAttribute(CHAVE)
        try {
            model.addAttribute("resultado", importacao.processar(pendente.nome, pendente.conteudo, podeAlterar(), gravar = true))
            model.addAttribute("arquivo", pendente.nome)
        } catch (e: IllegalArgumentException) {
            model.addAttribute("erro", e.message)
        }
        return "importar-clientes"
    }

    private fun podeAlterar() = authService.temPermissao("LOCACAO", "UPDATE")

    private companion object {
        const val CHAVE = "importacaoClientes"
    }
}
