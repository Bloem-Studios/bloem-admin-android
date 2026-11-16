package com.bloemstudios.admin.realtime

import kotlinx.serialization.json.*
import java.time.Instant

/** The channel names of Silo's events socket that this app subscribes to. */
object LiveChannels {
    const val SESSIONS = "sessions"
    const val SCANS = "scans"
    const val TASKS = "tasks"
    const val JOBS = "jobs"
    const val SETTINGS = "settings"
}

sealed interface EventsFrame {
    /** [available] is what this connection may subscribe to; a plain Silo server and a Bloem server both send it. */
    data class Hello(val available: Set<String>) : EventsFrame
    /** The answer to one subscribe frame: what was accepted, and why anything else was not (channel to code). */
    data class Subscribed(val requestId: String, val channels: Set<String>, val rejected: Map<String, String>) : EventsFrame
    // A snapshot's rows keep the server's own shape. They are only a cue to re-read through the REST API,
    // so they are validated for structure and dropped.
    data class Snapshot(val channel: String) : EventsFrame
    /** Something changed on [channel]. The event's payload is never read: the client re-reads. */
    data class Event(val channel: String) : EventsFrame
    data object AccessChanged : EventsFrame
    data object Ignored : EventsFrame
}

internal object EventsProtocol {
    const val PROTOCOL = "silo.events.v2"
    private val json = Json { ignoreUnknownKeys = true }

    /** Channel names are fixed identifiers of this app, never user input, so they need no escaping. */
    fun subscribe(requestId: String, channels: Collection<String>): String =
        """{"type":"subscribe","request_id":"$requestId","channels":[${channels.joinToString(",") { "\"$it\"" }}]}"""

    fun decode(text: String): EventsFrame = try {
        val frame = json.parseToJsonElement(text).jsonObject
        when (frame.string("type")) {
            "hello" -> {
                val schema = frame.getValue("schema_version").jsonPrimitive
                require(!schema.isString && schema.int == 1)
                require(frame.string("required_action") == "subscribe")
                EventsFrame.Hello(frame.strings("available_channels").toSet())
            }
            "subscribed" -> {
                val rejected = LinkedHashMap<String, String>()
                for (entry in frame["rejected"]?.jsonArray.orEmpty()) {
                    val item = entry.jsonObject
                    rejected[item.string("channel")] = item.string("code")
                }
                EventsFrame.Subscribed(frame.string("request_id"), frame.strings("channels").toSet(), rejected)
            }
            "snapshot" -> {
                val channel = frame.string("channel")
                if (channel == LiveChannels.SESSIONS) {
                    frame.timestamp()
                    frame.rows()
                } else {
                    // Present, of any shape (a list, an object or null, depending on the channel).
                    frame.getValue("data")
                }
                EventsFrame.Snapshot(channel)
            }
            "event" -> {
                val channel = frame.string("channel")
                val event = frame.string("event")
                if (channel == LiveChannels.SESSIONS) {
                    if (event == "sessions.replaced") {
                        frame.timestamp()
                        require(frame.string("event_id").isNotBlank())
                        frame.rows()
                        EventsFrame.Event(channel)
                    } else EventsFrame.Ignored
                } else EventsFrame.Event(channel)
            }
            "access_changed" -> EventsFrame.AccessChanged
            // The server answers a failed snapshot for one channel with an error frame and keeps the socket open, so one
            // channel's failure must not end the others. A request it refuses outright ends with a close, which the
            // transport sees. The frame's text is never kept.
            "error" -> EventsFrame.Ignored
            else -> EventsFrame.Ignored
        }
    } catch (failure: SessionTransportException) {
        throw failure
    } catch (_: Exception) {
        // Serialization errors can embed raw payload values. Never retain them.
        fail(SessionFailure.Protocol)
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let {
        require(it.isString)
        it.content
    }
    private fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map {
        require(it.jsonPrimitive.isString)
        it.jsonPrimitive.content
    }
    private fun JsonObject.timestamp() { Instant.parse(string("timestamp")) }
    // Structure only: the payload must be an array, its elements are never read.
    private fun JsonObject.rows() { getValue("data").jsonArray }
    private fun fail(failure: SessionFailure): Nothing = throw SessionTransportException(failure)
}
