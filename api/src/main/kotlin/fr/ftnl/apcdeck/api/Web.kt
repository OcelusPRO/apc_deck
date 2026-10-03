package fr.ftnl.apcdeck.api

/**
 * Interface web d'un plugin. Le jar contient un dossier `web/` (au minimum `web/index.html`) : n'importe quel
 * HTML/CSS/JS, y compris le résultat d'un build React/Vue/Tailwind. L'application l'affiche dans son navigateur
 * intégré et le sert sur un serveur local.
 *
 * Côté page, inclure `<script src="/apcdeck.js"></script>` (chemin absolu fourni par l'application) puis :
 * ```
 * const state = await apcdeck.call("state")          // -> ApcPlugin.onWebCall("state", "null")
 * apcdeck.on("drawing", data => render(data))          // <- ctx.web.emit("drawing", json)
 * ```
 */
interface WebBridge {
    /** Vrai si le jar contient `web/index.html`. */
    val available: Boolean

    /** Envoie un événement à toutes les pages ouvertes du plugin. [json] doit être du JSON valide. */
    fun emit(event: String, json: String)
}

/** Encode une chaîne en littéral JSON (guillemets compris). */
fun jsonQuote(text: String): String = buildString(text.length + 2) {
    append('"')
    for (c in text) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}
