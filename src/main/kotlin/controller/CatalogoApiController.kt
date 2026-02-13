package org.example.controller

import org.example.dto.MarcaDTO
import org.example.dto.ModeloDTO
import org.example.service.CatalogoService
import org.springframework.web.bind.annotation.*
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.springframework.ui.Model

@RestController
@RequestMapping("/api/catalogo")
class CatalogoApiController(
    private val catalogoService: CatalogoService
) {

    // ---------- POST ----------

    @PostMapping("/tipo")
    fun salvarTipo(@RequestBody req: TipoEquipamentoRequest) =
        catalogoService.salvarTipo(req)

        @PostMapping("/marca")
    fun salvarMarca(@RequestBody req: MarcaRequest) =
        catalogoService.salvarMarca(req)

    @PostMapping("/modelo")
    fun salvarModelo(@RequestBody req: ModeloRequest) =
        catalogoService.salvarModelo(req)

    @GetMapping("/marcas")
    fun listarMarcas(@RequestParam tipoId: Long): List<MarcaDTO> {
        return catalogoService
            .listarMarcasPorTipo(tipoId)
            .map { MarcaDTO(it.id!!, it.nome) }
    }

    // =============================
    // LISTAR MODELOS POR MARCA
    // =============================
    @GetMapping("/modelos")
    fun listarModelos(@RequestParam marcaId: Long): List<ModeloDTO> {
        return catalogoService
            .listarModelosPorMarca(marcaId)
            .map { ModeloDTO(it.id!!, it.nome) }
    }


//    @GetMapping("/marcas")
//    fun marcasPorTipo(@RequestParam tipoId: Long): List<MarcaDTO> =
//        catalogoService.listarMarcasPorTipo(tipoId)
//            .map { MarcaDTO(it.id, it.nome) }
//
//    @GetMapping("/modelos")
//    fun modelosPorMarca(@RequestParam marcaId: Long): List<ModeloDTO> =
//        catalogoService.listarModelosPorMarca(marcaId)
//            .map { ModeloDTO(it.id, it.nome) }
}
