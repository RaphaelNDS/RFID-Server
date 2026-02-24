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
    private val service: CatalogoService
) {

    // CREATE
    @PostMapping("/tipo")
    fun tipo(@RequestBody req: TipoEquipamentoRequest) =
        service.salvarTipo(req)

    @PostMapping("/marca")
    fun marca(@RequestBody req: MarcaRequest) =
        service.salvarMarca(req)

    @PostMapping("/modelo")
    fun modelo(@RequestBody req: ModeloRequest) =
        service.salvarModelo(req)

    // LIST DEPENDENTE

    @GetMapping("/tipos")
    fun listarTipos() = service.listarTipos()

    @GetMapping("/marcas")
    fun marcas(@RequestParam tipoId: Long) =
        service.listarMarcasPorTipo(tipoId).map {
            MarcaDTO(it.id, it.nome)
        }

    @GetMapping("/modelos")
    fun modelos(@RequestParam marcaId: Long) =
        service.listarModelosPorMarca(marcaId).map {
            ModeloDTO(it.id, it.nome)
        }

    // DELETE
    @DeleteMapping("/tipo/{id}")
    fun delTipo(@PathVariable id: Long) = service.excluirTipo(id)

    @DeleteMapping("/marca/{id}")
    fun delMarca(@PathVariable id: Long) = service.excluirMarca(id)

    @DeleteMapping("/modelo/{id}")
    fun delModelo(@PathVariable id: Long) = service.excluirModelo(id)

    // UPDATE
    @PutMapping("/tipo/{id}")
    fun putTipo(@PathVariable id: Long, @RequestBody req: TipoEquipamentoRequest) =
        service.editarTipo(id, req)

    @PutMapping("/marca/{id}")
    fun putMarca(@PathVariable id: Long, @RequestBody req: MarcaRequest) =
        service.editarMarca(id, req)

    @PutMapping("/modelo/{id}")
    fun putModelo(@PathVariable id: Long, @RequestBody req: ModeloRequest) =
        service.editarModelo(id, req)

    // GRAFICO
    @GetMapping("/grafico/modelos-por-tipo")
    fun grafico() = service.graficoModelosPorTipo()
}
