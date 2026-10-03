package fr.ftnl.apcdeck.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubTest {
    @Test
    fun `adresses de depot reconnues`() {
        val expected = "OcelusPRO/apc_deck"
        listOf(
            "https://github.com/OcelusPRO/apc_deck",
            "https://github.com/OcelusPRO/apc_deck.git",
            "https://github.com/OcelusPRO/apc_deck/",
            "http://www.github.com/OcelusPRO/apc_deck",
            "github.com/OcelusPRO/apc_deck",
            "git@github.com:OcelusPRO/apc_deck.git",
            "OcelusPRO/apc_deck",
            "  OcelusPRO/apc_deck  ",
        ).forEach { assertEquals(expected, GitHub.repoOf(it), it) }
    }

    @Test
    fun `adresses non GitHub refusees`() {
        assertNull(GitHub.repoOf("https://gitlab.com/owner/repo"))
        assertNull(GitHub.repoOf("https://github.com/owner"))
        assertNull(GitHub.repoOf(""))
    }
}
