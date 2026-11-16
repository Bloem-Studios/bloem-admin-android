package com.bloemstudios.admin.widget

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** A failed Approve or Decline, shown in the widget until the next good read of that server's requests. */
@Serializable
data class ActionNotice(val serverId: String, val approve: Boolean, val at: Long)

/**
 * The last good data of every placed widget, per server and kind. It is a file in the app's no-backup folder: it holds
 * titles and user names that a home-screen widget shows, so it is never backed up or copied to a new device, and it is
 * emptied of any server that is removed or signs out ([retain]) and of any slice whose feature goes away (the writer
 * stores no payload for those outcomes).
 */
class WidgetCache(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), CachedSlice.serializer())
    private val noticeSerializer = MapSerializer(String.serializer(), ActionNotice.serializer())

    private fun key(serverId: String, kind: WidgetKind) = "$serverId|${kind.storage}"

    /** Everything at once, so a widget that draws several servers reads the file one time. */
    class Snapshot(private val slices: Map<String, CachedSlice>, private val notices: Map<String, ActionNotice>) {
        fun slice(serverId: String, kind: WidgetKind): CachedSlice? = slices["$serverId|${kind.storage}"]
        fun notice(serverId: String): ActionNotice? = notices[serverId]

        /** Every server's last answer for [kind]. */
        fun slicesOf(kind: WidgetKind): List<CachedSlice> = slices.filterKeys { it.substringAfter('|') == kind.storage }.values.toList()
    }

    @Synchronized
    fun snapshot(): Snapshot = read().let { Snapshot(it.first, it.second) }

    @Synchronized
    fun slice(serverId: String, kind: WidgetKind): CachedSlice? = read().first[key(serverId, kind)]

    @Synchronized
    fun put(serverId: String, kind: WidgetKind, slice: CachedSlice) {
        val (slices, notices) = read()
        write(slices + (key(serverId, kind) to slice), notices)
    }

    /** Keeps only the servers in [savedIds]. */
    @Synchronized
    fun retain(savedIds: Set<String>) {
        val (slices, notices) = read()
        val keptSlices = slices.filterKeys { it.substringBefore('|') in savedIds }
        val keptNotices = notices.filterKeys { it in savedIds }
        if (keptSlices.size != slices.size || keptNotices.size != notices.size) write(keptSlices, keptNotices)
    }

    /** Forgets everything; used when no widget is left. */
    @Synchronized
    fun clear() {
        file.delete()
    }

    @Synchronized
    fun notice(serverId: String): ActionNotice? = read().second[serverId]

    @Synchronized
    fun setNotice(serverId: String, notice: ActionNotice?) {
        val (slices, notices) = read()
        write(slices, if (notice == null) notices - serverId else notices + (serverId to notice))
    }

    /**
     * Takes one request out of a server's cached list after it was approved or declined, so the widget stops showing it
     * before the next read confirms it.
     */
    @Synchronized
    fun dropRequest(serverId: String, requestId: String) {
        val (slices, notices) = read()
        val k = key(serverId, WidgetKind.Requests)
        val slice = slices[k] ?: return
        val data = slice.requests ?: return
        if (data.items.none { it.id == requestId }) return
        val left = data.items.filterNot { it.id == requestId }
        write(slices + (k to slice.copy(requests = data.copy(pending = (data.pending - 1).coerceAtLeast(0), items = left))), notices)
    }

    private fun read(): Pair<Map<String, CachedSlice>, Map<String, ActionNotice>> {
        val text = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull() ?: return emptyMap<String, CachedSlice>() to emptyMap()
        val root = runCatching { json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject }.getOrNull()
            ?: return emptyMap<String, CachedSlice>() to emptyMap()
        val slices = root["slices"]?.let { runCatching { json.decodeFromJsonElement(serializer, it) }.getOrNull() }.orEmpty()
        val notices = root["notices"]?.let { runCatching { json.decodeFromJsonElement(noticeSerializer, it) }.getOrNull() }.orEmpty()
        return slices to notices
    }

    private fun write(slices: Map<String, CachedSlice>, notices: Map<String, ActionNotice>) {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("slices", json.encodeToJsonElement(serializer, slices))
            put("notices", json.encodeToJsonElement(noticeSerializer, notices))
        }
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(body.toString())
            if (!temp.renameTo(file)) { file.writeText(body.toString()); temp.delete() }
        }
    }

    companion object {
        const val FILE_NAME = "widget-cache.json"

        fun forContext(context: Context): WidgetCache = WidgetCache(File(context.applicationContext.noBackupFilesDir, FILE_NAME))
    }
}
