package com.bloemstudios.admin.network

import com.bloemstudios.admin.activity.PlaybackHistoryEntry
import com.bloemstudios.admin.dashboard.AdminStats
import com.bloemstudios.admin.invitations.Invitation
import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.jobs.AdminTask
import com.bloemstudios.admin.streams.AdminSession
import com.bloemstudios.admin.streams.playbackProgressPercent
import com.bloemstudios.admin.streams.streamViewerLabel
import com.bloemstudios.admin.tenants.Tenant
import com.bloemstudios.admin.users.LoginSession
import com.bloemstudios.admin.users.AdminUser
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveContractFixturesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `dashboard stats decode the live per-provider array`() {
        val stats = json.decodeFromString<AdminStats>(
            """{"total_items":1,"total_files":2,"total_users":3,"total_movies":4,"total_movie_files":5,"total_shows":6,"total_show_files":7,"advisory_titles":0,"active_streams":8,"total_storage_bytes":9000000000,"watch_providers":[{"provider":"trakt","display_name":"Trakt","registered":true,"scrobbling":true,"exporting":true,"connected_profiles":11,"enabled_profiles":10,"export_enabled_profiles":9,"scrobble_enabled_profiles":8,"last_sync_completed_at":"2026-08-29T10:00:00Z","sync_runs_24h":7,"sync_errors_24h":1,"imported_watched_24h":6,"imported_progress_24h":5,"exported_watched_24h":4,"pending_exports":3,"failed_exports":2,"open_scrobbles":1,"scrobbles_24h":12}]}"""
        )

        assertEquals("trakt", stats.watch_providers.single().provider)
        assertEquals(12L, stats.watch_providers.single().scrobbles_24h)
    }

    @Test
    fun `login session decodes a null last seen and absent device fields`() {
        val session = json.decodeFromString<LoginSession>(V2Fixtures.loginSession("session-1"))

        assertNull(session.last_seen_at)
        assertNull(session.device_id)
        assertNull(session.device_platform)
    }

    @Test
    fun `admin user decodes the live effective policy shape`() {
        val user = json.decodeFromString<AdminUser>(
            V2Fixtures.adminUser("7", "viewer", policy = V2Fixtures.effectivePolicy(libraryIds = """["3","5"]""", permissions = """["playback"]"""))
        )

        assertEquals("7", user.id)
        assertEquals(6, user.effective_policy?.max_streams)
        assertEquals(listOf("3", "5"), user.effective_policy?.library_ids)
        assertEquals(true, user.effective_policy?.download_allowed)
        assertEquals(listOf("playback"), user.effective_policy?.permissions)
    }

    @Test
    fun `unrestricted effective policy decodes an explicit null library list`() {
        val user = json.decodeFromString<AdminUser>(V2Fixtures.adminUser("7", "viewer"))

        assertNull(user.effective_policy?.library_ids)
    }

    @Test
    fun `unknown playback durations decode as null`() {
        val history = json.decodeFromString<PlaybackHistoryEntry>(
            com.bloemstudios.admin.auth.V2Fixtures.adminPlaybackHistoryEntry("history-1", "Unknown")
                .replace("\"duration_seconds\":2692.0", "\"duration_seconds\":null")
        )
        val stream = json.decodeFromString<AdminSession>(
            com.bloemstudios.admin.auth.V2Fixtures.adminPlaybackSession(
                "stream-1", "admin", "Unknown", "movie", "direct_play", position = "12.0", duration = "null")
        )

        assertNull(history.duration_seconds)
        assertNull(stream.file_duration)
        assertNull(stream.client_name)
        assertEquals("admin", streamViewerLabel(stream))
        assertNull(playbackProgressPercent(stream))
    }

    @Test
    fun `invitation identifiers stay strings and entitlement revisions decode beyond 32 bit`() {
        val invitation = json.decodeFromString<Invitation>(
            V2Fixtures.adminInvitation(
                id = "5000000001",
                status = "accepted",
                optional = """"access_group_id":"5000000002","accepted_user_id":"5000000004""""
            ).replace(""""invited_by":"1"""", """"invited_by":"5000000003"""")
        )
        val tenant = json.decodeFromString<Tenant>(
            """{"tenant_id":"tenant-1","name":"Example","applied_entitlement_revision":5000000005}"""
        )

        assertEquals("5000000001", invitation.id)
        assertEquals("5000000002", invitation.access_group_id)
        assertEquals("5000000003", invitation.invited_by)
        assertEquals("5000000004", invitation.accepted_user_id)
        assertEquals(5_000_000_005L, tenant.applied_entitlement_revision)
    }

    @Test
    fun `task triggers preserve every live field through a round trip`() {
        val detail = json.decodeFromString<AdminTask>(
            V2Fixtures.adminTask("cleanup", category = "system", triggers = """[{"type":"weekly","interval_ms":86400000,"time_of_day":"03:30","day_of_week":2,"max_runtime_ms":7200000}]""")
        )

        val trigger = detail.triggers.single()
        assertEquals("03:30", trigger.time_of_day)
        assertEquals(2, trigger.day_of_week)
        assertEquals(7_200_000L, trigger.max_runtime_ms)
        assertEquals(
            """{"type":"weekly","interval_ms":86400000,"time_of_day":"03:30","day_of_week":2,"max_runtime_ms":7200000}""",
            json.encodeToString(trigger)
        )
    }
}
