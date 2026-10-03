package fr.ftnl.apcdeck.core

import kotlin.test.Test
import kotlin.test.assertTrue

class UpdaterTest {
    private fun cmp(a: String, b: String) = Updater.compareVersions(a, b)

    @Test
    fun `les versions se comparent nombre par nombre`() {
        assertTrue(cmp("1.10.0", "1.9.2") > 0)
        assertTrue(cmp("1.0.1", "1.0.0") > 0)
        assertTrue(cmp("2.0", "1.99.99") > 0)
        assertTrue(cmp("1.0", "1.0.0") == 0)
        assertTrue(cmp("1.2.0-beta", "1.2.0") == 0)
        assertTrue(cmp("1.0.0", "1.0.1") < 0)
    }
}
