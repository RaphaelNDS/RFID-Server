package org.example.controller

import jakarta.servlet.http.HttpServletRequest
import org.example.model.AreaAuditoria
import org.example.service.AuditoriaService
import org.example.service.ColetorService
import org.example.service.ConfiguracaoColetorService
import org.example.service.ErroColetorService
import org.example.service.SituacaoContato
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException

/** Tela Coletores: último contato, IP, versão do app, operador e erros de cada coletor. Só ADMIN, como Usuários. */
@Controller
@RequestMapping("/admin/coletores")
class ColetorWebController(
    private val coletores: ColetorService,
    private val erros: ErroColetorService,
    private val configuracao: ConfiguracaoColetorService,
    private val auditoria: AuditoriaService,
    // 1.31.0: modo treinamento por coletor
    private val treinamento: org.example.service.TreinamentoColetorService? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 1.31.0: liga ou desliga o modo treinamento do coletor. */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{nome}/treinamento")
    fun treinamento(@PathVariable nome: String, @RequestParam ligado: Boolean,
                    redirect: org.springframework.web.servlet.mvc.support.RedirectAttributes): String {
        val servico = treinamento ?: throw ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND)
        try {
            servico.definir(nome, ligado)
            redirect.addFlashAttribute("msg", if (ligado)
                "Coletor $nome em modo treinamento: as leituras e os inventários dele não mudam nada de verdade."
            else "Coletor $nome fora do modo treinamento: volta a gravar normalmente.")
        } catch (e: NoSuchElementException) {
            throw ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, e.message)
        }
        return "redirect:/admin/coletores"
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    fun listar(model: Model): String {
        val lista = coletores.listar()

        model.addAttribute("coletores", lista)
        model.addAttribute("emUso", lista.count { it.situacao == SituacaoContato.EM_USO })
        model.addAttribute("desatualizados", lista.count { it.desatualizado })
        model.addAttribute("semContato", lista.count { it.situacao == SituacaoContato.SEM_CONTATO || it.situacao == SituacaoContato.NUNCA })
        model.addAttribute("atencao", lista.count { it.filaParada || it.relogioErrado || it.poucoEspaco })
        model.addAttribute("versaoMaisNova", lista.filter { it.codigoVersao != null }.maxByOrNull { it.codigoVersao!! }
            ?.let { ColetorService.descreverVersao(it.versaoApp, it.codigoVersao) })
        model.addAttribute("versaoPublicada", coletores.versaoPublicada()?.let { ColetorService.descreverVersao(it.versaoNome, it.codigo) })
        model.addAttribute("erros24h", erros.contarUltimas24h())
        model.addAttribute("comChave", lista.map { it.nome }.filter(configuracao::temChave).toSet())
        model.addAttribute("emTreinamento", treinamento?.nomesEmTreinamento().orEmpty())
        return "coletores"
    }

    /** Erros do app enviados pelo coletor, do mais recente para o mais antigo, com filtro por versão do app e por tipo. */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{nome}/erros")
    fun erros(@PathVariable nome: String, @RequestParam(required = false) versao: String?,
              @RequestParam(required = false) tipo: String?, model: Model): String {
        model.addAttribute("coletor", nome)
        model.addAttribute("erros", erros.recentes(nome, versao, tipo))
        model.addAttribute("versoes", erros.versoesComErro(nome))
        model.addAttribute("versaoSelecionada", versao)
        model.addAttribute("tipos", erros.tiposComErro(nome).associateWith { ErroColetorService.rotuloTipo(it) })
        model.addAttribute("tipoSelecionado", tipo)
        model.addAttribute("rotulosTipo", ErroColetorService.ROTULOS_TIPO)
        // 1.30.0: o que fazer nos erros conhecidos (mensagem do leitor Zebra em inglês etc.)
        model.addAttribute("dicas", org.example.service.DicasErroColetor)
        return "coletor-erros"
    }

    /**
     * QR code de configuração do coletor (1.5.0): servidor, chave e impressão digital do certificado, para o app
     * se configurar lendo o código. Leva a chave do coletor, então: só ADMIN, só por POST (o clique em "Mostrar QR",
     * com o token CSRF: um link ou uma pré-visualização não abre) e cada vez fica na auditoria.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{nome}/qr")
    fun qr(@PathVariable nome: String, request: HttpServletRequest, auth: Authentication, model: Model): String {
        val qr = configuracao.qr(nome, request.serverName)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "O coletor $nome não tem chave configurada no servidor")
        log.info("QR de configuração do coletor {} (com a chave) mostrado para {} ({})", nome, auth.name, qr.endereco)
        auditoria.registrar(AreaAuditoria.COLETORES, "QR de configuração mostrado", nome, "Endereço ${qr.endereco}")
        model.addAttribute("qr", qr)
        return "coletor-qr"
    }
}
