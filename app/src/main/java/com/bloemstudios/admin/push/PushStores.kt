package com.bloemstudios.admin.push

import android.content.Context
import android.content.SharedPreferences
import com.bloemstudios.admin.servers.NonPersistingSharedPreferences
import com.bloemstudios.admin.servers.ServerStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Why a saved server does or does not receive push, as the settings screen explains it. */
enum class PushStatus {
    /** Not asked yet, or the last ask could not be answered. */
    Unknown,

    /** This server cannot push to this app: a plain Silo server, or a Bloem server without admin push. Local checks only. */
    NotAvailable,

    /** This build has no Firebase config. */
    NotConfigured,

    /** The server can push but Android notifications are not allowed for this app. */
    NeedsPermission,

    /** Registered: the server pushes to this device. */
    Registered,

    /** The server can push, but registering failed. Tried again at the next sync. */
    Failed,
}

/**
 * Device-local push choices that carry no secret: which kinds are switched off per saved server, the last known status
 * per server, whether the notification permission was already asked, and this installation's id. Everything defaults to
 * on; a kind is stored only when switched off.
 */
class PushPreferences(private val preferences: SharedPreferences) {
    private val _statuses = MutableStateFlow(readStatuses())
    val statuses: StateFlow<Map<String, PushStatus>> = _statuses

    private val _disabled = MutableStateFlow(readDisabled())
    /** Server id to the kinds switched off for it. */
    val disabledKinds: StateFlow<Map<String, Set<String>>> = _disabled

    /** One random id per installation, kept across token refreshes and sign-ins. It names no person or device. */
    fun installationId(): String {
        preferences.getString(KEY_INSTALLATION, null)?.takeIf { it.length >= 8 }?.let { return it }
        val created = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_INSTALLATION, created).apply()
        return created
    }

    val permissionAsked: Boolean get() = preferences.getBoolean(KEY_PERMISSION_ASKED, false)

    fun markPermissionAsked() = preferences.edit().putBoolean(KEY_PERMISSION_ASKED, true).apply()

    fun isKindEnabled(serverId: String, kind: String): Boolean = kind !in disabledFor(serverId)

    fun disabledFor(serverId: String): Set<String> = _disabled.value[serverId].orEmpty()

    fun setKindEnabled(serverId: String, kind: String, enabled: Boolean) {
        val next = if (enabled) disabledFor(serverId) - kind else disabledFor(serverId) + kind
        write(_disabled.value + (serverId to next))
    }

    fun status(serverId: String): PushStatus = _statuses.value[serverId] ?: PushStatus.Unknown

    /**
     * True once any saved server has been found able to push to this app. A device that never has stays out of
     * WorkManager entirely, as the local alerts do.
     */
    val wantsPeriodicSync: Boolean
        get() = _statuses.value.values.any { it == PushStatus.Registered || it == PushStatus.NeedsPermission || it == PushStatus.Failed }

    fun setStatus(serverId: String, status: PushStatus) {
        if (_statuses.value[serverId] == status) return
        val next = _statuses.value + (serverId to status)
        preferences.edit().putString(KEY_STATUSES, json.encodeToString(next.mapValues { it.value.name })).apply()
        _statuses.value = next
    }

    /** Forgets everything kept for servers that are no longer saved. */
    fun retainServers(savedIds: Set<String>) {
        val kept = _disabled.value.filterKeys { it in savedIds }
        if (kept != _disabled.value) write(kept)
        val statuses = _statuses.value.filterKeys { it in savedIds }
        if (statuses != _statuses.value) {
            preferences.edit().putString(KEY_STATUSES, json.encodeToString(statuses.mapValues { it.value.name })).apply()
            _statuses.value = statuses
        }
    }

    private fun write(next: Map<String, Set<String>>) {
        val clean = next.filterValues { it.isNotEmpty() }
        preferences.edit().putString(KEY_DISABLED, json.encodeToString(clean.mapValues { it.value.sorted() })).apply()
        _disabled.value = clean
    }

    private fun readDisabled(): Map<String, Set<String>> = try {
        json.decodeFromString<Map<String, List<String>>>(preferences.getString(KEY_DISABLED, null) ?: "{}")
            .mapValues { it.value.toSet() }.filterValues { it.isNotEmpty() }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun readStatuses(): Map<String, PushStatus> = try {
        json.decodeFromString<Map<String, String>>(preferences.getString(KEY_STATUSES, null) ?: "{}")
            .mapNotNull { (id, name) -> PushStatus.entries.firstOrNull { it.name == name }?.let { id to it } }.toMap()
    } catch (_: Exception) {
        emptyMap()
    }

    companion object {
        const val FILE = "push_preferences"
        private const val KEY_INSTALLATION = "installation_id"
        private const val KEY_PERMISSION_ASKED = "permission_asked"
        private const val KEY_DISABLED = "disabled_kinds"
        private const val KEY_STATUSES = "statuses"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** What registering with one saved server returned: the device id the server pushes to, and the token that reads its text. */
@Serializable
data class PushRegistration(
    val deviceId: String,
    val displayToken: String,
    /** A hash of the FCM token this registration was made with, so a rotated token is noticed without keeping the token. */
    val tokenHash: String = "",
    val registeredAt: Long = 0L,
    /** The server could not be told a switch; the next pass registers again so it hears every choice. */
    val stale: Boolean = false,
    /** What the server was last told about this device's widgets (the channels, joined, or "" for none), or "?" for never. */
    val widgetReport: String = PushRegistrar.NOT_REPORTED,
) {
    override fun toString(): String = "PushRegistration(deviceId=$deviceId)"
}

/**
 * The per-server registrations. The display token is a credential for reading this device's notification text, so it lives
 * in encrypted storage like the sign-in tokens, never in the plain preferences.
 */
class PushRegistrationStore(private val preferences: SharedPreferences) {
    fun get(serverId: String): PushRegistration? = read(KEY_PREFIX + serverId)

    fun put(serverId: String, registration: PushRegistration) {
        preferences.edit().putString(KEY_PREFIX + serverId, json.encodeToString(registration)).apply()
    }

    fun remove(serverId: String) {
        preferences.edit().remove(KEY_PREFIX + serverId).apply()
    }

    /** The saved server a push belongs to, from the device id its delivery id starts with. */
    fun serverFor(deviceId: String): Pair<String, PushRegistration>? =
        preferences.all.entries.firstNotNullOfOrNull { (key, value) ->
            if (!key.startsWith(KEY_PREFIX) || value !is String) return@firstNotNullOfOrNull null
            val registration = decode(value) ?: return@firstNotNullOfOrNull null
            if (registration.deviceId == deviceId) key.removePrefix(KEY_PREFIX) to registration else null
        }

    fun serverIds(): Set<String> = preferences.all.keys.filter { it.startsWith(KEY_PREFIX) }.map { it.removePrefix(KEY_PREFIX) }.toSet()

    private fun read(key: String): PushRegistration? = preferences.getString(key, null)?.let(::decode)

    private fun decode(raw: String): PushRegistration? = try {
        json.decodeFromString<PushRegistration>(raw)
    } catch (_: Exception) {
        null
    }

    companion object {
        const val FILE = "admin_push_registrations"
        const val MASTER_KEY_ALIAS = "bloem_admin_push_master_key"
        private const val KEY_PREFIX = "reg:"
        private val json = Json { ignoreUnknownKeys = true }

        /** Opens the encrypted file; if the keystore cannot, nothing is kept and push simply registers again next time. */
        fun open(context: Context): PushRegistrationStore = PushRegistrationStore(
            try {
                ServerStore.createEncryptedPrefs(context.applicationContext, FILE, MASTER_KEY_ALIAS)
            } catch (_: Exception) {
                NonPersistingSharedPreferences()
            },
        )
    }
}
