package org.example.controller

import org.example.dto.MarcaDTO
import org.example.dto.ModeloDTO
import org.example.dto.TipoDTO
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/catalogo")
class CatalogoApiController(
    private val service: CatalogoService
) {

    companion object {
        /**
         * Listas de tipos, marcas e modelos: também para quem cadastra ou edita etiquetas (RFID Criar/Editar), que
         * escolhe marca e modelo pelos selects da tela sem ter o módulo Catálogo. Antes a API recusava (403) e a
         * edição da etiqueta salvava marca e modelo vazios.
         */
        const val LER_PARA_ETIQUETAS = "@authService.temPermissao('CATALOGO','READ') or " +
            "@authService.temPermissao('RFID','CREATE') or @authService.temPermissao('RFID','UPDATE')"
    }

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

    @PreAuthorize(LER_PARA_ETIQUETAS)
    @GetMapping("/tipos")
    fun listarTipos() = service.listarTipos().map { TipoDTO(it.id, it.nome) }

    @PreAuthorize(LER_PARA_ETIQUETAS)
    @GetMapping("/marcas")
    fun marcas(@RequestParam tipoId: Long) =
        service.listarMarcasPorTipo(tipoId).map {
            MarcaDTO(it.id, it.nome)
        }

    @PreAuthorize(LER_PARA_ETIQUETAS)
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
