package fr.ftnl.apcdeck.macros

import fr.ftnl.apcdeck.api.MiniJson
import fr.ftnl.apcdeck.api.bool
import fr.ftnl.apcdeck.api.int
import fr.ftnl.apcdeck.api.obj
import fr.ftnl.apcdeck.api.str
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MiniJsonTest {
    @Test
    fun `aller-retour d'une macro avec script multiligne et caractères spéciaux`() {
        val macro = mapOf(
            "id" to "m1", "name" to "Ouvrir \"Claude\"", "terminal" to true, "color" to 45,
            "script" to "Start-Process \"https://claude.ai\"\r\n# é à ü \\ \t fin",
            "tags" to listOf(1, "deux", null, false),
        )
        val parsed = MiniJson.parse(MiniJson.stringify(macro)).obj()
        assertEquals("Ouvrir \"Claude\"", parsed.str("name"))
        assertEquals(true, parsed.bool("terminal"))
        assertEquals(45, parsed.int("color"))
        assertEquals(macro["script"], parsed.str("script"))
        assertEquals(listOf(1.0, "deux", null, false), parsed["tags"])
    }

    @Test
    fun `json envoyé par le navigateur`() {
        val parsed = MiniJson.parse("""{ "from": {"row":0,"col":1,"x":2,"y":3}, "to":{"row":4,"col":7,"x":0,"y":0}, "u":"é" }""").obj()
        assertEquals(Slot(Page(0, 1), 2, 3), Slot.fromJson(parsed["from"].obj()))
        assertEquals(Slot(Page(4, 7), 0, 0), Slot.fromJson(parsed["to"].obj()))
        assertEquals("é", parsed.str("u"))
        assertEquals(null, MiniJson.parse("null"))
        assertEquals(-12.5, MiniJson.parse(" -12.5 "))
    }

    @Test
    fun `json invalide refusé`() {
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("{\"a\":1} x") }
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("\"non terminé") }
    }

    @Test
    fun `emplacement hors grille refusé`() {
        assertEquals(null, Slot.decode("5.0.0.0"))
        assertEquals(null, Slot.decode("0.0.8.0"))
        assertEquals(Slot(Page(1, 2), 3, 4), Slot.decode(Slot(Page(1, 2), 3, 4).encode()))
    }
}
