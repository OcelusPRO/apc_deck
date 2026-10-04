package fr.ftnl.apcdeck.desktop

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleasesTest {
    private fun ref(address: String) = RepoRef.parse(address)

    @Test
    fun `adresses GitHub`() {
        val expected = RepoRef("github.com", "OcelusPRO/apc_deck")
        listOf(
            "https://github.com/OcelusPRO/apc_deck",
            "https://github.com/OcelusPRO/apc_deck.git",
            "https://github.com/OcelusPRO/apc_deck/",
            "https://github.com/OcelusPRO/apc_deck/releases/tag/v1.0.1",
            "http://www.github.com/OcelusPRO/apc_deck",
            "github.com/OcelusPRO/apc_deck",
            "git@github.com:OcelusPRO/apc_deck.git",
            "OcelusPRO/apc_deck",
            "  OcelusPRO/apc_deck  ",
        ).forEach { assertEquals(expected.copy(scheme = if (it.trim().startsWith("http://")) "http" else "https"), ref(it), it) }
    }

    @Test
    fun `adresses GitLab, sous-groupes et auto-heberge`() {
        assertEquals(RepoRef("gitlab.com", "groupe/sous-groupe/projet"), ref("https://gitlab.com/groupe/sous-groupe/projet"))
        assertEquals(RepoRef("gitlab.com", "groupe/projet"), ref("https://gitlab.com/groupe/projet/-/releases"))
        assertEquals(RepoRef("gitlab.com", "groupe/projet"), ref("git@gitlab.com:groupe/projet.git"))
        assertEquals(RepoRef("git.exemple.fr", "equipe/plugin"), ref("https://git.exemple.fr/equipe/plugin.git"))
        assertEquals(RepoRef("git.exemple.fr", "equipe/plugin", "http"), ref("http://git.exemple.fr/equipe/plugin"))
        assertEquals(RepoRef("git.exemple.fr", "equipe/plugin"), ref("ssh://git@git.exemple.fr:2222/equipe/plugin.git"))
    }

    @Test
    fun `adresses Codeberg et non reconnues`() {
        assertEquals(RepoRef("codeberg.org", "owner/repo"), ref("https://codeberg.org/owner/repo/releases"))
        assertNull(ref("https://github.com/owner"))
        assertNull(ref("pas une adresse"))
        assertNull(ref(""))
    }

    @Test
    fun `release GitHub ou Gitea`() {
        val release = ReleaseClient.parseGitHub(Json.parseToJsonElement("""
            {"tag_name":"v1.2.0","html_url":"https://codeberg.org/o/r/releases/tag/v1.2.0","body":"notes",
             "assets":[{"name":"paint.jar","browser_download_url":"https://codeberg.org/o/r/releases/download/v1.2.0/paint.jar"}]}
        """))
        assertEquals("1.2.0", release.version)
        assertEquals("https://codeberg.org/o/r/releases/download/v1.2.0/paint.jar", release.pluginJar("paint")?.url)
        assertNull(release.pluginJar("autre"))
    }

    @Test
    fun `release GitLab, jar reconnu par son URL`() {
        val gitlab = RepoRef("gitlab.com", "g/p")
        val release = ReleaseClient.parseGitLab(Json.parseToJsonElement("""
            [{"tag_name":"2.0.0","description":"notes","_links":{"self":"https://gitlab.com/g/p/-/releases/2.0.0"},
              "assets":{"links":[{"name":"Plugin Paint","url":"https://x/raw","direct_asset_url":"https://gitlab.com/g/p/-/releases/2.0.0/downloads/paint.jar"}]}}]
        """), gitlab)
        assertEquals("2.0.0", release?.version)
        assertEquals("https://gitlab.com/g/p/-/releases/2.0.0", release?.pageUrl)
        assertEquals("https://gitlab.com/g/p/-/releases/2.0.0/downloads/paint.jar", release?.pluginJar("paint")?.url)
        assertNull(ReleaseClient.parseGitLab(Json.parseToJsonElement("[]"), gitlab)) // aucune release
    }
}
