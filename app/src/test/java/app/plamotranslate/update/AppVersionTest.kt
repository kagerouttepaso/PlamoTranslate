package app.plamotranslate.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun parsesTagAndVersionName() {
        assertEquals(listOf(0, 4, 0), AppVersion.parse("v0.4.0"))
        assertEquals(listOf(0, 4, 0), AppVersion.parse("0.4.0"))
        assertEquals(listOf(1, 2), AppVersion.parse("v1.2-beta"))
        assertNull(AppVersion.parse("latest"))
    }

    @Test
    fun comparesNumbersNotText() {
        assertTrue(AppVersion.isNewer("v0.4.1", "0.4.0"))
        assertTrue(AppVersion.isNewer("v0.10.0", "0.9.9"))
        assertTrue(AppVersion.isNewer("v1.0", "0.9.9"))
        assertFalse(AppVersion.isNewer("v0.4.0", "0.4.0"))
        assertFalse(AppVersion.isNewer("v0.4", "0.4.0"))
        assertFalse(AppVersion.isNewer("v0.3.9", "0.4.0"))
    }

    @Test
    fun unreadableVersionIsNeverNewer() {
        assertFalse(AppVersion.isNewer("nightly", "0.4.0"))
        assertFalse(AppVersion.isNewer("v0.5.0", ""))
    }

    private fun apk(tag: String) = ReleaseInfo(tag, "", "", "app.apk", "https://example.invalid/$tag.apk", 1, "00")

    private fun entry(tag: String, draft: Boolean = false, hasApk: Boolean = true) =
        ReleaseEntry(tag, draft, if (hasApk) apk(tag) else null)

    @Test
    fun newestPicksTheHighestVersionNotTheListOrder() {
        val entries = listOf(entry("v0.9.0"), entry("v0.13.0"), entry("v0.13.0-beta.2"), entry("v0.10.1"))
        assertEquals("v0.13.0", AppVersion.newest(entries)?.tag)
    }

    @Test
    fun newestSkipsDraftsReleasesWithoutAVerifiableApkAndUnreadableTags() {
        val entries = listOf(
            entry("v0.14.0", draft = true),
            entry("v0.13.1", hasApk = false),
            entry("nightly"),
            entry("v0.13.0-beta.1"),
        )
        assertEquals("v0.13.0-beta.1", AppVersion.newest(entries)?.tag)
    }

    @Test
    fun newestIsNullWhenNothingIsUsable() {
        assertNull(AppVersion.newest(listOf(entry("v0.14.0", draft = true), entry("v0.13.0", hasApk = false))))
        assertNull(AppVersion.newest(emptyList()))
    }

    @Test
    fun betaIsOlderThanTheReleaseOfTheSameNumber() {
        assertTrue(AppVersion.isNewer("v0.11.0-beta.1", "0.10.1"))
        assertTrue(AppVersion.isNewer("v0.11.0-beta.2", "0.11.0-beta.1"))
        assertTrue(AppVersion.isNewer("v0.11.0-beta.10", "0.11.0-beta.2"))
        assertTrue(AppVersion.isNewer("v0.11.0", "0.11.0-beta.3"))
        assertFalse(AppVersion.isNewer("v0.11.0-beta.3", "0.11.0"))
        assertFalse(AppVersion.isNewer("v0.11.0-beta.1", "0.11.0-beta.1"))
        assertTrue(AppVersion.isNewer("v0.11.0-rc.1", "0.11.0-beta.9"))
    }
}
