package com.khcompany.lanedash.related

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelatedAppsTest {
    private val sample = """
        {
          "groups": [
            { "id": "racing", "apps": ["lanedash", "yorijorirun", "nowhere"] },
            { "id": "other", "apps": ["store", "yorijorirun", "lanedash"] },
            { "id": "unrelated", "apps": ["x"] }
          ],
          "apps": {
            "lanedash": { "name": "Lane Dash", "tagline": "a", "package": "com.khcompany.lanedash", "public": true, "web": "https://w/l", "icon": null },
            "yorijorirun": { "name": "요리조리 런", "tagline": "b", "package": "com.khcompany.yorijorirun", "public": false, "web": "https://w/y", "icon": "https://i/y.png" },
            "nowhere": { "name": "N", "tagline": "c", "package": null, "public": false, "web": null, "icon": null },
            "store": { "name": "S", "tagline": "d", "package": "com.khcompany.store", "public": true, "web": null, "icon": null },
            "x": { "name": "X", "tagline": "e", "package": "com.x", "public": true, "web": null, "icon": null }
          }
        }
    """.trimIndent()

    @Test
    fun siblingsComeFromSharedGroupsWithoutSelfRepeatsOrDeadEnds() {
        val siblings = RelatedApps.siblings(RelatedApps.parse(sample), "lanedash")
        assertEquals(listOf("yorijorirun", "store"), siblings.map { it.id })
    }

    @Test
    fun linksFollowPublicThenWeb() {
        val apps = RelatedApps.parse(sample).apps
        assertEquals(RelatedLink.Web("https://w/y"), RelatedApps.linkFor(apps.getValue("yorijorirun")))
        assertEquals(RelatedLink.Store("com.khcompany.store"), RelatedApps.linkFor(apps.getValue("store")))
        assertNull(RelatedApps.linkFor(apps.getValue("nowhere")))
        assertNull(apps.getValue("lanedash").iconUrl)
    }

    @Test
    fun refreshesAtMostOnceADay() {
        val day = RelatedApps.REFRESH_INTERVAL_MS
        assertTrue(RelatedApps.shouldRefresh(0L, 1000L))
        assertFalse(RelatedApps.shouldRefresh(1000L, 1000L + day - 1))
        assertTrue(RelatedApps.shouldRefresh(1000L, 1000L + day))
        assertTrue(RelatedApps.shouldRefresh(5000L, 1000L))
    }

    @Test
    fun bundledCopyPointsLaneDashAtYorijoriRun() {
        val bundled = File("src/main/assets/related-apps.json").readText()
        val siblings = RelatedApps.siblings(RelatedApps.parse(bundled))
        assertEquals("yorijorirun", siblings.first().id)
        assertTrue(siblings.first().packageName in manifestQueries())
    }

    /** Every sibling package must be listed in <queries>, or Android 11+ can't tell it's installed. */
    @Test
    fun manifestQueriesCoverBundledSiblings() {
        val bundled = File("src/main/assets/related-apps.json").readText()
        val packages = RelatedApps.siblings(RelatedApps.parse(bundled)).mapNotNull { it.packageName }
        assertTrue(manifestQueries().containsAll(packages))
    }

    private fun manifestQueries(): Set<String> {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val queries = manifest.substringAfter("<queries>", "").substringBefore("</queries>")
        return Regex("""android:name="([^"]+)"""").findAll(queries).map { it.groupValues[1] }.toSet()
    }
}
