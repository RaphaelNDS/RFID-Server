package org.example.controller

import jakarta.servlet.http.HttpServletRequest
import org.example.request.CadastroRequest
import org.example.security.nomeOperador
import org.example.service.CadastroColetorService
import org.example.service.CatalogoColetor
import org.example.service.ColetorSemPermissaoException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

/**
 * Cadastro de etiquetas pelo coletor Android. Protegido pela chave do coletor
 * (SecurityConfig.dispositivosFilterChain cobre tudo em /api/android); quem pode cadastrar
 * é definido em rfid.dispositivos.cadastro.
 */
@RestController
@RequestMapping("/api/android")
class CadastroAndroidController(
    private val service: CadastroColetorService,
    // 1.31.0: coletor em treinamento não cadastra (409 com o aviso)
    private val treinamento: org.example.service.TreinamentoColetorService? = null
) {

    @ExceptionHandler(org.example.service.ColetorEmTreinamentoException::class)
    fun emTreinamento(ex: org.example.service.ColetorEmTreinamentoException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf("status" to HttpStatus.CONFLICT.value(), "mensagem" to ex.message, "caminho" to request.requestURI)
        )

    /** Tipos, marcas e modelos para o formulário, e se este coletor pode cadastrar. */
    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @GetMapping("/catalogo")
    fun catalogo(auth: Authentication): CatalogoColetor = service.catalogo(auth.name)

    // Operador com a permissão de cadastro da web (RFID/CREATE) E coletor liberado (verificado no service)
    @PreAuthorize("@authService.temPermissao('RFID','CREATE')")
    @PostMapping("/tags")
    fun cadastrar(@RequestBody req: CadastroRequest, auth: Authentication, request: HttpServletRequest): Map<String, String> {
        if (treinamento?.emTreinamento(auth.name) == true) {
            org.slf4j.LoggerFactory.getLogger(javaClass).info("Cadastro da etiqueta {} pelo coletor {} recusado: coletor em treinamento", req.tag.trim(), auth.name)
            throw org.example.service.ColetorEmTreinamentoException(org.example.service.TreinamentoColetorService.AVISO_CADASTRO)
        }
        return mapOf("resultado" to repetindoSeReenvioSimultaneo("Cadastro da etiqueta ${req.tag.trim()} pelo coletor") {
            service.cadastrar(req, auth.name, request.remoteAddr, auth.nomeOperador())
        }.name)
    }

    // Fica aqui (e não no TratamentoErros global) porque é específico do coletor: 403 com a mensagem em JSON
    @ExceptionHandler(ColetorSemPermissaoException::class)
    fun semPermissao(ex: ColetorSemPermissaoException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            mapOf(
                "status" to HttpStatus.FORBIDDEN.value(),
                "mensagem" to ex.message,
                "caminho" to request.requestURI
            )
        )
}
