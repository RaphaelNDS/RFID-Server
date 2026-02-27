package org.example.controller

import org.example.dto.MarcaDTO
import org.example.dto.ModeloDTO
import org.example.service.CatalogoService
import org.springframework.web.bind.annotation.*
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.springframework.security.access.prepost.PreAuthorize

@RestController
@RequestMapping("/api/catalogo")
class CatalogoApiController(
    private val service: CatalogoService
) {

    /* ================= CREATE ================= */

    @PreAuthorize("@authService.temPermissao('CATALOGO','CREATE')")
    @PostMapping("/tipo")
    fun criarTipo(@RequestBody req: TipoEquipamentoRequest) =
        service.salvarTipo(req)

    @PreAuthorize("@authService.temPermissao('CATALOGO','CREATE')")
    @PostMapping("/marca")
    fun criarMarca(@RequestBody req: MarcaRequest) =
        service.salvarMarca(req)

    @PreAuthorize("@authService.temPermissao('CATALOGO','CREATE')")
    @PostMapping("/modelo")
    fun criarModelo(@RequestBody req: ModeloRequest) =
        service.salvarModelo(req)

    /* ================= READ ================= */

    @PreAuthorize("@authService.temPermissao('CATALOGO','READ')")
    @GetMapping("/tipos")
    fun listarTipos() = service.listarTipos()

    @PreAuthorize("@authService.temPermissao('CATALOGO','READ')")
    @GetMapping("/marcas")
    fun marcas(@RequestParam tipoId: Long) =
        service.listarMarcasPorTipo(tipoId).map {
            MarcaDTO(it.id, it.nome)
        }

    @PreAuthorize("@authService.temPermissao('CATALOGO','READ')")
    @GetMapping("/modelos")
    fun modelos(@RequestParam marcaId: Long) =
        service.listarModelosPorMarca(marcaId).map {
            ModeloDTO(it.id, it.nome)
        }

    /* ================= UPDATE ================= */

    @PreAuthorize("@authService.temPermissao('CATALOGO','UPDATE')")
    @PutMapping("/tipo/{id}")
    fun atualizarTipo(@PathVariable id: Long, @RequestBody req: TipoEquipamentoRequest) =
        service.editarTipo(id, req)

    @PreAuthorize("@authService.temPermissao('CATALOGO','UPDATE')")
    @PutMapping("/marca/{id}")
    fun atualizarMarca(@PathVariable id: Long, @RequestBody req: MarcaRequest) =
        service.editarMarca(id, req)

    @PreAuthorize("@authService.temPermissao('CATALOGO','UPDATE')")
    @PutMapping("/modelo/{id}")
    fun atualizarModelo(@PathVariable id: Long, @RequestBody req: ModeloRequest) =
        service.editarModelo(id, req)

    /* ================= DELETE ================= */

    @PreAuthorize("@authService.temPermissao('CATALOGO','DELETE')")
    @DeleteMapping("/tipo/{id}")
    fun delTipo(@PathVariable id: Long) =
        service.excluirTipo(id)

    @PreAuthorize("@authService.temPermissao('CATALOGO','DELETE')")
    @DeleteMapping("/marca/{id}")
    fun delMarca(@PathVariable id: Long) =
        service.excluirMarca(id)

    @PreAuthorize("@authService.temPermissao('CATALOGO','DELETE')")
    @DeleteMapping("/modelo/{id}")
    fun delModelo(@PathVariable id: Long) =
        service.excluirModelo(id)

    /* ================= DASHBOARD ================= */

    @PreAuthorize("@authService.temPermissao('CATALOGO','READ')")
    @GetMapping("/grafico/modelos-por-tipo")
    fun grafico() = service.graficoModelosPorTipo()
}
