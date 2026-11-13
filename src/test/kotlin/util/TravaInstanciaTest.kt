package org.example.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** 1.33.0: uma cópia só do servidor por pasta. */
class TravaInstanciaTest {

    @TempDir lateinit var pasta: Path

    @AfterEach
    fun soltar() = TravaInstancia.soltar()

    @Test
    fun `a primeira copia trava e a segunda do mesmo processo desiste sabendo quem esta rodando`() {
        val arquivo = pasta.resolve("sub").resolve("servidor.lock")
        assertThat(TravaInstancia.adquirir(arquivo)).isEqualTo(TravaInstancia.Resultado.Adquirida)
        val segunda = TravaInstancia.adquirir(arquivo)
        assertThat(segunda).isInstanceOf(TravaInstancia.Resultado.Ocupada::class.java)
        assertThat((segunda as TravaInstancia.Resultado.Ocupada).quem).contains("PID ${ProcessHandle.current().pid()}")

        // Solta (como o processo acabando): a próxima cópia sobe
        TravaInstancia.soltar()
        assertThat(TravaInstancia.adquirir(arquivo)).isEqualTo(TravaInstancia.Resultado.Adquirida)
    }

    @Test
    fun `outro processo nao sobe enquanto este segura a trava, e sobe depois`() {
        val arquivo = pasta.resolve("servidor.lock")
        assertThat(TravaInstancia.adquirir(arquivo)).isEqualTo(TravaInstancia.Resultado.Adquirida)
        assertThat(outroProcesso(arquivo)).isEqualTo(OutroProcessoTrava.OCUPADA)
        TravaInstancia.soltar()
        assertThat(outroProcesso(arquivo)).isEqualTo(OutroProcessoTrava.ADQUIRIDA)
        // O processo acabou: a trava dele saiu junto, sem ficar presa
        assertThat(TravaInstancia.adquirir(arquivo)).isEqualTo(TravaInstancia.Resultado.Adquirida)
    }

    @Test
    fun `sem como criar o arquivo, sobe sem trava`() {
        val arquivo = Files.createFile(pasta.resolve("um-arquivo")).resolve("servidor.lock")
        assertThat(TravaInstancia.adquirir(arquivo)).isInstanceOf(TravaInstancia.Resultado.SemTrava::class.java)
    }

    private fun outroProcesso(arquivo: Path): Int {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val p = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), OutroProcessoTrava::class.java.name, arquivo.toString())
            .redirectErrorStream(true).start()
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue()
        return p.exitValue()
    }
}

/** Roda num processo separado: tenta a trava e sai com o resultado. */
object OutroProcessoTrava {
    const val ADQUIRIDA = 10
    const val OCUPADA = 11
    const val SEM_TRAVA = 12

    @JvmStatic
    fun main(args: Array<String>) {
        val codigo = when (TravaInstancia.adquirir(Path.of(args[0]))) {
            TravaInstancia.Resultado.Adquirida -> ADQUIRIDA
            is TravaInstancia.Resultado.Ocupada -> OCUPADA
            is TravaInstancia.Resultado.SemTrava -> SEM_TRAVA
        }
        System.exit(codigo)
    }
}
