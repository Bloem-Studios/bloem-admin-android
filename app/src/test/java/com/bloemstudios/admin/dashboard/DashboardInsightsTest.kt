package com.bloemstudios.admin.dashboard

import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.users.AdminUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.Locale

class DashboardInsightsTest {
    private val english = Locale.US
    private val text = object : TileText {
        override fun label(id: DashboardTileId) = when (id) {
            DashboardTileId.ActiveStreams -> "Active streams"
            DashboardTileId.EgressNow -> "Egress now"
            DashboardTileId.ProfilesActive -> "Profiles · 24h"
            DashboardTileId.Movies -> "Movies"
            DashboardTileId.Shows -> "Shows"
            DashboardTileId.Storage -> "Storage"
        }
        override fun sessions(count: Int) = "$count sessions"
        override fun files(count: Int) = "$count files"
        override fun profilesDetail() = "watched on this server"
        override fun egressDetail() = "node + server egress"
        override fun storage(bytes: Long) = "$bytes B"
    }
    private val stats = AdminStats(10, 11, 12, 13, 14, 15, 16, 7, 1000, emptyList())
    private val activity = PlaybackActivity(profiles_active_24h = 4)
    private val loaded = SectionState("loaded")

    private fun tiles(sessions: Int? = 2, stats: AdminStats? = this.stats, activity: PlaybackActivity? = this.activity, egress: Double? = 5_000.0) =
        dashboardTiles(sessions, stats, activity, egress, text, english)

    @Test fun `tiles follow the web order`() {
        assertEquals(
            listOf(DashboardTileId.ActiveStreams, DashboardTileId.EgressNow, DashboardTileId.ProfilesActive,
                DashboardTileId.Movies, DashboardTileId.Shows, DashboardTileId.Storage),
            tiles().map { it.id },
        )
    }

    @Test fun `tile values and details come from the web's sources`() {
        val byId = tiles().associateBy { it.id }
        assertEquals("2" to "2 sessions", byId.getValue(DashboardTileId.ActiveStreams).let { it.value to it.detail })
        assertEquals("5.0 Mbps", byId.getValue(DashboardTileId.EgressNow).value)
        assertEquals("4" to "watched on this server", byId.getValue(DashboardTileId.ProfilesActive).let { it.value to it.detail })
        assertEquals("13" to "14 files", byId.getValue(DashboardTileId.Movies).let { it.value to it.detail })
        assertEquals("15" to "16 files", byId.getValue(DashboardTileId.Shows).let { it.value to it.detail })
        assertEquals("1000 B" to "11 files", byId.getValue(DashboardTileId.Storage).let { it.value to it.detail })
    }

    @Test fun `a tile without data is left out`() {
        assertEquals(
            listOf(DashboardTileId.EgressNow, DashboardTileId.ProfilesActive, DashboardTileId.Movies, DashboardTileId.Shows, DashboardTileId.Storage),
            tiles(sessions = null).map { it.id },
        )
        assertEquals(
            listOf(DashboardTileId.ActiveStreams, DashboardTileId.ProfilesActive, DashboardTileId.Movies, DashboardTileId.Shows, DashboardTileId.Storage),
            tiles(egress = null).map { it.id },
        )
        assertEquals(
            listOf(DashboardTileId.ActiveStreams, DashboardTileId.EgressNow, DashboardTileId.Movies, DashboardTileId.Shows, DashboardTileId.Storage),
            tiles(activity = null).map { it.id },
        )
        assertEquals(
            listOf(DashboardTileId.ActiveStreams, DashboardTileId.EgressNow, DashboardTileId.ProfilesActive),
            tiles(stats = null).map { it.id },
        )
        assertEquals(emptyList<DashboardTile>(), tiles(null, null, null, null))
    }

    @Test fun `zero sessions is data and keeps its tile`() {
        assertEquals("0", tiles(sessions = 0).first().value)
    }

    @Test fun `sampled stream counts never stand in for live sessions`() {
        // stats.active_streams is 7, but with no live session list there is no Active streams tile.
        assertEquals(emptyList<DashboardTileId>(), tiles(sessions = null).map { it.id }.filter { it == DashboardTileId.ActiveStreams })
    }

    @Test fun `the one main chart, concurrent streams, leads the two smaller trends`() {
        val everything = DashboardInsights(
            activity = loaded.let { SectionState(activity) }, egress24h = SectionState(Timeseries()), top = SectionState(TopActivity()),
            libraries = SectionState(emptyList()), users = SectionState(emptyList()), recent = SectionState(emptyList()),
        )
        assertEquals(
            listOf(DashboardSection.Tiles, DashboardSection.Health, DashboardSection.NowPlaying, DashboardSection.ConcurrentStreams,
                DashboardSection.PlaybackActivity, DashboardSection.Egress, DashboardSection.TopTitles, DashboardSection.TopProfiles,
                DashboardSection.Libraries, DashboardSection.Users, DashboardSection.RecentActivity),
            visibleDashboardSections(hasTiles = true, hasHealth = true, insights = everything),
        )
    }

    @Test fun `a section without data or failure is not shown but now playing always is`() {
        assertEquals(listOf(DashboardSection.NowPlaying), visibleDashboardSections(false, false, DashboardInsights()))
        assertEquals(
            listOf(DashboardSection.Tiles, DashboardSection.NowPlaying, DashboardSection.Libraries),
            visibleDashboardSections(true, false, DashboardInsights(libraries = SectionState(emptyList()))),
        )
    }

    @Test fun `a failed section keeps its place so the failure is visible`() {
        val failed = DashboardInsights(top = SectionState(null, ErrorText.Raw("down")), recent = SectionState(null, ErrorText.Raw("down")))
        assertEquals(
            listOf(DashboardSection.NowPlaying, DashboardSection.TopTitles, DashboardSection.TopProfiles, DashboardSection.RecentActivity),
            visibleDashboardSections(false, false, failed),
        )
    }

    @Test fun `watch providers are not a default section`() {
        assertEquals(11, DashboardSection.entries.size)
        assertEquals(emptyList<DashboardSection>(), DashboardSection.entries.filter { it.name.contains("Provider") })
    }

    @Test fun `egress is current only while the sample is fresh`() {
        val series = Timeseries(points = listOf(TimeseriesPoint("2026-11-13T10:00:00Z", 1, 1_000.0), TimeseriesPoint("2026-11-13T10:05:00Z", 2, 12_500.0)))
        assertEquals(12_500.0, series.freshEgressKbps(Instant.parse("2026-11-13T10:06:00Z"))!!, 0.0)
        assertNull(series.freshEgressKbps(Instant.parse("2026-11-13T10:07:01Z")))
        assertNull(Timeseries().freshEgressKbps(Instant.parse("2026-11-13T10:06:00Z")))
    }

    @Test fun `egress summary reports peak and average in Mbps`() {
        val series = Timeseries(points = listOf(TimeseriesPoint("a", 0, 2_000.0), TimeseriesPoint("b", 0, 6_000.0)))
        assertEquals(6.0, series.peakMbps!!, 0.0)
        assertEquals(4.0, series.averageMbps!!, 0.0)
        assertNull(Timeseries().peakMbps)
    }

    @Test fun `playback activity totals the buckets by method`() {
        val a = PlaybackActivity(buckets = listOf(PlaybackBucket("h1", 3, 1, 0), PlaybackBucket("h2", 2, 0, 4)), profiles_active_24h = 1)
        assertEquals(5, a.directStarts); assertEquals(1, a.remuxStarts); assertEquals(4, a.transcodeStarts); assertEquals(10, a.totalStarts)
    }

    @Test fun `small egress rates keep a decimal`() {
        assertEquals("0.5 Mbps", formatMbps(0.5, english))
        assertEquals("0 Mbps", formatMbps(0.0, english))
        assertEquals("120 Mbps", formatMbps(120.4, english))
        assertEquals("—", formatMbps(Double.NaN, english))
    }

    @Test fun `watch time stays in minutes below an hour`() {
        assertEquals("0m", formatWatchTime(0.0, english))
        assertEquals("1m", formatWatchTime(20.0, english))
        assertEquals("45m", formatWatchTime(2_700.0, english))
        assertEquals("1.5h", formatWatchTime(5_400.0, english))
    }

    @Test fun `uptime uses the coarsest useful unit`() {
        val now = Instant.parse("2026-11-13T12:00:00Z")
        assertEquals("45s", formatUptime("2026-11-13T11:59:15Z", now))
        assertEquals("5m 30s", formatUptime("2026-11-13T11:54:30Z", now))
        assertEquals("3h", formatUptime("2026-11-13T09:00:00Z", now))
        assertEquals("3d 4h", formatUptime("2026-11-10T08:00:00Z", now))
        assertEquals("2d", formatUptime("2026-11-11T12:00:00Z", now))
        assertEquals("—", formatUptime(null, now))
        assertEquals("—", formatUptime("not a time", now))
    }

    @Test fun `latency keeps decimals only for fast round trips`() {
        assertEquals("0.42 ms", formatLatency(0.42, english))
        assertEquals("23 ms", formatLatency(23.4, english))
    }

    private fun user(name: String, lastActive: String?) = AdminUser("id-$name", name, "$name@example.test", "user", true, last_active_at = lastActive)

    @Test fun `users are listed by last activity with the never-active last`() {
        val sorted = usersByLastActive(listOf(
            user("zed", null), user("amy", "2026-11-01T00:00:00Z"), user("bob", "2026-11-12T00:00:00Z"), user("abe", null),
        ))
        assertEquals(listOf("bob", "amy", "abe", "zed"), sorted.map { it.username })
    }

    @Test fun `top profile label adds the account only when it says something`() {
        assertEquals("Kids · maria", topProfileLabel(TopProfile("1", "maria", "p1", "Kids")))
        assertEquals("maria", topProfileLabel(TopProfile("1", "maria", "p1", "maria")))
        assertEquals("p1 · maria", topProfileLabel(TopProfile("1", "maria", "p1", "")))
        assertEquals("User 1", topProfileLabel(TopProfile("1", "", "", "")))
    }
}
