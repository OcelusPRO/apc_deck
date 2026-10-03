package fr.ftnl.apcdeck.api

/**
 * JSON minimal, sans dépendance, à disposition des plugins (ils ne voient que l'API de l'application).
 * Objets -> Map<String, Any?>, tableaux -> List<Any?>, nombres -> Double, plus String / Boolean / null.
 * Typique dans ApcPlugin.onWebCall : `val json = MiniJson.parse(body).obj()` puis `MiniJson.stringify(mapOf(...))`.
 */
object MiniJson {
    fun parse(text: String): Any? = Parser(text).run { value().also { skipSpaces(); require(pos == text.length) { "JSON : caractères en trop" } } }

    fun stringify(value: Any?): String = buildString { write(value) }

    private fun StringBuilder.write(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> append(jsonQuote(value))
            is Boolean -> append(value)
            is Int, is Long -> append(value)
            is Number -> value.toDouble().let { if (it == Math.floor(it) && !it.isInfinite()) append(it.toLong()) else append(it) }
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) append(',')
                    append(jsonQuote(k.toString())).append(':')
                    write(v)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                value.forEachIndexed { i, v -> if (i > 0) append(','); write(v) }
                append(']')
            }
            else -> append(jsonQuote(value.toString()))
        }
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun skipSpaces() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipSpaces()
            require(pos < s.length) { "JSON : fin inattendue" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("JSON : caractère inattendu '$c'")
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            require(s.startsWith(word, pos)) { "JSON : '$word' attendu" }
            pos += word.length
            return result
        }

        private fun number(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            return s.substring(start, pos).toDouble()
        }

        private fun string(): String = buildString {
            pos++ // "
            while (true) {
                require(pos < s.length) { "JSON : chaîne non terminée" }
                when (val c = s[pos++]) {
                    '"' -> return@buildString
                    '\\' -> when (val e = s[pos++]) {
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        'b' -> append('\b')
                        'f' -> append('\u000C')
                        'u' -> {
                            append(s.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> append(e)
                    }
                    else -> append(c)
                }
            }
        }

        private fun array(): List<Any?> {
            pos++
            val list = mutableListOf<Any?>()
            skipSpaces()
            if (s[pos] == ']') return list.also { pos++ }
            while (true) {
                list += value()
                skipSpaces()
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> error("JSON : ',' ou ']' attendu")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipSpaces()
            if (s[pos] == '}') return map.also { pos++ }
            while (true) {
                skipSpaces()
                val key = string()
                skipSpaces()
                require(s[pos++] == ':') { "JSON : ':' attendu" }
                map[key] = value()
                skipSpaces()
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> error("JSON : ',' ou '}' attendu")
                }
            }
        }
    }
}

/** Accès typés pratiques sur un objet JSON décodé. */
@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
fun Map<String, Any?>.str(key: String): String? = this[key] as? String
fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()
fun Map<String, Any?>.double(key: String): Double? = (this[key] as? Number)?.toDouble()
fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
