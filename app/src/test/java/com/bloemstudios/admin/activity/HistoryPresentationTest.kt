package com.bloemstudios.admin.activity

import com.bloemstudios.admin.streams.StreamMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPresentationTest {
    private fun entry(
        title: String = "Who Is Alive?",
        series: String = "Severance",
        season: Int? = 2,
        episode: Int? = 3,
        method: String = "direct",
        played: Double? = 2_400.0,
        watched: Double = 2_652.0,
        duration: Double? = 2_652.0,
        started: String = "2026-11-14T10:00:00Z",
        ended: String? = "2026-11-14T10:44:12Z",
        itemId: String = "item-1",
    ) = PlaybackHistoryEntry(
        session_id = "s1", user_id = "2", username = "maria", media_title = title, media_type = "episode",
        watched_seconds = watched, duration_seconds = duration, completed = true, started_at = started, ended_at = ended,
        profile_id = "p1", profile_name = "Living room", play_method = method, series_title = series,
        season_number = season, episode_number = episode, played_seconds = played, media_item_id = itemId,
    )

    @Test fun `episode code pads season and episode to two digits`() {
        assertEquals("S02E03", episodeCode(2, 3))
        assertEquals("S12E104", episodeCode(12, 104))
    }

    @Test fun `episode code is absent unless both numbers are known`() {
        assertNull(episodeCode(null, 3))
        assertNull(episodeCode(2, null))
        assertNull(episodeCode(null, null))
    }

    @Test fun `an episode leads with its series and puts the code and episode title second`() {
        val title = historyTitle(entry())
        assertEquals("Severance", title.title)
        assertEquals("S02E03 · Who Is Alive?", title.detail)
    }

    @Test fun `a pilot named after its show does not repeat the series title`() {
        val title = historyTitle(entry(title = "Severance", series = "Severance", season = 1, episode = 1))
        assertEquals("Severance", title.title)
        assertEquals("S01E01", title.detail)
    }

    @Test fun `a movie keeps its own title and has no detail line`() {
        val title = historyTitle(entry(title = "Dune: Part Two", series = "", season = null, episode = null))
        assertEquals("Dune: Part Two", title.title)
        assertNull(title.detail)
    }

    @Test fun `a title missing from the catalog falls back to the item id`() {
        val title = historyTitle(entry(title = "", series = "", season = null, episode = null, itemId = "item-9"))
        assertEquals("item-9", title.title)
    }

    @Test fun `measured watch time is the played seconds and is not an estimate`() {
        val watch = watchTime(entry(played = 1_800.0))
        assertEquals(1_800.0, watch.seconds, 0.0)
        assertFalse(watch.estimated)
    }

    @Test fun `an unmeasured play estimates from the end position capped at the elapsed time`() {
        // Ended 44m12s after it started, and the end position was 3,000 s: the estimate cannot exceed the elapsed time.
        val watch = watchTime(entry(played = null, watched = 3_000.0))
        assertEquals(2_652.0, watch.seconds, 0.0)
        assertTrue(watch.estimated)
    }

    @Test fun `an unmeasured play shorter than the elapsed time keeps its end position`() {
        val watch = watchTime(entry(played = null, watched = 600.0))
        assertEquals(600.0, watch.seconds, 0.0)
        assertTrue(watch.estimated)
    }

    @Test fun `play methods map to the stream palette`() {
        assertEquals(StreamMethod.Direct, historyMethod("direct"))
        assertEquals(StreamMethod.Remux, historyMethod("remux"))
        assertEquals(StreamMethod.DirectStream, historyMethod("direct_stream"))
        assertEquals(StreamMethod.DirectStream, historyMethod("audio"))
        assertEquals(StreamMethod.Transcode, historyMethod("transcode"))
        assertEquals(StreamMethod.Unknown, historyMethod("hls"))
        assertEquals(StreamMethod.Unknown, historyMethod(""))
    }

    @Test fun `spans read as hours and minutes, minutes and seconds, or seconds`() {
        assertEquals("1h 12m", formatSpan(4_330.0))
        assertEquals("12m 5s", formatSpan(725.0))
        assertEquals("42s", formatSpan(42.0))
    }

    @Test fun `an unknown or empty span reads as zero minutes`() {
        assertEquals("0m", formatSpan(null))
        assertEquals("0m", formatSpan(0.0))
        assertEquals("0m", formatSpan(Double.NaN))
    }

    @Test fun `the profile line leaves out a profile that repeats the user`() {
        assertEquals("maria · Living room", historyPeopleLine("maria", "Living room"))
        assertEquals("maria", historyPeopleLine("maria", "maria"))
        assertEquals("maria", historyPeopleLine("maria", ""))
    }

    @Test fun `a removed account falls back to its id`() {
        assertEquals("User 7", historyPeopleLine("", "", userId = "7", fallback = { "User $it" }))
    }
}
