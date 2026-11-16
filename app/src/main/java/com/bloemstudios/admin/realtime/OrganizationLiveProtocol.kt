package com.bloemstudios.admin.realtime

import kotlinx.serialization.json.*

/** The channels of the organization live socket. A page subscribes only to the one it shows. */
object OrganizationLiveChannels {
    const val STREAMS = "org.streams"
    const val PEOPLE = "org.people"
    const val ACTIVITY = "org.activity"
}

sealed interface OrganizationLiveFrame {
    /** One item of [channel] changed. Only the channel matters: the page reads its list again. */
    data class Change(val channel: String) : OrganizationLiveFrame
    /** Read the whole list of [channel] again: sent after every accepted subscribe, after a burst, and after a lost signal. */
    data class Resync(val channel: String) : OrganizationLiveFrame
    data object Heartbeat : OrganizationLiveFrame
    /** `unknown_channel` or `unavailable`: the server will not serve [channel]. The connection stays open. */
    data class Error(val code: String, val channel: String?) : OrganizationLiveFrame
    data object Ignored : OrganizationLiveFrame
}

/** `bloem-live.v1`, the frames of the organization live socket. Item ids, revisions and kinds are never kept: the page re-reads. */
internal object OrganizationLiveProtocol {
    const val PROTOCOL = "bloem-live.v1"
    private val json = Json { ignoreUnknownKeys = true }

    /** Channel names are fixed identifiers of this app, never user input, so they need no escaping. */
    fun subscribe(channels: Collection<String>): String =
        """{"op":"subscribe","channels":[${channels.joinToString(",") { "\"$it\"" }}],"resume":null}"""

    fun unsubscribe(channels: Collection<String>): String =
        """{"op":"unsubscribe","channels":[${channels.joinToString(",") { "\"$it\"" }}]}"""

    fun decode(text: String): OrganizationLiveFrame = try {
        val frame = json.parseToJsonElement(text).jsonObject
        when (frame.string("op")) {
            "change" -> {
                frame.string("id")
                require(frame.string("kind") in KINDS)
                OrganizationLiveFrame.Change(frame.string("channel"))
            }
            "resync" -> OrganizationLiveFrame.Resync(frame.string("channel"))
            "heartbeat" -> OrganizationLiveFrame.Heartbeat
            "error" -> OrganizationLiveFrame.Error(frame.string("code"), frame["channel"]?.jsonPrimitive?.takeIf { it.isString }?.content)
            // A frame this client does not know is not a failure: the protocol may grow.
            else -> OrganizationLiveFrame.Ignored
        }
    } catch (_: Exception) {
        // Serialization errors can embed raw payload values. Never retain them.
        throw SessionTransportException(SessionFailure.Protocol)
    }

    private val KINDS = setOf("created", "updated", "deleted")

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let {
        require(it.isString)
        it.content
    }
}
