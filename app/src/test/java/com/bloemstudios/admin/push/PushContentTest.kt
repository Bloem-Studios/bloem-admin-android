package com.bloemstudios.admin.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushWireTest {
    @Test fun readsTheDeliveryIdAndMode() {
        val wire = PushWire.parse(mapOf("bloem_delivery_id" to "01HZX.0b9e1c4a-7d2f-5a3e-9c11-2f6d8e4a7b90", "bloem_mode" to "private_alert"))!!
        assertEquals("01HZX", wire.deviceId)
        assertEquals("0b9e1c4a-7d2f-5a3e-9c11-2f6d8e4a7b90", wire.deliveryId)
        assertEquals("01HZX.0b9e1c4a-7d2f-5a3e-9c11-2f6d8e4a7b90", wire.contentId)
        assertTrue(wire.showsNotification)
    }

    @Test fun aMissingModeMeansAnAlert() {
        assertTrue(PushWire.parse(mapOf("bloem_delivery_id" to "dev1.d1"))!!.showsNotification)
    }

    @Test fun aBackgroundWakeShowsNothing() {
        assertFalse(PushWire.parse(mapOf("bloem_delivery_id" to "dev1.d1", "bloem_mode" to "background_wake"))!!.showsNotification)
    }

    @Test fun anythingThatIsNotAnAdminPushIsRejected() {
        for (data in listOf(
            emptyMap(),
            mapOf("bloem_delivery_id" to ""),
            mapOf("bloem_delivery_id" to "nodot"),
            mapOf("bloem_delivery_id" to "dev1."),
            mapOf("bloem_delivery_id" to ".d1"),
            mapOf("bloem_delivery_id" to "dev1.d1.extra"),
            mapOf("bloem_delivery_id" to "dev 1.d1"),
            mapOf("bloem_delivery_id" to "dev1/../x.d1"),
            mapOf("bloem_delivery_id" to "dev1.d1?x=1"),
            mapOf("bloem_delivery_id" to "dev1.d1", "bloem_mode" to "weird"),
            mapOf("bloem_delivery_id" to "a".repeat(65) + ".d1"),
            mapOf("title" to "Spoofed", "body" to "Tap here"),
        )) {
            assertNull("accepted $data", PushWire.parse(data))
        }
    }
}

class PushContentSanitizeTest {
    private fun content(
        kind: String = PushKind.REQUEST_WAITING,
        title: String = "sam requested Dune",
        body: String = "3 waiting",
        lock: String = "New request waiting",
        channel: String = "requests",
        page: String? = "requests",
        item: String? = "req-1",
        actions: List<String> = listOf("approve", "decline"),
        badge: Int? = 3,
    ) = PushContent("d1", kind, channel, title, body, lock, page, item, "request", actions, badge)

    @Test fun aRequestNoticeKeepsItsTextItsItemAndItsTwoButtons() {
        val clean = content().sanitized()!!
        assertEquals("sam requested Dune", clean.title)
        assertEquals("3 waiting", clean.body)
        assertEquals("New request waiting", clean.lockScreen)
        assertEquals(PushChannels.REQUESTS, clean.channel)
        assertEquals("requests", clean.page)
        assertEquals("req-1", clean.itemId)
        assertEquals(listOf("approve", "decline"), clean.actions)
        assertEquals(3, clean.badge)
    }

    @Test fun anUnknownKindIsDroppedRatherThanGuessedAt() {
        assertNull(content(kind = "admin.something_new").sanitized())
        assertNull(content(kind = "").sanitized())
    }

    @Test fun aNoticeWithNoTitleIsNotWorthShowing() {
        assertNull(content(title = "   \n ").sanitized())
    }

    @Test fun textIsOneShortLineWithNoControlCharacters() {
        val clean = content(title = "a\nb\u0000c\td", body = "x".repeat(400)).sanitized()!!
        assertEquals("a b c d".replace("b c", "bc"), clean.title)
        assertEquals(140, clean.body.length)
        assertTrue(clean.body.endsWith("…"))
    }

    @Test fun buttonsAppearOnlyOnARequestNoticeThatNamesItsRequest() {
        assertTrue(content(kind = PushKind.BACKUP_FAILED, page = "backups", item = "backups", actions = listOf("approve", "decline")).sanitized()!!.actions.isEmpty())
        assertTrue(content(item = null).sanitized()!!.actions.isEmpty())
        // Nothing but the two non-destructive actions is ever kept, whatever the server lists.
        assertEquals(listOf("approve"), content(actions = listOf("approve", "delete", "restart", "approve")).sanitized()!!.actions)
    }

    @Test fun aLinkPageTheAppDoesNotHaveIsDropped_andTakesItsItemWithIt() {
        val clean = content(page = "secrets", item = "x").sanitized()!!
        assertNull(clean.page)
        assertNull(clean.itemId)
    }

    @Test fun aNodeNoticeOpensTheNodesPageAndAPluginNoticeThePluginsPage() {
        assertEquals("nodes", content(kind = PushKind.NODE_OFFLINE, channel = "nodes", page = "nodes", item = "4", actions = emptyList()).sanitized()!!.page)
        assertEquals("plugins", content(kind = PushKind.PLUGIN_FAILED, channel = "plugins", page = "plugins", item = "12", actions = emptyList()).sanitized()!!.page)
    }

    @Test fun anUnknownChannelFallsBackToTheKindsOwn() {
        assertEquals(PushChannels.BACKUPS, content(kind = PushKind.BACKUP_FAILED, channel = "nonsense", page = "backups", item = "backups", actions = emptyList()).sanitized()!!.channel)
    }

    @Test fun anOverlongItemIdIsDroppedSoNoLongLinkIsBuilt() {
        val clean = content(item = "i".repeat(129)).sanitized()!!
        assertNull(clean.itemId)
        assertTrue(clean.actions.isEmpty())
    }

    @Test fun aMissingLockScreenTextGetsAGenericOneNeverTheTitle() {
        val clean = content(lock = "").sanitized()!!
        assertEquals(PushContent.GENERIC_LOCK_SCREEN, clean.lockScreen)
        assertFalse(clean.lockScreen.contains("sam"))
    }

    @Test fun theBadgeIsKeptInTheRangeTheRelayAllows() {
        assertEquals(9999, content(badge = 50_000).sanitized()!!.badge)
        assertEquals(0, content(badge = -4).sanitized()!!.badge)
        assertNull(content(badge = null).sanitized()!!.badge)
    }

    @Test fun everyKindHasAChannelTheAppCreates() {
        for (kind in PushKinds.all) assertNotNull("${kind.id} has no channel", PushChannels.ids.firstOrNull { it == kind.channelId })
        assertEquals(
            listOf("server_reachability", "push_requests", "push_libraries", "push_backups", "push_nodes", "push_plugins"),
            PushChannels.ids,
        )
    }

    @Test fun theLocalKindsAreTheTwoReachabilityOnes() {
        assertEquals(setOf(PushKind.SERVER_DOWN, PushKind.SERVER_UP), PushKinds.all.filter { it.local }.map { it.id }.toSet())
        assertEquals(7, PushKinds.all.size)
    }
}
