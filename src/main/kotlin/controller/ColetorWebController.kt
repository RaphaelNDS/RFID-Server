package org.example.controller

import org.example.service.ColetorService
import org.example.service.ErroColetorService
import org.example.service.SituacaoContato
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/** Tela Coletores: último contato, IP, versão do app, operador e erros de cada coletor. Só ADMIN, como Usuários. */
@Controller
@RequestMapping("/admin/coletores")
class ColetorWebController(
    private val coletores: ColetorService,
    private val erros: ErroColetorService
) {

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    fun listar(model: Model): String {
        val lista = coletores.listar()

        model.addAttribute("coletores", lista)
        model.addAttribute("emUso", lista.count { it.situacao == SituacaoContato.EM_USO })
        model.addAttribute("desatualizados", lista.count { it.desatualizado })
        model.addAttribute("semContato", lista.count { it.situacao == SituacaoContato.SEM_CONTATO || it.situacao == SituacaoContato.NUNCA })
        model.addAttribute("versaoMaisNova", lista.filter { it.codigoVersao != null }.maxByOrNull { it.codigoVersao!! }
            ?.let { ColetorService.descreverVersao(it.versaoApp, it.codigoVersao) })
        model.addAttribute("versaoPublicada", coletores.versaoPublicada()?.let { ColetorService.descreverVersao(it.versaoNome, it.codigo) })
        model.addAttribute("erros24h", erros.contarUltimas24h())
        return "coletores"
    }

    /** Erros do app enviados pelo coletor, do mais recente para o mais antigo, com filtro por versão do app. */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{nome}/erros")
    fun erros(@PathVariable nome: String, @RequestParam(required = false) versao: String?, model: Model): String {
        model.addAttribute("coletor", nome)
        model.addAttribute("erros", erros.recentes(nome, versao))
        model.addAttribute("versoes", erros.versoesComErro(nome))
        model.addAttribute("versaoSelecionada", versao)
        return "coletor-erros"
    }
}
