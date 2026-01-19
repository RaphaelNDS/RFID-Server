//package org.example.controller
//
//import org.example.request.MarcaRequest
//import org.example.request.ModeloRequest
//import org.example.request.TipoEquipamentoRequest
//import org.example.service.CatalogoService
//import org.springframework.stereotype.Controller
//import org.springframework.ui.Model
//import org.springframework.web.bind.annotation.*
//
//@RestController
//@RequestMapping("/api/catalogo")
//class CatalogoController(
//    private val catalogoService: CatalogoService
//) {
//
//    @PostMapping("/tipo")
//    fun salvarTipo(@RequestBody req: TipoEquipamentoRequest) =
//        catalogoService.salvarTipo(req)
//
//    @PostMapping("/marca")
//    fun salvarMarca(@RequestBody req: MarcaRequest) =
//        catalogoService.salvarMarca(req)
//
//    @PostMapping("/modelo")
//    fun salvarModelo(@RequestBody req: ModeloRequest) =
//        catalogoService.salvarModelo(req)
//
//    @GetMapping("/marcas")
//    fun marcasPorTipo(@RequestParam tipoId: Long) =
//        catalogoService.listarMarcasPorTipo(tipoId)
//
//    @GetMapping("/modelos")
//    fun modelosPorMarca(@RequestParam marcaId: Long) =
//        catalogoService.listarModelosPorMarca(marcaId)
//}
//
//
//
