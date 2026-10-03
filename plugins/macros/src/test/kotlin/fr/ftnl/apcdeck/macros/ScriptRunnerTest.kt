package fr.ftnl.apcdeck.macros

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScriptRunnerTest {
    private val dir = Files.createTempDirectory("macros-test")

    private fun run(shell: Shell, script: String): Pair<ScriptRunner, Pair<List<String>, CompletableFuture<Int>>> {
        val file = dir.resolve("s.${shell.extension}")
        if (shell == Shell.POWERSHELL) ScriptRunner.writeWithBom(file, script) else Files.writeString(file, script)
        val lines = CopyOnWriteArrayList<String>()
        val exit = CompletableFuture<Int>()
        val runner = ScriptRunner(file, shell, terminal = false, runnerDir = dir, onLine = { lines += it }, onExit = { exit.complete(it) })
        runner.start()
        return runner to (lines to exit)
    }

    @Test
    fun `powershell - sortie UTF-8 et code de retour`() {
        if (!Shell.WINDOWS) return
        val (_, result) = run(Shell.POWERSHELL, "Write-Output 'bonjour é à'\r\nexit 3\r\n")
        val (lines, exit) = result
        assertEquals(3, exit.get(30, TimeUnit.SECONDS))
        Thread.sleep(200) // laisse le thread de lecture finir
        assertContains(lines, "bonjour é à")
    }

    @Test
    fun `cmd - sortie et code de retour`() {
        if (!Shell.WINDOWS) return
        val (_, result) = run(Shell.CMD, "@echo off\r\necho salut\r\nexit /b 0\r\n")
        val (lines, exit) = result
        assertEquals(0, exit.get(30, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertContains(lines, "salut")
    }

    @Test
    fun `stop tue un script long`() {
        if (!Shell.WINDOWS) return
        val (runner, result) = run(Shell.POWERSHELL, "Start-Sleep -Seconds 60\r\n")
        Thread.sleep(1500)
        val started = System.nanoTime()
        runner.stop()
        result.second.get(10, TimeUnit.SECONDS)
        assertTrue((System.nanoTime() - started) < TimeUnit.SECONDS.toNanos(10))
    }
}
