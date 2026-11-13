package org.example.response

import java.time.OffsetDateTime

data class TagResponse(
val codigoInterno: String,
val tipo: String?,
val marca: String?,
val modelo: String?,
val patrimonio: String,
val numeroSerie: String,

// Ficha do bem no coletor (1.5.0). Opcionais: null quando não há (o app mostra o que vier)
/** Local do último inventário finalizado em que a etiqueta foi encontrada. */
val ultimoLocal: String? = null,
val ultimoInventarioEm: OffsetDateTime? = null,
/** Última leitura da etiqueta (avulsa ou em lote) e o operador dela. */
val ultimaLeituraEm: OffsetDateTime? = null,
val ultimaLeituraPor: String? = null,

// Opcionais: o app antigo ignora
/** Código da situação (DISPONIVEL, FORA, EXTRAVIADO, BAIXADO) e o nome dela ("No estoque", "Fora do estoque"...). */
val situacao: String? = null,
val situacaoRotulo: String? = null,
/** Onde o bem está pelo cadastro (o ultimoLocal é o do último inventário que o encontrou). */
val unidade: String? = null,
val local: String? = null,

// 2.0.0: preenchidos quando o equipamento está fora do estoque (situação FORA), com a saída que o levou
/** Para onde foi (o destino da saída). */
val destino: String? = null,
/** Quando saiu. */
val saidaEm: OffsetDateTime? = null
)
