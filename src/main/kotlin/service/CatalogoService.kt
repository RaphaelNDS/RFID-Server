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

    fun salvarTipo(req: TipoEquipamentoRequest)
    fun salvarMarca(req: MarcaRequest)
    fun salvarModelo(req: ModeloRequest)
}





