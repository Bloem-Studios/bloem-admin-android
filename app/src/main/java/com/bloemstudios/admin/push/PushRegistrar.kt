package com.bloemstudios.admin.push

import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.servers.AdminServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

/** What the registrar needs from a signed-in server. [StorePushSession] is the real one; tests supply their own. */
interface PushServerSession : AutoCloseable {
    fun hasSession(server: AdminServer): Boolean

    /** True when the server lists the push token, false when it cannot push to this app (final), null when it could not be told. */
    suspend fun pushCapable(server: AdminServer): Boolean?

    suspend fun register(server: AdminServer, request: PushRegisterRequest): PushRegistered

    suspend fun setKinds(server: AdminServer, deviceId: String, kinds: Map<String, Boolean>)

    /** True when the server also lists the widget wakes token: it accepts the widget report and sends wakes. */
    suspend fun supportsWidgetWakes(server: AdminServer): Boolean = false

    suspend fun setWidgets(server: AdminServer, deviceId: String, report: WidgetReport) {}
}

/**
 * Keeps each saved Bloem server's registration for this device in step with the choices on it. Plain Silo servers are
 * asked one question (the capability probe) and never sent a registration: they cannot push to this app, so for them the
 * app keeps only its own server-down checks and says so.
 *
 * Registering replaces the display token the server issued before, so it is done only when needed: no registration yet, the
 * FCM token changed, or the last one is a week old.
 */
class PushRegistrar(
    private val servers: suspend () -> List<AdminServer>,
    private val session: PushServerSession,
    private val registrations: PushRegistrationStore,
    private val preferences: PushPreferences,
    private val content: PushContentClient,
    /** The FCM registration token, or null when this build has no Firebase or Firebase has none yet. */
    private val fcmToken: suspend () -> String?,
    private val firebaseConfigured: Boolean,
    private val notificationsAllowed: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The widgets placed on this device, for the report to servers that send widget wakes. */
    private val widgets: PlacedWidgets = PlacedWidgets { emptyList() },
) {
    /** Returns how many servers were registered or refreshed in this pass. */
    suspend fun reconcile(): Int {
        val saved = servers()
        val savedIds = saved.map { it.id }.toSet()
        preferences.retainServers(savedIds)
        // A registration for a server that is no longer saved has no address left to retire it at.
        registrations.serverIds().filter { it !in savedIds }.forEach(registrations::remove)
        if (!firebaseConfigured) {
            // Even with no Firebase in this build, a plain Silo server is told apart from a Bloem server that could push,
            // so the settings say the right thing for each.
            for (server in saved) {
                if (!session.hasSession(server)) continue
                when (session.pushCapable(server)) {
                    false -> preferences.setStatus(server.id, PushStatus.NotAvailable)
                    true -> preferences.setStatus(server.id, PushStatus.NotConfigured)
                    null -> Unit
                }
            }
            return 0
        }
        val token = fcmToken() ?: return 0
        val tokenHash = sha256(token)
        var changed = 0
        for (server in saved) {
            if (!session.hasSession(server)) continue
            when (session.pushCapable(server)) {
                false -> {
                    registrations.remove(server.id)
                    preferences.setStatus(server.id, PushStatus.NotAvailable)
                }
                null -> Unit
                true -> if (registerIfNeeded(server, token, tokenHash)) changed++
            }
        }
        return changed
    }

    private suspend fun registerIfNeeded(server: AdminServer, token: String, tokenHash: String): Boolean {
        val existing = registrations.get(server.id)
        val fresh = existing != null && !existing.stale && existing.tokenHash == tokenHash && clock() - existing.registeredAt < REFRESH_AFTER_MILLIS
        if (fresh) {
            preferences.setStatus(server.id, allowedStatus())
            // Widgets come and go without the token changing: tell the server when what they show has changed.
            reportWidgets(server, existing!!)
            return false
        }
        return try {
            val wakes = session.supportsWidgetWakes(server)
            val report = if (wakes) WidgetReport.forServer(widgets.placed(), server.id) else null
            val registered = session.register(
                server,
                PushRegisterRequest(
                    installationId = preferences.installationId(),
                    platform = PLATFORM,
                    token = token,
                    disabledKinds = remoteDisabled(server.id),
                    widgetsPlaced = report?.placed,
                    widgetChannels = report?.channels,
                ),
            )
            registrations.put(
                server.id,
                PushRegistration(registered.deviceId, registered.displayToken, tokenHash, clock(), widgetReport = report?.key ?: NOT_REPORTED),
            )
            preferences.setStatus(server.id, allowedStatus())
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: ApiError) {
            // The server says it cannot push after all (push turned off, or the sign-in lost admin rights).
            preferences.setStatus(server.id, if (error.statusCode == 503) PushStatus.NotAvailable else PushStatus.Failed)
            false
        } catch (_: Exception) {
            preferences.setStatus(server.id, PushStatus.Failed)
            false
        }
    }

    /**
     * Tells a server that sends widget wakes what this device's widgets show, when that changed since the last time. A server that
     * does not send wakes is never sent the report, and a failure leaves the old report in place so the next pass tries again.
     */
    private suspend fun reportWidgets(server: AdminServer, registration: PushRegistration) {
        val report = WidgetReport.forServer(widgets.placed(), server.id)
        if (registration.widgetReport == report.key && registration.widgetReport != NOT_REPORTED) return
        if (!session.supportsWidgetWakes(server)) return
        try {
            session.setWidgets(server, registration.deviceId, report)
            registrations.put(server.id, registration.copy(widgetReport = report.key))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Left as it was; the next pass sends it again.
        }
    }

    private fun allowedStatus() = if (notificationsAllowed()) PushStatus.Registered else PushStatus.NeedsPermission

    private fun remoteDisabled(serverId: String): List<String> =
        preferences.disabledFor(serverId).filter { kind -> PushKinds.byId(kind)?.local == false }.sorted()

    /**
     * Switches one kind for one saved server. The choice is kept on this device first; a Bloem server that pushes to this
     * device is told too. If it cannot be told now, the registration is marked stale so the next pass sends the choices
     * again.
     */
    suspend fun setKind(server: AdminServer, kind: String, enabled: Boolean) {
        preferences.setKindEnabled(server.id, kind, enabled)
        if (PushKinds.byId(kind)?.local != false) return
        val registration = registrations.get(server.id) ?: return
        try {
            session.setKinds(server, registration.deviceId, mapOf(kind to enabled))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            registrations.put(server.id, registration.copy(stale = true))
        }
    }

    /**
     * Stops pushes to this device for [server] and forgets the registration. It uses the display token, so it works after
     * the sign-in is gone, and waits at most [UNREGISTER_WAIT_MILLIS]: signing out or removing a server never hangs on a
     * server that does not answer. The local record is dropped either way.
     */
    suspend fun unregister(server: AdminServer) {
        val registration = registrations.get(server.id) ?: return
        try {
            withTimeoutOrNull(UNREGISTER_WAIT_MILLIS) { content.retire(server.baseUrl, registration.displayToken, registration.deviceId) }
        } finally {
            registrations.remove(server.id)
        }
    }

    companion object {
        const val PLATFORM = "android"

        /** The server has never been told about widgets (an older server, or a registration before the report existed). */
        const val NOT_REPORTED = "?"
        const val UNREGISTER_WAIT_MILLIS = 5_000L
        const val REFRESH_AFTER_MILLIS = 7L * 24 * 60 * 60 * 1000

        internal fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
