package org.example.controller

import jakarta.servlet.http.HttpSession
import org.example.service.AuthService
import org.example.service.ImportacaoEtiquetasService
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
 * Importação de etiquetas por planilha (1.6.0), em duas etapas: o envio simula e mostra o que vai acontecer (nada é
 * gravado); "Confirmar" grava. Entre as duas, a planilha fica na sessão de quem enviou (some ao confirmar ou ao enviar
 * outra). Quem pode cadastrar etiquetas importa; atualizar as que já existem pede também a permissão de editar.
 */
@Controller
@RequestMapping("/admin/tags-cadastradas/importar")
class ImportacaoEtiquetasController(
    private val importacao: ImportacaoEtiquetasService,
    private val authService: AuthService
) {

    /** Serializável: o Tomcat grava as sessões ao desligar, e um atributo que não fosse dava erro no log. */
    private class Pendente(val nome: String, val conteudo: ByteArray, val criar: Boolean) : Serializable

    @PreAuthorize("@authService.temPermissao('RFID','CREATE')")
    @GetMapping
    fun formulario(): String = "importar-etiquetas"

    @PreAuthorize("@authService.temPermissao('RFID','CREATE')")
    @GetMapping("/modelo")
    fun modelo(): ResponseEntity<ByteArray> = respostaPlanilha(importacao.modelo(), FormatoPlanilha.XLSX, "modelo-importacao-etiquetas")

    /** Envio da planilha: simula (não grava) e mostra linha a linha. */
    @PreAuthorize("@authService.temPermissao('RFID','CREATE')")
    @PostMapping
    fun simular(@RequestParam("arquivo") arquivo: MultipartFile, @RequestParam(defaultValue = "false") criar: Boolean,
                sessao: HttpSession, model: Model): String {
        sessao.removeAttribute(CHAVE)
        try {
            require(!arquivo.isEmpty) { "Escolha a planilha (.xlsx ou .csv)" }
            require(arquivo.size <= ImportacaoEtiquetasService.MAXIMO_BYTES) { "A planilha pode ter no máximo 10 MB" }
            val nome = arquivo.originalFilename?.substringAfterLast('/')?.substringAfterLast('\\')?.take(150).orEmpty().ifEmpty { "planilha" }
            val conteudo = arquivo.bytes
            val resultado = importacao.processar(nome, conteudo, criar, podeAlterar(), gravar = false)
            sessao.setAttribute(CHAVE, Pendente(nome, conteudo, criar))
            model.addAttribute("resultado", resultado)
            model.addAttribute("arquivo", nome)
            model.addAttribute("criar", criar)
        } catch (e: LeitorPlanilha.PlanilhaInvalidaException) {
            model.addAttribute("erro", e.message)
        } catch (e: IllegalArgumentException) {
            model.addAttribute("erro", e.message)
        }
        return "importar-etiquetas"
    }

    /** Confirmação: grava a planilha simulada (a que está na sessão). */
    @PreAuthorize("@authService.temPermissao('RFID','CREATE')")
    @PostMapping("/confirmar")
    fun confirmar(sessao: HttpSession, model: Model): String {
        val pendente = sessao.getAttribute(CHAVE) as? Pendente
        if (pendente == null) {
            model.addAttribute("erro", "A planilha não está mais aqui (a sessão expirou ou ela já foi importada): envie de novo")
            return "importar-etiquetas"
        }
        sessao.removeAttribute(CHAVE)
        try {
            model.addAttribute("resultado", importacao.processar(pendente.nome, pendente.conteudo, pendente.criar, podeAlterar(), gravar = true))
            model.addAttribute("arquivo", pendente.nome)
            model.addAttribute("criar", pendente.criar)
        } catch (e: IllegalArgumentException) {
            model.addAttribute("erro", e.message)
        }
        return "importar-etiquetas"
    }

    private fun podeAlterar() = authService.temPermissao("RFID", "UPDATE")

    private companion object {
        const val CHAVE = "importacaoEtiquetas"
    }
}
