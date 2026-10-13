package org.example.controller

import jakarta.servlet.http.HttpServletRequest
import org.example.request.CadastroRequest
import org.example.service.CadastroColetorService
import org.example.service.CatalogoColetor
import org.example.service.ColetorSemPermissaoException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.example.security.nomeOperador
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
    private val service: CadastroColetorService
) {

    /** Tipos, marcas e modelos para o formulário, e se este coletor pode cadastrar. */
    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @GetMapping("/catalogo")
    fun catalogo(auth: Authentication): CatalogoColetor = service.catalogo(auth.name)

    // Operador com a permissão de cadastro da web (RFID/CREATE) E coletor liberado (verificado no service)
    @PreAuthorize("@authService.temPermissao('RFID','CREATE')")
    @PostMapping("/tags")
    fun cadastrar(@RequestBody req: CadastroRequest, auth: Authentication, request: HttpServletRequest): Map<String, String> =
        mapOf("resultado" to service.cadastrar(req, auth.name, request.remoteAddr, auth.nomeOperador()).name)

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
