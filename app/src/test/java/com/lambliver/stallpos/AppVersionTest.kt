package com.lambliver.stallpos

import com.lambliver.stallpos.data.VersionChecker
import com.lambliver.stallpos.domain.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppVersionTest {
    @Test
    fun parse_acceptsStableTagsOnly() {
        assertEquals("2.1.2", AppVersion.parse("v2.1.2").toString())
        assertEquals("2.1.2", AppVersion.parse("2.1.2").toString())
        assertNull(AppVersion.parse("v2.1.2-rc.1"))
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse(""))
    }

    @Test
    fun isNewer_comparesNumericFields() {
        assertTrue(AppVersion.isNewer("2.1.2", "v2.1.3"))
        assertTrue(AppVersion.isNewer("2.1.2", "v2.2.0"))
        assertFalse(AppVersion.isNewer("2.1.2", "v2.1.2"))
        assertFalse(AppVersion.isNewer("2.1.2", "v2.1.1"))
        assertFalse(AppVersion.isNewer("2.1.2", "v2.1.3-beta"))
    }

    @Test
    fun newerThanCurrent_returnsOnlyNewerStableTag() {
        assertEquals("v2.1.3", VersionChecker.newerThanCurrent("2.1.2", "v2.1.3"))
        assertNull(VersionChecker.newerThanCurrent("2.1.2", "v2.1.2"))
        assertNull(VersionChecker.newerThanCurrent("2.1.2", null))
        assertNull(VersionChecker.newerThanCurrent("2.1.2", "nightly"))
    }

    @Test
    fun latestTagFromReleaseJson_ignoresDraftPrereleaseAndOversize() {
        assertEquals("v2.1.3", VersionChecker.latestTagFromReleaseJson("""{"tag_name":"v2.1.3","draft":false,"prerelease":false}"""))
        assertNull(VersionChecker.latestTagFromReleaseJson("""{"tag_name":"v2.1.3","draft":true}"""))
        assertNull(VersionChecker.latestTagFromReleaseJson("""{"tag_name":"v2.1.3","prerelease":true}"""))
        assertNull(VersionChecker.latestTagFromReleaseJson("""{"html":"<script>alert(1)</script>"}"""))
        assertNull(VersionChecker.latestTagFromReleaseJson("x".repeat(VersionChecker.MAX_BODY_BYTES + 1)))
    }
}
