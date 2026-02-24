package org.example.service

import org.example.model.MarcaEntity
import org.example.model.ModeloEntity
import org.example.model.TipoEquipamentoEntity
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest

interface CatalogoService {

    fun listarTipos(): List<TipoEquipamentoEntity>

    fun listarMarcas(): List<MarcaEntity>
    fun listarModelos(): List<ModeloEntity>

    fun listarMarcasPorTipo(tipoId: Long): List<MarcaEntity>
    fun listarModelosPorMarca(marcaId: Long): List<ModeloEntity>

//    fun salvarTipo(req: TipoEquipamentoRequest): TipoEquipamentoEntity
//    fun salvarMarca(req: MarcaRequest)
//    fun salvarModelo(req: ModeloRequest)
//
//    fun editarTipo(id: Long, req: TipoEquipamentoRequest)
//    fun editarMarca(id: Long, req: MarcaRequest)
//    fun editarModelo(id: Long, req: ModeloRequest)

    fun graficoModelosPorTipo(): Map<String, Long>

    fun excluirTipo(id: Long)
    fun excluirMarca(id: Long)
    fun excluirModelo(id: Long)



    fun salvarTipo(req: TipoEquipamentoRequest): TipoEquipamentoEntity
    fun salvarMarca(req: MarcaRequest): MarcaEntity
    fun salvarModelo(req: ModeloRequest): ModeloEntity

    fun editarTipo(id: Long, req: TipoEquipamentoRequest): TipoEquipamentoEntity
    fun editarMarca(id: Long, req: MarcaRequest): MarcaEntity
    fun editarModelo(id: Long, req: ModeloRequest): ModeloEntity

}
