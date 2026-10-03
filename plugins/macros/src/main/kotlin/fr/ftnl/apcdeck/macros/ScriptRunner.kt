package fr.ftnl.apcdeck.macros

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText

/** Shells disponibles : extension du fichier de script et façon de le lancer. */
enum class Shell(val id: String, val label: String, val extension: String) {
    POWERSHELL("powershell", "PowerShell", "ps1"),
    CMD("cmd", "Invite de commandes (cmd)", "cmd"),
    BASH("bash", "Bash", "sh");

    companion object {
        val WINDOWS: Boolean = System.getProperty("os.name").lowercase().contains("win")
        val AVAILABLE: List<Shell> = if (WINDOWS) listOf(POWERSHELL, CMD, BASH) else listOf(BASH)
        val DEFAULT: Shell = if (WINDOWS) POWERSHELL else BASH
        fun of(id: String?): Shell = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Lance un script dans un processus séparé et suit sa fin. La sortie (stdout + stderr) est lue en UTF-8,
 * ligne par ligne, sur un thread dédié. [stop] tue tout l'arbre de processus (fenêtre de terminal comprise).
 */
class ScriptRunner(
    private val script: Path,
    private val shell: Shell,
    private val terminal: Boolean,
    private val runnerDir: Path,
    private val onLine: (String) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    private var process: Process? = null

    fun start() {
        val command = command()
        val builder = ProcessBuilder(command)
            .directory(File(System.getProperty("user.home")))
            .redirectErrorStream(true)
        val p = builder.start()
        process = p
        thread(isDaemon = true, name = "macro-output") {
            runCatching { p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine(onLine) }
        }
        p.onExit().thenAccept { onExit(it.exitValue()) }
    }

    fun stop() {
        val p = process ?: return
        p.descendants().forEach { it.destroyForcibly() }
        p.destroyForcibly()
    }

    /** Un petit lanceur force l'UTF-8 et appelle le script de l'utilisateur sans le modifier. */
    private fun command(): List<String> {
        val path = script.absolutePathString()
        return when (shell) {
            Shell.POWERSHELL -> {
                val runner = runnerDir.resolve("run-${script.fileName}")
                writeWithBom(
                    runner,
                    "[Console]::OutputEncoding = [Text.Encoding]::UTF8\r\n& '${path.replace("'", "''")}'\r\nexit \$LASTEXITCODE\r\n",
                )
                val ps = listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass")
                if (terminal) listOf("cmd.exe", "/c", "start", "", "/wait") + ps + listOf("-NoExit", "-File", runner.absolutePathString())
                else ps + listOf("-NonInteractive", "-File", runner.absolutePathString())
            }
            Shell.CMD -> {
                val runner = runnerDir.resolve("run-${script.fileName}")
                runner.writeText("@chcp 65001 >nul\r\n@call \"$path\"\r\n")
                if (terminal) listOf("cmd.exe", "/c", "start", "", "/wait", "cmd.exe", "/k", runner.absolutePathString())
                else listOf("cmd.exe", "/d", "/c", runner.absolutePathString())
            }
            Shell.BASH ->
                if (terminal && Shell.WINDOWS) listOf("cmd.exe", "/c", "start", "", "/wait", "bash", "--login", "-i", path)
                else listOf("bash", path)
        }
    }

    companion object {
        /** PowerShell 5 lit les .ps1 sans BOM en ANSI : on écrit en UTF-8 avec BOM. */
        fun writeWithBom(file: Path, text: String) {
            Files.write(file, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8))
        }
    }
}
