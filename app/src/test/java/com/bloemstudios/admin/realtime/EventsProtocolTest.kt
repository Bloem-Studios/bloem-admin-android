package com.bloemstudios.admin.realtime

import org.junit.Assert.*
import org.junit.Test

class EventsProtocolTest {
    @Test fun `hello lists what the connection may subscribe to and a subscription is answered by request id`() {
        assertEquals(EventsFrame.Hello(setOf("sessions")), EventsProtocol.decode(HELLO))
        assertEquals(EventsFrame.Hello(setOf("sessions", "scans", "tasks", "jobs")), EventsProtocol.decode(HELLO_ADMIN))
        assertEquals(EventsFrame.Hello(emptySet()), EventsProtocol.decode(HELLO.replace("[\"sessions\"]", "[]")))
        assertEquals(EventsFrame.Subscribed("live-1", setOf("sessions"), emptyMap()), EventsProtocol.decode(ACK))
    }

    @Test fun `a refusal names the channel and the server's reason without throwing`() {
        val frame = EventsProtocol.decode("""{"type":"subscribed","request_id":"live-2","channels":["tasks"],"rejected":[{"channel":"sessions","code":"forbidden","message":"private"},{"channel":"x","code":"unknown_channel","message":"private"}]}""")
        assertEquals(EventsFrame.Subscribed("live-2", setOf("tasks"), mapOf("sessions" to "forbidden", "x" to "unknown_channel")), frame)
    }

    @Test fun `the subscribe frame lists the channels in the order given`() {
        assertEquals("""{"type":"subscribe","request_id":"live-3","channels":["scans","tasks"]}""", EventsProtocol.subscribe("live-3", listOf("scans", "tasks")))
        assertEquals("""{"type":"subscribe","request_id":"live-4","channels":[]}""", EventsProtocol.subscribe("live-4", emptyList()))
    }

    @Test fun `a snapshot is only a cue and its rows are never decoded`() {
        assertEquals(EventsFrame.Snapshot("sessions"), EventsProtocol.decode(snapshot(ROW)))
        // Rows are not interpreted at all, so a shape the app model could not decode still cues a re-read.
        assertEquals(EventsFrame.Snapshot("sessions"), EventsProtocol.decode(snapshot("""{"session_id":7,"position_seconds":"later"}""")))
        assertEquals(EventsFrame.Snapshot("sessions"), EventsProtocol.decode(snapshot()))
    }

    @Test fun `other channels' snapshots may be a list, an object or null`() {
        for (data in listOf("[]", "{}", "null", """[{"id":1}]""")) {
            assertEquals(EventsFrame.Snapshot("scans"), EventsProtocol.decode("""{"type":"snapshot","channel":"scans","timestamp":"x","data":$data}"""))
        }
        assertEquals(EventsFrame.Snapshot("tasks"), EventsProtocol.decode("""{"type":"snapshot","channel":"tasks","data":[]}"""))
    }

    @Test fun `sessions replaced and any event on another channel are cues`() {
        assertEquals(EventsFrame.Event("sessions"), EventsProtocol.decode(REPLACEMENT))
        assertEquals(EventsFrame.Event("sessions"), EventsProtocol.decode(REPLACEMENT.replace("\"data\":[]", "\"data\":[$ROW]")))
        assertEquals(EventsFrame.Event("tasks"), EventsProtocol.decode("""{"type":"event","channel":"tasks","event":"task.updated","event_id":"e2","timestamp":"2026-10-12T00:00:01Z","data":{"key":"scan"}}"""))
        assertEquals(EventsFrame.Event("scans"), EventsProtocol.decode("""{"type":"event","channel":"scans","event":"scan.progress","data":null}"""))
    }

    @Test fun `access loss is its own frame and an error frame does not end the other channels`() {
        assertEquals(EventsFrame.AccessChanged, EventsProtocol.decode("""{"type":"access_changed"}"""))
        assertEquals(EventsFrame.Ignored, EventsProtocol.decode("""{"type":"error","code":"internal_error","message":"sensitive detail"}"""))
    }

    @Test fun `unknown frame types and sessions events are ignored`() {
        assertEquals(EventsFrame.Ignored, EventsProtocol.decode("""{"type":"future"}"""))
        assertEquals(EventsFrame.Ignored, EventsProtocol.decode("""{"type":"event","channel":"sessions","event":"future","data":{}}"""))
    }

    @Test fun `schema version must be a JSON number rather than a numeric string`() {
        rejected(HELLO.replace("schema_version\":1", "schema_version\":\"1\""), SessionFailure.Protocol)
    }

    @Test fun `required frames reject malformed data schema action and ack`() {
        listOf("not json", "[]", "{}", """{"type":1}""",
            HELLO.replace("schema_version\":1", "schema_version\":2"),
            HELLO.replace("\"subscribe\"", "\"none\""),
            HELLO.replace("\"subscribe\"", "\"unexpected\""),
            """{"type":"subscribed","channels":[]}""",
            snapshot().replace("[]", "null"), snapshot().replace("[]", "{}"),
            snapshot().replace("2026-10-12T00:00:00Z", "yesterday"),
            REPLACEMENT.replace("\"event_id\":\"e1\",", ""),
            """{"type":"snapshot","channel":"scans"}""",
            """{"type":"event","channel":"tasks"}""",
        ).forEach { rejected(it, SessionFailure.Protocol) }
    }

    private fun rejected(json: String, expected: SessionFailure) {
        try { EventsProtocol.decode(json); fail("Expected sanitized rejection") }
        catch (error: SessionTransportException) {
            assertEquals(expected, error.failure)
            assertNull(error.cause)
            assertFalse(error.message.orEmpty().contains("private"))
        }
    }

    companion object {
        const val HELLO = """{"type":"hello","schema_version":1,"connection_id":"fixture","available_channels":["sessions"],"required_action":"subscribe"}"""
        const val HELLO_ADMIN = """{"type":"hello","schema_version":1,"connection_id":"fixture","available_channels":["sessions","scans","tasks","jobs"],"required_action":"subscribe"}"""
        const val ACK = """{"type":"subscribed","request_id":"live-1","channels":["sessions"]}"""
        const val ROW = """{"session_id":"session-one","username":"viewer","media_title":"Film","media_type":"movie","play_method":"direct","position_seconds":12.5,"file_duration":null,"is_paused":false,"user_id":42,"stream_bitrate_kbps":null}"""
        const val REPLACEMENT = """{"type":"event","channel":"sessions","event":"sessions.replaced","event_id":"e1","timestamp":"2026-10-12T00:00:01Z","data":[]}"""
        fun snapshot(row: String = "", channel: String = "sessions") = """{"type":"snapshot","channel":"$channel","timestamp":"2026-10-12T00:00:00Z","data":[$row]}"""
    }
}
