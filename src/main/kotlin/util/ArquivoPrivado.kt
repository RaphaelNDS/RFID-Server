package org.example.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal

/**
 * Deixa um arquivo com segredo (ex.: a senha do certificado HTTPS) legível só por quem precisa.
 *
 * File.setReadable(false, false) não faz nada no NTFS (o Windows usa ACL, não as permissões do Unix), e o retorno
 * era ignorado: o .senha ficava legível por qualquer usuário da máquina que lesse a pasta.
 * - POSIX (Linux): permissões 600 (só o dono lê e grava).
 * - Windows: ACL só com o dono do arquivo, o SYSTEM e o grupo Administradores, sem herdar as permissões da pasta.
 */
object ArquivoPrivado {

    // Os nomes das contas do Windows mudam com o idioma (no pt-BR: AUTORIDADE NT\SISTEMA, BUILTIN\Administradores)
    // e o Java só procura conta pelo nome: tenta os dois idiomas. A que não existir é ignorada (o dono sempre entra)
    private val CONTAS_DO_SISTEMA = listOf(
        "NT AUTHORITY\\SYSTEM", "AUTORIDADE NT\\SISTEMA",
        "BUILTIN\\Administrators", "BUILTIN\\Administradores"
    )

    /** Restringe o arquivo; lança exceção (com o motivo) se o sistema de arquivos não deixar. */
    fun restringirAoDono(arquivo: Path) {
        val posix = Files.getFileAttributeView(arquivo, PosixFileAttributeView::class.java)
        if (posix != null) {
            posix.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            return
        }

        val acl = Files.getFileAttributeView(arquivo, AclFileAttributeView::class.java)
            ?: throw UnsupportedOperationException("o sistema de arquivos não tem permissões POSIX nem ACL")

        val busca = arquivo.fileSystem.userPrincipalLookupService
        val contas: List<UserPrincipal> = (listOf(acl.owner) +
            CONTAS_DO_SISTEMA.mapNotNull { nome -> runCatching { busca.lookupPrincipalByName(nome) }.getOrNull() })
            .distinct()

        // Lista nova (sem as entradas herdadas da pasta, como "Usuários" ou "Todos"): só essas contas, com controle total
        acl.acl = contas.map { conta ->
            AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(conta)
                .setPermissions(AclEntryPermission.entries.toSet())
                .build()
        }
    }
}
