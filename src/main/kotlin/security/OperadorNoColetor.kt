package org.example.security

import org.springframework.security.core.Authentication
import java.security.Principal

/**
 * Principal das requisições do coletor. O NOME continua sendo o do dispositivo (coletor-01): a lista
 * de coletores liberados para cadastro, os logs do RfidService e o campo "dispositivo" dos inventários
 * dependem disso. O operador logado fica à parte, em [operador] (null em leituras antigas, de antes do
 * login de operador).
 */
class OperadorNoColetor(
    val dispositivo: String,
    val operador: UserPrincipal?
) : Principal {

    override fun getName(): String = dispositivo

    override fun toString(): String = operador?.let { "$dispositivo (operador ${it.email})" } ?: dispositivo
}

/** Operador logado no coletor desta requisição, se houver. */
fun Authentication?.operador(): UserPrincipal? = (this?.principal as? OperadorNoColetor)?.operador

/** Nome do operador para gravar nos registros (leituras, inventários). */
fun Authentication?.nomeOperador(): String? = operador()?.nome
