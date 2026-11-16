package com.bloemstudios.admin.push

import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.servers.AdminServer
import kotlinx.coroutines.CancellationException

/** Where a received push ends up. [PushNotifier] is the real one. */
interface PushPoster {
    fun post(server: AdminServer, content: PushContent): Boolean
    fun postGeneric(server: AdminServer, deliveryId: String): Boolean
    fun showActionResult(server: AdminServer?, tag: String, requestId: String, text: String, failed: Boolean)
}

enum class PushOutcome {
    /** Not a push this app understands, or not meant to be shown (a wake, an unknown device, a switched-off kind). */
    Ignored,

    /** The text was fetched and the notification posted. */
    Posted,

    /** The server could not be asked in time, so only the generic notification was posted. */
    PostedGeneric,

    /** The notification was ready but Android would not show it. */
    Suppressed,

    /** A silent widget wake: a refresh of the affected widgets was scheduled. Nothing was fetched or shown. */
    Woke,
}

/**
 * A pushed delivery id arrives; the saved server it is for is found from the device id inside it, the text is read from that
 * server with this device's display token, and the notification is posted. The relay carried no text, so a server that
 * does not answer leaves only the generic notification: no title, no name, no count.
 */
class PushReceiver(
    private val servers: suspend () -> List<AdminServer>,
    private val registrations: PushRegistrationStore,
    private val preferences: PushPreferences,
    private val content: PushContentClient,
    private val poster: PushPoster,
    private val waker: WidgetWaker = WidgetWaker { _, _ -> },
) {
    suspend fun receive(data: Map<String, String>): PushOutcome {
        val wire = PushWire.parse(data) ?: return PushOutcome.Ignored
        if (!wire.showsNotification) return wake(wire)
        val (serverId, registration) = registrations.serverFor(wire.deviceId) ?: return PushOutcome.Ignored
        val server = servers().firstOrNull { it.id == serverId } ?: return PushOutcome.Ignored

        val fetched = try {
            content.fetch(server.baseUrl, registration.displayToken, wire.contentId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        val clean = fetched?.sanitized()
        if (clean == null) {
            // No content, or content this build cannot show: say only that something is waiting.
            return if (poster.postGeneric(server, wire.deliveryId)) PushOutcome.PostedGeneric else PushOutcome.Suppressed
        }
        // The server filters by kind too; this is the second lock on the same door, for a switch it has not heard yet.
        if (!preferences.isKindEnabled(serverId, clean.kind)) return PushOutcome.Ignored
        return if (poster.post(server, clean)) PushOutcome.Posted else PushOutcome.Suppressed
    }

    /**
     * A silent wake names channels, never content. It is acted on only when it is well formed, comes from a server this device
     * registered with and that is still saved; then the widgets are refreshed in the background. A wake never fetches the
     * notification route, posts a notification, plays a sound or opens a socket.
     */
    private suspend fun wake(wire: PushWire): PushOutcome {
        val channels = wire.wakeChannels ?: return PushOutcome.Ignored
        val (serverId, _) = registrations.serverFor(wire.deviceId) ?: return PushOutcome.Ignored
        if (servers().none { it.id == serverId }) return PushOutcome.Ignored
        waker.wake(serverId, channels)
        return PushOutcome.Woke
    }
}

/** The buttons on a request notification. They call the same route the widgets do. */
interface PushRequestActions {
    suspend fun decide(server: AdminServer, requestId: String, approve: Boolean)
}

class PushActionRunner(
    private val servers: suspend () -> List<AdminServer>,
    private val actions: PushRequestActions,
    private val poster: PushPoster,
    private val labels: PushActionLabels,
) {
    /** True when the request is decided: by this call, or already by someone else. */
    suspend fun run(serverId: String, requestId: String, approve: Boolean, tag: String): Boolean {
        val server = servers().firstOrNull { it.id == serverId }
        if (server == null) {
            poster.showActionResult(null, tag, requestId, labels.failed(approve), failed = true)
            return false
        }
        return try {
            actions.decide(server, requestId, approve)
            poster.showActionResult(server, tag, requestId, labels.done(approve), failed = false)
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: ApiError) {
            // Already approved, declined or withdrawn by someone else: nothing is waiting any more, which is what the
            // admin wanted to know.
            if (error.statusCode == 404 || error.statusCode == 409) {
                poster.showActionResult(server, tag, requestId, labels.alreadyDone(), failed = false)
                true
            } else {
                poster.showActionResult(server, tag, requestId, labels.failed(approve), failed = true)
                false
            }
        } catch (_: Exception) {
            poster.showActionResult(server, tag, requestId, labels.failed(approve), failed = true)
            false
        }
    }
}

/** The words of an action's outcome, kept out of the logic so they come from string resources. */
interface PushActionLabels {
    fun done(approve: Boolean): String
    fun alreadyDone(): String
    fun failed(approve: Boolean): String
}
