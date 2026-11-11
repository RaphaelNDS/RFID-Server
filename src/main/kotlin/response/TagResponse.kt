package org.example.response

import java.time.LocalDate
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

// Gestão de patrimônio (1.6.0). Opcionais: o app antigo ignora
/** Código da situação (DISPONIVEL, ALUGADO, EM_DEVOLUCAO, EM_MANUTENCAO, A_VENDA, VENDIDO, EXTRAVIADO, BAIXADO) e o nome dela. */
val situacao: String? = null,
val situacaoRotulo: String? = null,
/** Onde o bem está pelo cadastro (o ultimoLocal é o do último inventário que o encontrou). */
val unidade: String? = null,
val local: String? = null,
val responsavel: String? = null,

// Locação (1.12.0): preenchidos quando o equipamento está num pedido (ALUGADO ou EM_DEVOLUCAO)
/** Número do pedido de locação. */
val pedido: Long? = null,
val cliente: String? = null,
val previsaoDevolucao: LocalDate? = null,
/** Para onde foi: o local de entrega do pedido (o app mostra "Foi para: ..."). */
val localEntrega: String? = null
)

