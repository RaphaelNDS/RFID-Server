package org.example.controller

import org.example.service.AtualizacaoAppService
import org.example.util.LeitorApk
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/** Tela "App dos coletores": publicar uma versão nova do APK e ver o histórico. Somente ADMIN. */
@Controller
@RequestMapping("/admin/app")
class AtualizacaoAppWebController(
    private val atualizacao: AtualizacaoAppService
) {

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    fun tela(model: Model): String {
        model.addAttribute("publicada", atualizacao.versaoPublicada())
        model.addAttribute("versoes", atualizacao.historico())
        model.addAttribute("publicacaoLigada", atualizacao.publicacaoLigada)
        model.addAttribute("certificadoEsperado", atualizacao.certificadoEsperado.takeIf { it.isNotEmpty() }?.let(LeitorApk::formatarImpressao))
        model.addAttribute("pacote", atualizacao.pacoteEsperado)
        model.addAttribute("versoesGuardadas", atualizacao.versoesGuardadas)
        model.addAttribute("tamanhoMaximoMb", AtualizacaoAppService.TAMANHO_MAXIMO / (1024 * 1024))
        return "app-atualizacao"
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    fun publicar(
        @RequestParam("apk") apk: MultipartFile,
        @RequestParam(required = false) notas: String?,
        auth: Authentication,
        redirect: RedirectAttributes
    ): String {
        try {
            if (apk.isEmpty) throw IllegalArgumentException("Escolha o arquivo .apk para enviar")
            val versao = apk.inputStream.use { atualizacao.publicar(it, notas, auth.name) }
            redirect.addFlashAttribute("msg",
                "Versão ${versao.versaoNome} (código ${versao.codigo}) publicada. Os coletores vão oferecer a atualização na tela de login.")
        } catch (e: IllegalArgumentException) {
            recusado(e.message, notas, redirect)
        } catch (e: IllegalStateException) {
            recusado(e.message, notas, redirect)
        }
        return "redirect:/admin/app"
    }

    /** APK recusado: a tela volta com o erro e com as notas que foram digitadas (antes o campo voltava vazio). */
    private fun recusado(mensagem: String?, notas: String?, redirect: RedirectAttributes) {
        redirect.addFlashAttribute("erro", mensagem)
        notas?.takeIf { it.isNotBlank() }?.let { redirect.addFlashAttribute("notas", it) }
    }

    /** Retira uma versão publicada com problema (os coletores voltam a receber a anterior). */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{codigo}/retirar")
    fun retirar(
        @PathVariable codigo: Int,
        @RequestParam(required = false) motivo: String?,
        auth: Authentication,
        redirect: RedirectAttributes
    ): String {
        try {
            val oferecida = atualizacao.retirar(codigo, motivo, auth.name)
            redirect.addFlashAttribute("msg", "Versão de código $codigo retirada. " +
                (oferecida?.let { "Os coletores que ainda não atualizaram passam a receber a ${it.versaoNome} (código ${it.codigo})." }
                    ?: "Nenhuma versão fica disponível para os coletores."))
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        } catch (e: IllegalStateException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:/admin/app"
    }
}
