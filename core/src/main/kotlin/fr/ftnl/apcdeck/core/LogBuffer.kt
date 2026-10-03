package fr.ftnl.apcdeck.core

import fr.ftnl.apcdeck.api.PluginLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogLine(val time: LocalTime, val level: LogLevel, val source: String, val message: String) {
    override fun toString(): String = "${time.format(FORMAT)} ${level.name.padEnd(5)} [$source] $message"

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

/** Journal partagé : console + derniers messages exposés à l'interface. */
class LogBuffer(private val capacity: Int = 500) {
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines

    /** Appelés à chaque nouvelle ligne (depuis n'importe quel thread). */
    val listeners = CopyOnWriteArrayList<(LogLine) -> Unit>()

    fun add(level: LogLevel, source: String, message: String, throwable: Throwable? = null) {
        val text = if (throwable == null) message else "$message : ${throwable::class.simpleName}: ${throwable.message}"
        val line = LogLine(LocalTime.now(), level, source, text)
        println(line)
        throwable?.printStackTrace()
        synchronized(this) { _lines.value = (_lines.value + line).takeLast(capacity) }
        listeners.forEach { runCatching { it(line) } }
    }

    fun logger(source: String): PluginLogger = object : PluginLogger {
        override fun debug(message: String) = add(LogLevel.DEBUG, source, message)
        override fun info(message: String) = add(LogLevel.INFO, source, message)
        override fun warn(message: String) = add(LogLevel.WARN, source, message)
        override fun error(message: String, throwable: Throwable?) = add(LogLevel.ERROR, source, message, throwable)
    }
}
