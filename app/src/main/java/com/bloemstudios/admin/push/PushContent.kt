package com.bloemstudios.admin.push

import com.bloemstudios.admin.links.DeepLink
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a push carries: only a delivery id and a mode. The relay never sees text. The id is `{deviceId}.{delivery}`; the
 * device id is what this app got when it registered with one saved server, so it names which saved server the push is for.
 */
data class PushWire(val deviceId: String, val deliveryId: String, val mode: String) {
    /** The id the content route takes, exactly as pushed. */
    val contentId: String get() = "$deviceId.$deliveryId"

    val showsNotification: Boolean get() = mode == MODE_ALERT

    /** The widget channels a silent wake names, or null when this is not a well-formed widget wake. */
    val wakeChannels: Set<String>? get() = if (mode == MODE_WAKE) PushWake.channelsOf(deliveryId) else null

    companion object {
        const val KEY_DELIVERY = "bloem_delivery_id"
        const val KEY_MODE = "bloem_mode"
        const val MODE_ALERT = "private_alert"
        const val MODE_WAKE = "background_wake"

        private val ID = Regex("^([A-Za-z0-9]{1,64})\\.([A-Za-z0-9-]{1,64})$")

        /** Reads an FCM data message. Anything that is not a well-formed admin push is rejected, not guessed at. */
        fun parse(data: Map<String, String>): PushWire? {
            val raw = data[KEY_DELIVERY] ?: return null
            val match = ID.matchEntire(raw) ?: return null
            val mode = data[KEY_MODE] ?: MODE_ALERT
            if (mode != MODE_ALERT && mode != MODE_WAKE) return null
            return PushWire(match.groupValues[1], match.groupValues[2], mode)
        }
    }
}

/** The text and behavior of one notification, read from the server with this device's display token. */
@Serializable
data class PushContent(
    @SerialName("delivery_id") val deliveryId: String,
    val kind: String,
    val channel: String = "",
    val title: String,
    val body: String = "",
    @SerialName("lock_screen") val lockScreen: String,
    val page: String? = null,
    @SerialName("item_id") val itemId: String? = null,
    @SerialName("subject_type") val subjectType: String? = null,
    val actions: List<String> = emptyList(),
    val badge: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    /**
     * The content cleaned up for display. A server could send anything, so: text is trimmed to one short line, a channel
     * this build does not know falls back to the kind's own, a link page must be one the app has, and a button appears only
     * on a request notice that names its request. Returns null when there is nothing worth showing.
     */
    fun sanitized(): PushContent? {
        val info = PushKinds.byId(kind) ?: return null
        val cleanTitle = oneLine(title, MAX_TEXT)
        if (cleanTitle.isEmpty()) return null
        val cleanItem = itemId?.takeIf { it.isNotEmpty() && it.length <= MAX_ITEM }
        val cleanPage = page?.takeIf { it in DeepLink.pageSlugs }
        val cleanActions = if (kind == PushKind.REQUEST_WAITING && cleanItem != null) actions.filter { it in ACTIONS }.distinct() else emptyList()
        return copy(
            title = cleanTitle,
            body = oneLine(body, MAX_TEXT),
            lockScreen = oneLine(lockScreen, MAX_TEXT).ifEmpty { GENERIC_LOCK_SCREEN },
            channel = PushChannels.idFor(channel) ?: info.channelId,
            page = cleanPage,
            itemId = cleanItem.takeIf { cleanPage != null },
            actions = cleanActions,
            badge = badge?.coerceIn(0, MAX_BADGE),
        )
    }

    companion object {
        const val ACTION_APPROVE = "approve"
        const val ACTION_DECLINE = "decline"
        private val ACTIONS = setOf(ACTION_APPROVE, ACTION_DECLINE)
        private const val MAX_TEXT = 140
        private const val MAX_ITEM = 128
        private const val MAX_BADGE = 9999
        const val GENERIC_LOCK_SCREEN = "New notification"

        private fun oneLine(value: String, max: Int): String {
            val flat = value.filter { it >= ' ' || it == '\n' || it == '\t' }.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
        }
    }
}
