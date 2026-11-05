package org.example.controller

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException

private val log = LoggerFactory.getLogger("org.example.controller.ReenvioSimultaneo")

/**
 * O coletor reenvia o que ficou sem resposta, e o reenvio pode chegar com o primeiro envio ainda gravando. Os dois
 * conferem "já existe?", os dois respondem "não", e o segundo bate no índice único: antes respondia 409 ("Registro
 * duplicado") mesmo com tudo gravado pelo primeiro. Aqui o segundo tenta de novo, uma vez, numa transação nova: agora
 * o que o primeiro gravou já está no banco, e o serviço responde como reenvio (o mesmo resultado, sem duplicar).
 *
 * Só para operações idempotentes do coletor (abrir inventário, enviar etiquetas, cadastro pelo coletor). Fora de
 * transação: cada [bloco] abre a sua no serviço.
 */
fun <T> repetindoSeReenvioSimultaneo(operacao: String, bloco: () -> T): T =
    try {
        bloco()
    } catch (e: DataIntegrityViolationException) {
        log.info("{}: envio simultâneo com outro igual (o outro gravou primeiro); repetindo como reenvio", operacao)
        bloco()
    }
