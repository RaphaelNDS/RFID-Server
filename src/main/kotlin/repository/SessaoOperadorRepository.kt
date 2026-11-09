package org.example.repository

import org.example.model.SessaoOperadorEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime

interface SessaoOperadorRepository : JpaRepository<SessaoOperadorEntity, String> {

    /**
     * Encerra as sessões ainda abertas do coletor (um operador por coletor). Devolve quantas.
     * clearAutomatically: o update em massa não passa pelo cache do JPA; sem limpar, a mesma transação
     * continuaria vendo as sessões antigas como abertas.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """update SessaoOperadorEntity s set s.encerradaEm = :agora, s.encerradaPor = :motivo
           where s.dispositivo = :dispositivo and s.encerradaEm is null and s.expiraEm > :agora"""
    )
    fun encerrarAbertas(dispositivo: String, agora: LocalDateTime, motivo: String): Int

    /** Encerra as sessões abertas do usuário em todos os coletores (desativado ou senha trocada). Devolve quantas. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """update SessaoOperadorEntity s set s.encerradaEm = :agora, s.encerradaPor = :motivo
           where s.usuarioId = :usuarioId and s.encerradaEm is null and s.expiraEm > :agora"""
    )
    fun encerrarAbertasDoUsuario(usuarioId: Long, agora: LocalDateTime, motivo: String): Int

    /** Limpeza: apaga as sessões que expiraram antes de [limite] (um DELETE só). Devolve quantas. */
    @Modifying
    @Query("delete from SessaoOperadorEntity s where s.expiraEm < :limite")
    fun apagarExpiradasAntesDe(limite: LocalDateTime): Int
}
