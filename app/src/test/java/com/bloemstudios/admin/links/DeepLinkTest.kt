package com.bloemstudios.admin.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkTest {
    @Test fun aServerAndAPageOpenThatPage() {
        assertEquals(
            DeepLinkTarget(serverId = "srv-1", destinationId = "work.requests"),
            DeepLink.parse("bloemadmin://server/srv-1/requests"),
        )
        assertEquals(
            DeepLinkTarget("srv-1", "live.streams"),
            DeepLink.parse("bloemadmin://server/srv-1/now-playing"),
        )
        assertEquals(
            DeepLinkTarget("srv-1", "overview.dashboard"),
            DeepLink.parse("bloemadmin://server/srv-1/dashboard"),
        )
    }

    @Test fun aServerWithNoPageOpensTheStartPage() {
        assertEquals(DeepLinkTarget("srv-1", null), DeepLink.parse("bloemadmin://server/srv-1"))
        assertEquals(DeepLinkTarget("srv-1", null), DeepLink.parse("bloemadmin://server/srv-1/"))
    }

    @Test fun anUnknownPageFallsBackToTheStartPage() {
        assertEquals(DeepLinkTarget("srv-1", null), DeepLink.parse("bloemadmin://server/srv-1/holograms"))
    }

    @Test fun theWidgetsPagesAreKnown() {
        assertEquals(DeepLinkTarget("srv-1", "content.libraries"), DeepLink.parse("bloemadmin://server/srv-1/libraries"))
        assertEquals(DeepLinkTarget("srv-1", "playback.nodes"), DeepLink.parse("bloemadmin://server/srv-1/nodes"))
        assertEquals(DeepLinkTarget("srv-1", "system.backups"), DeepLink.parse("bloemadmin://server/srv-1/backups"))
        assertEquals(DeepLinkTarget(null, "playback.nodes"), DeepLink.parse("bloemadmin://all/nodes"))
    }

    @Test fun aLinkCanNameOneItemOnItsPage() {
        assertEquals(DeepLinkTarget("srv-1", "work.requests", "req-9"), DeepLink.parse("bloemadmin://server/srv-1/requests/req-9"))
        assertEquals(DeepLinkTarget("srv-1", "live.streams", "sess 1"), DeepLink.parse("bloemadmin://server/srv-1/now-playing/sess%201"))
        assertEquals(DeepLinkTarget(null, "work.requests", "req-9"), DeepLink.parse("bloemadmin://all/requests/req-9"))
    }

    @Test fun itemLinksRoundTripThroughBuildAndParse() {
        val link = DeepLink.build("srv 1", "requests", "id/with space+plus")
        assertEquals(DeepLinkTarget("srv 1", "work.requests", "id/with space+plus"), DeepLink.parse(link))
        assertEquals(DeepLinkTarget(null, "live.streams", "a b"), DeepLink.parse(DeepLink.buildAll("now-playing", "a b")))
    }

    @Test fun anItemWithoutAKnownPageIsDropped() {
        assertEquals(DeepLinkTarget("srv-1", null, null), DeepLink.parse("bloemadmin://server/srv-1/holograms/req-9"))
        assertEquals(DeepLinkTarget("srv-1", "work.requests", null), DeepLink.parse("bloemadmin://server/srv-1/requests/"))
    }

    @Test fun anOverlongItemIsDroppedAndTheLinkStillOpensThePage() {
        val long = "x".repeat(129)
        assertEquals(DeepLinkTarget("srv-1", "work.requests", null), DeepLink.parse("bloemadmin://server/srv-1/requests/$long"))
    }

    @Test fun anItemCarriesNoActionEvenWithAQuery() {
        assertEquals(
            DeepLinkTarget("srv-1", "work.requests", "req-9"),
            DeepLink.parse("bloemadmin://server/srv-1/requests/req-9?approve=1"),
        )
    }

    @Test fun allServersHasItsOwnForm() {
        assertEquals(DeepLinkTarget(null, "live.streams"), DeepLink.parse("bloemadmin://all/now-playing"))
        assertEquals(DeepLinkTarget(null, null), DeepLink.parse("bloemadmin://all"))
    }

    @Test fun serverIdsAreDecoded() {
        assertEquals(DeepLinkTarget("a b/c", "work.requests"), DeepLink.parse("bloemadmin://server/a%20b%2Fc/requests"))
        assertEquals(DeepLinkTarget("a+b", null), DeepLink.parse("bloemadmin://server/a%2Bb"))
    }

    @Test fun otherSchemesHostsAndEmptyIdsAreRefused() {
        assertNull(DeepLink.parse("https://example.test/server/srv-1/requests"))
        assertNull(DeepLink.parse("org.siloserver.silo://server/srv-1"))
        assertNull(DeepLink.parse("bloemadmin://settings/srv-1"))
        assertNull(DeepLink.parse("bloemadmin://server"))
        assertNull(DeepLink.parse("bloemadmin://server//requests"))
        assertNull(DeepLink.parse(null))
        assertNull(DeepLink.parse(""))
        assertNull(DeepLink.parse("not a uri"))
    }

    @Test fun queryAndFragmentAreIgnoredSoALinkCannotCarryAnAction() {
        assertEquals(
            DeepLinkTarget("srv-1", "work.requests"),
            DeepLink.parse("bloemadmin://server/srv-1/requests?approve=all#now"),
        )
    }

    @Test fun anOverlongLinkIsRefused() {
        assertNull(DeepLink.parse("bloemadmin://server/" + "a".repeat(500)))
    }

    @Test fun builtLinksParseBackToTheSameTarget() {
        val ids = listOf("srv-1", "a b", "x/y", "üñí", "a+b", "100%")
        for (id in ids) for (slug in listOf(null) + DeepLink.pageSlugs) {
            val built = DeepLink.build(id, slug)
            val parsed = DeepLink.parse(built)
            assertEquals(id, parsed?.serverId)
            assertEquals(slug?.let { DeepLink.destinationFor(it) }, parsed?.destinationId)
        }
        assertEquals("bloemadmin://all/now-playing", DeepLink.buildAll("now-playing"))
        assertEquals("bloemadmin://all", DeepLink.buildAll(null))
    }

    @Test fun everyShortcutPageMapsToARealShellPage() {
        assertEquals("live.streams", DeepLink.destinationFor("now-playing"))
        assertEquals("work.requests", DeepLink.destinationFor("requests"))
        assertEquals("overview.dashboard", DeepLink.destinationFor("dashboard"))
        assertNull(DeepLink.destinationFor("nope"))
    }
}
