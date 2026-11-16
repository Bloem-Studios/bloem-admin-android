package com.bloemstudios.admin.widget

import com.bloemstudios.admin.alerts.CountsStatus
import com.bloemstudios.admin.alerts.ServerRecord
import com.bloemstudios.admin.alerts.ServerStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TileModelTest {
    private val now = 10_000_000L

    private fun known(streams: Int, at: Long = now, reachable: Boolean = true) = ServerRecord(
        ServerStatus(reachable = reachable, checkedAt = at, counts = CountsStatus.Known, streams = streams, requests = 0),
    )

    @Test fun streamsFromEveryServerAddUp() {
        assertEquals(TileModel.Streams(3), TileModels.map(mapOf("a" to known(1), "b" to known(2)), now))
    }

    @Test fun noStreamsIsNothingPlaying() {
        assertEquals(TileModel.Streams(0), TileModels.map(mapOf("a" to known(0)), now))
    }

    @Test fun anUnreachableServerDoesNotCount() {
        assertEquals(TileModel.Streams(1), TileModels.map(mapOf("a" to known(1), "b" to known(7, reachable = false)), now))
    }

    @Test fun noCheckYetIsNoData() {
        assertEquals(TileModel.NoData, TileModels.map(emptyMap(), now))
    }

    @Test fun noSessionAnywhereIsNoData() {
        val signIn = ServerRecord(ServerStatus(reachable = true, checkedAt = now, counts = CountsStatus.SignInNeeded))
        assertEquals(TileModel.NoData, TileModels.map(mapOf("a" to signIn), now))
    }

    @Test fun aCheckOlderThanThreeRunsIsTooOldToShow() {
        val old = now - TileModels.STALE_AFTER_MILLIS - 1
        assertEquals(TileModel.NoData, TileModels.map(mapOf("a" to known(4, at = old)), now))
        assertEquals(TileModel.Streams(4), TileModels.map(mapOf("a" to known(4, at = now - TileModels.STALE_AFTER_MILLIS)), now))
    }

    @Test fun onlyFreshServersAreCounted() {
        val old = now - TileModels.STALE_AFTER_MILLIS - 1
        assertEquals(TileModel.Streams(2), TileModels.map(mapOf("a" to known(2), "b" to known(9, at = old)), now))
    }
}
