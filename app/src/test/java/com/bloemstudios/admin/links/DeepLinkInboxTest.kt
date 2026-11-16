package com.bloemstudios.admin.links

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeepLinkInboxTest {
    @Before @After fun reset() = DeepLinkInbox.reset()

    @Test fun anOfferedLinkWaitsUntilSomeoneConsumesIt() {
        val target = DeepLinkTarget("a", "work.requests")
        DeepLinkInbox.offer(target, nowMillis = 100L)
        val pending = DeepLinkInbox.pending.value!!
        assertEquals(target, pending.target)
        DeepLinkInbox.consume(pending)
        assertNull(DeepLinkInbox.pending.value)
    }

    @Test fun consumingAnOldLinkDoesNotDropANewerOne() {
        DeepLinkInbox.offer(DeepLinkTarget("a", null), nowMillis = 1L)
        val old = DeepLinkInbox.pending.value!!
        DeepLinkInbox.offer(DeepLinkTarget("b", null), nowMillis = 2L)
        DeepLinkInbox.consume(old)
        assertEquals("b", DeepLinkInbox.pending.value?.target?.serverId)
    }

    @Test fun aLinkNobodyOpenedForTenMinutesIsStale() {
        DeepLinkInbox.offer(DeepLinkTarget("a", null), nowMillis = 1_000L)
        val request = DeepLinkInbox.pending.value!!
        assertTrue(DeepLinkInbox.isFresh(request, nowMillis = 1_000L + DeepLinkInbox.MAX_AGE_MILLIS))
        assertFalse(DeepLinkInbox.isFresh(request, nowMillis = 1_001L + DeepLinkInbox.MAX_AGE_MILLIS))
    }

    @Test fun anItemWaitsForItsOwnPageAndOpensOnce() {
        DeepLinkItems.reset()
        DeepLinkItems.offer("work.requests", "req-9", nowMillis = 1_000L)
        assertNull("another page does not take it", DeepLinkItems.take("live.streams", nowMillis = 1_001L))
        assertEquals("req-9", DeepLinkItems.take("work.requests", nowMillis = 1_002L))
        assertNull("it opens once", DeepLinkItems.take("work.requests", nowMillis = 1_003L))
    }

    @Test fun anItemNobodyTookInTimeIsDropped() {
        DeepLinkItems.reset()
        DeepLinkItems.offer("work.requests", "req-9", nowMillis = 1_000L)
        assertNull(DeepLinkItems.take("work.requests", nowMillis = 1_001L + DeepLinkInbox.MAX_AGE_MILLIS))
    }
}
