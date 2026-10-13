package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

enum class StatusInventario { ABERTO, FINALIZADO }

/**
 * Inventário de um local, aberto e finalizado pelo coletor.
 * O [id] é um UUID gerado no próprio coletor (o inventário pode ser feito offline).
 */
@Entity
@Table(name = "inventarios")
class InventarioEntity(

    @Id
    @Column(length = 36)
    val id: String,

    @Column(name = "local_id", nullable = false)
    val localId: Long,

    /** Nome do coletor (o mesmo da chave em rfid.dispositivos.chaves). */
    @Column(nullable = false, length = 100)
    val dispositivo: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: StatusInventario = StatusInventario.ABERTO,

    @Column(nullable = false)
    val iniciadoEm: LocalDateTime,

    var finalizadoEm: LocalDateTime? = null,

    /** Quando o servidor recebeu o inventário (difere de iniciadoEm se foi feito offline). */
    @Column(nullable = false)
    val recebidoEm: LocalDateTime = LocalDateTime.now(),

    /** Nome do operador que abriu o inventário (nulo nos anteriores ao login de operador). */
    val operador: String? = null
)
