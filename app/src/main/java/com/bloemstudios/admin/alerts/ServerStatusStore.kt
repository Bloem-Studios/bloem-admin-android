package com.bloemstudios.admin.alerts

import android.content.SharedPreferences
import com.bloemstudios.admin.widget.PlacedWidget
import com.bloemstudios.admin.widget.WidgetKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** What the last check could tell about a server's numbers. */
enum class CountsStatus {
    /** Not asked: nothing needs counts for this server, or it was not reachable. */
    NotChecked,
    Known,
    /** No session is stored for this server, or the server no longer accepts it. */
    SignInNeeded,
    /** The server answered but could not give the numbers (for example the account is not an administrator). */
    Unavailable,
}

/** The last answer for one server, as the widget and the Quick Settings tile show it. Server names are looked up fresh; only numbers are kept here. */
@Serializable
data class ServerStatus(
    /** Null until a check has run. */
    val reachable: Boolean? = null,
    /** Epoch milliseconds of the last check. */
    val checkedAt: Long? = null,
    val counts: CountsStatus = CountsStatus.NotChecked,
    val streams: Int? = null,
    /** Requests waiting for approval. */
    val requests: Int? = null,
)

@Serializable
data class ServerRecord(
    val status: ServerStatus = ServerStatus(),
    val reach: ReachabilityState = ReachabilityState(),
)

/**
 * Per-server results of the background check. Plain preferences on purpose: it holds numbers and flags only,
 * never an address, a token or a person.
 */
class ServerStatusStore(private val preferences: SharedPreferences) {
    private val serializer = MapSerializer(String.serializer(), ServerRecord.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun records(): Map<String, ServerRecord> =
        preferences.getString(KEY, null)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()

    @Synchronized
    fun put(serverId: String, record: ServerRecord) = write(records() + (serverId to record))

    /** Keeps only [savedIds]; returns the ids that were dropped. */
    @Synchronized
    fun retain(savedIds: Set<String>): Set<String> {
        val current = records()
        val removed = current.keys - savedIds
        if (removed.isNotEmpty()) write(current - removed)
        return removed
    }

    private fun write(records: Map<String, ServerRecord>) {
        preferences.edit().putString(KEY, json.encodeToString(serializer, records)).apply()
    }

    companion object {
        const val FILE = "server_status"
        private const val KEY = "records"
    }
}

/** Which home-screen widgets exist and what each one shows, and whether the Quick Settings tile is in use. */
class SurfacePreferences(private val preferences: SharedPreferences) {
    /** Widget id to saved server id; null means All servers. */
    @Synchronized
    fun widgetTargets(): Map<Int, String?> =
        preferences.all.filterKeys { it.startsWith(WIDGET_PREFIX) }
            .mapNotNull { (key, value) ->
                key.removePrefix(WIDGET_PREFIX).toIntOrNull()?.let { id -> id to (value as? String)?.takeIf { it.isNotEmpty() } }
            }.toMap()

    fun hasWidget(widgetId: Int): Boolean = preferences.contains(WIDGET_PREFIX + widgetId)

    @Synchronized
    fun setWidgetTarget(widgetId: Int, serverId: String?) {
        preferences.edit().putString(WIDGET_PREFIX + widgetId, serverId.orEmpty()).apply()
    }

    /** Which widget this id is. A widget placed before there were several kinds has no entry and is Server status. */
    @Synchronized
    fun widgetKind(widgetId: Int): WidgetKind =
        WidgetKind.fromStorage(preferences.getString(KIND_PREFIX + widgetId, null))

    /** Records what a placed widget shows: its [kind] and its server (null for All servers). */
    @Synchronized
    fun setWidget(widgetId: Int, kind: WidgetKind, serverId: String?) {
        preferences.edit()
            .putString(WIDGET_PREFIX + widgetId, serverId.orEmpty())
            .putString(KIND_PREFIX + widgetId, kind.storage)
            .apply()
    }

    /** Every placed widget with its kind and server. */
    @Synchronized
    fun placedWidgets(): List<PlacedWidget> =
        widgetTargets().map { (id, target) -> PlacedWidget(id, widgetKind(id), target) }.sortedBy { it.id }

    @Synchronized
    fun removeWidgets(widgetIds: IntArray) {
        val editor = preferences.edit()
        widgetIds.forEach { editor.remove(WIDGET_PREFIX + it).remove(KIND_PREFIX + it) }
        editor.apply()
    }

    /** The Now playing Quick Settings tile is in use. */
    var tileAdded: Boolean
        get() = preferences.getBoolean(KEY_TILE, false)
        set(value) = preferences.edit().putBoolean(KEY_TILE, value).apply()

    /** The Requests waiting Quick Settings tile is in use. */
    var requestsTileAdded: Boolean
        get() = preferences.getBoolean(KEY_REQUESTS_TILE, false)
        set(value) = preferences.edit().putBoolean(KEY_REQUESTS_TILE, value).apply()

    /** Either tile: both read the stream and request numbers of the last check. */
    val anyTile: Boolean get() = tileAdded || requestsTileAdded

    companion object {
        const val FILE = "native_surfaces"
        private const val WIDGET_PREFIX = "widget:"
        private const val KIND_PREFIX = "widgetkind:"
        private const val KEY_TILE = "tile_added"
        private const val KEY_REQUESTS_TILE = "requests_tile_added"
    }
}
