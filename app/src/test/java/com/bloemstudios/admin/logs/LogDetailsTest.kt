package com.bloemstudios.admin.logs

import com.bloemstudios.admin.audit.AuditLogEntry
import com.bloemstudios.admin.ui.shareText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogDetailsTest {
    private fun attrs(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun log(
        id: String = "1",
        component: String = "api",
        ts: String = "2026-11-14T10:00:00Z",
        node: String? = null,
        playback: String? = null,
        attrs: JsonObject? = null,
        level: String = "info",
    ) = OperationalLogEntry(id, ts, level, component, "message $id", node_id = node, playback_session_id = playback, attrs = attrs)

    private fun audit(id: String, ts: String, node: String? = null, playback: String? = null) = AuditLogEntry(
        id = id, timestamp = ts, client_ip = "203.0.113.7", method = "GET", path = "/x", status_code = 200, duration_ms = 5,
        node_id = node, playback_session_id = playback,
    )

    @Test fun `a string or number attribute reads as text and anything else as null`() {
        val entry = log(attrs = attrs("""{"method":"GET","status":200,"empty":"","flag":true,"nested":{"a":1}}"""))
        assertEquals("GET", entry.attrText("method"))
        assertEquals("200", entry.attrText("status"))
        assertNull(entry.attrText("empty"))
        assertNull(entry.attrText("flag"))
        assertNull(entry.attrText("nested"))
        assertNull(entry.attrText("missing"))
        assertNull(log(attrs = null).attrText("method"))
    }

    @Test fun `duration comes from duration_ms whether it is a number or a string`() {
        assertEquals("45", log(attrs = attrs("""{"duration_ms":45}""")).durationMs())
        assertEquals("45", log(attrs = attrs("""{"duration_ms":"45"}""")).durationMs())
        assertNull(log(attrs = attrs("""{"other":1}""")).durationMs())
    }

    @Test fun `a fractional duration keeps one decimal`() {
        assertEquals("12.5", log(attrs = attrs("""{"duration_ms":12.5}""")).durationMs())
    }

    @Test fun `the client address and sessions fall back to the attributes as the web does`() {
        val entry = log(attrs = attrs("""{"client_ip":"10.0.0.8","session_id":"sess-1","playback_session_id":"pb-1"}"""))
        assertEquals("10.0.0.8", entry.clientIpOrAttr())
        assertEquals("sess-1", entry.sessionOrAttr())
        assertEquals("pb-1", entry.playbackSessionOrAttr())
        val direct = log().copy(client_ip = "192.0.2.1", session_id = "s", playback_session_id = "p")
        assertEquals("192.0.2.1", direct.clientIpOrAttr())
        assertEquals("s", direct.sessionOrAttr())
        assertEquals("p", direct.playbackSessionOrAttr())
    }

    @Test fun `a client address loses its prefix length`() {
        assertEquals("203.0.113.7", plainAddress("203.0.113.7/32"))
        assertEquals("203.0.113.7", plainAddress("203.0.113.7"))
    }

    @Test fun `the summary counts the matching rows, the ffmpeg ones, the audit rows and the nodes`() {
        val app = listOf(
            log("1", "api", "2026-11-14T10:00:05Z", node = "main", playback = "pb"),
            log("2", "ffmpeg", "2026-11-14T10:00:03Z", node = "gpu-1", playback = "pb"),
            log("3", "ffmpeg", "2026-11-14T10:00:09Z", node = "gpu-1", playback = "pb"),
            log("4", "api", "2026-11-14T09:00:00Z", node = "other", playback = "elsewhere"),
        )
        val audits = listOf(audit("a", "2026-11-14T10:00:01Z", node = "main", playback = "pb"), audit("b", "2026-11-14T08:00:00Z", playback = "x"))
        val summary = summarizePlaybackSession("pb", app, audits)
        assertEquals(3, summary.appCount)
        assertEquals(2, summary.ffmpegCount)
        assertEquals(1, summary.auditCount)
        assertEquals("2026-11-14T10:00:01Z", summary.firstSeen)
        assertEquals("2026-11-14T10:00:09Z", summary.lastSeen)
        assertEquals(listOf("gpu-1", "main"), summary.nodes)
    }

    @Test fun `a session with no rows has no first or last time and no nodes`() {
        val summary = summarizePlaybackSession("pb", emptyList(), emptyList())
        assertEquals(0, summary.appCount)
        assertNull(summary.firstSeen)
        assertNull(summary.lastSeen)
        assertTrue(summary.nodes.isEmpty())
    }

    @Test fun `the log filter counts only the extra filters as active`() {
        assertEquals(0, LogFilter().extraFilterCount)
        assertEquals(0, LogFilter(level = "error", q = "scan").extraFilterCount)
        assertEquals(3, LogFilter(component = "ffmpeg", requestId = "r", playbackSessionId = "p").extraFilterCount)
        assertFalse(LogFilter(component = " ").normalized().component != null)
    }

    @Test fun `the warn and error choice sends both levels`() {
        assertEquals("error,warn", LogLevelChoice.WarnError.wire)
        assertNull(LogLevelChoice.All.wire)
    }

    @Test fun `a filter opened for a playback session can ask for ffmpeg only`() {
        val session = LogFilter(playbackSessionId = "pb")
        assertEquals("ffmpeg", session.copy(component = "ffmpeg").component)
        assertEquals(session, session.copy(component = null))
    }

    @Test fun `the share text lists the labelled fields and skips the empty ones`() {
        val text = shareText("Scan finished", listOf("Level" to "Info", "Node" to null, "Request ID" to "", "Component" to "scanner"))
        assertEquals("Scan finished\nLevel: Info\nComponent: scanner", text)
    }

    @Test fun `a multi line value goes on its own lines`() {
        val text = shareText("Entry", listOf("Attributes" to "{\n  \"a\": 1\n}"))
        assertEquals("Entry\nAttributes:\n{\n  \"a\": 1\n}", text)
    }
}
