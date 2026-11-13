package org.example.service

import org.example.model.StatusInventario
import org.example.repository.ClienteRepository
import org.example.repository.ColetorRepository
import org.example.repository.InventarioRepository
import org.example.repository.LocalRepository
import org.example.repository.TagRepository
import org.example.repository.TipoEquipamentoRepository
import org.example.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Path

/** Um passo da implantação: feito quando o sistema já tem o que ele pede. */
data class PassoImplantacao(val titulo: String, val dica: String, val feito: Boolean, val link: String, val botao: String)

data class PrimeirosPassos(val passos: List<PassoImplantacao>) {
    val feitos: Int get() = passos.count { it.feito }
    val total: Int get() = passos.size
    val completo: Boolean get() = feitos == total
}

/**
 * Primeiros passos (1.30.0): o que falta para começar a usar o sistema, conferido sozinho a partir do banco. Aparece no
 * painel inicial do administrador até tudo estar feito, ou até ele ocultar o quadro (Administração · Personalizar).
 */
@Service
class PrimeirosPassosService(
    private val tipos: TipoEquipamentoRepository,
    private val locais: LocalRepository,
    private val usuarios: UserRepository,
    private val coletores: ColetorRepository,
    private val tags: TagRepository,
    private val clientes: ClienteRepository,
    private val inventarios: InventarioRepository,
    private val config: ConfiguracaoSistemaService,
    private val backup: BackupService? = null
) {

    @Transactional(readOnly = true)
    fun passos(): PrimeirosPassos = PrimeirosPassos(listOf(
        PassoImplantacao("Catálogo", "Cadastre os tipos de equipamento (e as marcas e modelos)", tipos.count() > 0,
            "/admin/catalogo", "Abrir o catálogo"),
        PassoImplantacao("Locais", "Cadastre onde os equipamentos ficam (depósito, prateleiras, oficina)", locais.count() > 0,
            "/admin/locais", "Abrir os locais"),
        PassoImplantacao("Equipe", "Crie um usuário para cada pessoa: a auditoria mostra quem fez o quê", usuarios.countByAtivoTrue() >= 2,
            "/admin/usuarios", "Abrir os usuários"),
        PassoImplantacao("Coletor", "Ligue pelo menos um coletor ao servidor (QR de configuração)", coletores.count() > 0,
            "/admin/coletores", "Abrir os coletores"),
        PassoImplantacao("Equipamentos", "Leia as etiquetas com o coletor e cadastre as pendentes em lote (ou importe uma planilha)",
            tags.count() > 0, "/admin/tags-pendentes", "Abrir as pendentes"),
        PassoImplantacao("Clientes", "Importe a planilha de clientes ou cadastre os primeiros", clientes.count() > 0,
            "/admin/clientes/importar", "Importar clientes"),
        PassoImplantacao("Primeiro inventário", "Conte um local com o coletor e finalize o inventário",
            inventarios.countByStatusAndTreinoFalse(StatusInventario.FINALIZADO) > 0, "/admin/inventarios", "Abrir os inventários"),
        PassoImplantacao("Backup fora do computador", "Guarde o backup do certificado numa pasta fora deste computador (rfid.backup.pasta, ex.: OneDrive)",
            backupForaDoComputador(), "/status", "Ver o status")
    ))

    /** No painel: só enquanto falta algo e o administrador não ocultou. */
    fun paraOPainel(): PrimeirosPassos? {
        if (config.primeirosPassosOcultos()) return null
        return passos().takeUnless { it.completo }
    }

    private fun backupForaDoComputador(): Boolean {
        val b = backup ?: return false
        return b.variavelAusente == null && b.pasta.normalize() != Path.of("data", "backups")
    }
}
