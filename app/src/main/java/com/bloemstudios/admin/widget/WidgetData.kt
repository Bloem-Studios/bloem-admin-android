package com.bloemstudios.admin.widget

import com.bloemstudios.admin.streams.StreamMethod
import kotlinx.serialization.Serializable

/**
 * What the last attempt to read one server for one widget came to. Only [Ok] carries data; [Unreachable] keeps the data of
 * the last good read next to it, so a widget can show it with its time. The other two drop the data: a server that no longer
 * offers the feature, or no longer accepts the sign-in, has nothing worth keeping on a home screen.
 */
@Serializable
enum class Outcome {
    Ok,

    /** The server answered, but the feature is not there: a capabilities answer says so, or the route is missing. */
    Unavailable,

    /** No stored session, or the server no longer accepts it. */
    SignInNeeded,

    /** The server did not answer, or answered with a server error. */
    Unreachable,
}

// ---- What each widget keeps. Names and titles are kept here and hidden again when a widget is drawn (WidgetPrivacy). ----

@Serializable
data class StreamItem(
    val sessionId: String,
    val user: String,
    val title: String,
    /** 0 to 1 as of the read, or null when the file's length is not known. */
    val progress: Float? = null,
    val transcode: Boolean = false,
    val paused: Boolean = false,
    val bitrateKbps: Int? = null,
    // What the app's own session card shows (streams/StreamRow.kt), read once with the rest. Each has a default so a cache
    // written before they existed still reads.
    /** "maria · Living room": the user, and the profile when it adds something (streamUserLabel). */
    val userLabel: String? = null,
    /** How it is delivered; null in an entry from before this was kept, which falls back to [transcode]. */
    val method: StreamMethod? = null,
    /** "HEVC 4K · MKV": what the file holds. */
    val source: String? = null,
    /** "Infuse 8.1 · tvOS": the player and the device. */
    val client: String? = null,
    /** "tvOS": the device alone, for the narrow cards. */
    val device: String? = null,
    /** What does the video work while transcoding: `hw:QSV`, `sw`, `pending`, `unknown` or `audio`. */
    val transcodeMode: String? = null,
    /** What a transcode delivers: "H.264 1080p". */
    val target: String? = null,
    /** Where playback was when the server was read. The widget moves it forward from there while the data is fresh. */
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double? = null,
) {
    /** When the file should end if it keeps playing, counted from [readAt]; null when it is paused or its length is unknown. */
    fun endsAt(readAt: Long): Long? {
        if (paused) return null
        val length = durationSeconds?.takeIf { it > 0.0 } ?: return null
        return readAt + ((length - positionSeconds).coerceAtLeast(0.0) * 1000).toLong()
    }
}

@Serializable
data class NowPlayingData(
    /** Every live stream, not only the ones in [streams]. */
    val total: Int,
    val transcoding: Int = 0,
    /** Sum of the streams' bitrates that the server reported. */
    val bandwidthKbps: Long = 0,
    /** The streams, in the server's order (up to [NOW_PLAYING_MAX_ITEMS]). */
    val streams: List<StreamItem> = emptyList(),
    /** Epoch milliseconds the server was read; each stream's position is as of then. Zero in an entry from before it was kept. */
    val readAt: Long = 0,
)

@Serializable
data class RequestItem(
    val id: String,
    val title: String,
    /** The requester's user name, or null when it could not be looked up. */
    val who: String? = null,
    /** RFC 3339 instant the request was made. */
    val createdAt: String? = null,
)

@Serializable
data class RequestsData(
    /** Every request waiting for approval, not only the ones in [items]. */
    val pending: Int,
    /** The newest few, newest first. */
    val items: List<RequestItem> = emptyList(),
)

/** A scan or refresh under way for one library. [percent] is null for a scan, which the server does not measure. */
@Serializable
data class ScanBrief(
    /** `queued`, `running` or `refreshing` (a metadata refresh, which does report progress). */
    val state: String,
    val percent: Int? = null,
)

@Serializable
data class LibraryItem(
    val id: String,
    val name: String,
    /** RFC 3339 instant of the last finished scan, or null if it never ran. */
    val lastScannedAt: String? = null,
    val warning: Boolean = false,
    val scan: ScanBrief? = null,
)

@Serializable
data class LibrariesData(
    val libraries: List<LibraryItem> = emptyList(),
    /** False when the server cannot start or list scans; the libraries still show, without scan state. */
    val scansAvailable: Boolean = true,
)

@Serializable
data class NodeItem(
    val id: String,
    val name: String,
    /** `healthy`, `draining`, `drained`, `unhealthy` or `disabled`. */
    val state: String,
    val cpuPercent: Int? = null,
    val memoryPercent: Int? = null,
    val diskPercent: Int? = null,
)

@Serializable
data class NodesData(val nodes: List<NodeItem> = emptyList())

/** One server's last answer for one widget kind. */
@Serializable
data class CachedSlice(
    val outcome: Outcome,
    /** Epoch milliseconds of the last attempt, successful or not. */
    val attemptedAt: Long,
    /** Epoch milliseconds the data in this slice was read; null when there is none. */
    val dataAt: Long? = null,
    val nowPlaying: NowPlayingData? = null,
    val requests: RequestsData? = null,
    val libraries: LibrariesData? = null,
    val nodes: NodesData? = null,
) {
    val hasData: Boolean get() = nowPlaying != null || requests != null || libraries != null || nodes != null
}

/** The most items a list widget keeps for each server (the large size shows the first few and scrolls to the rest). */
const val WIDGET_MAX_ITEMS = 6

/** Now playing keeps every live stream a server reports (up to this many), so the large size can scroll through all of them. */
const val NOW_PLAYING_MAX_ITEMS = 24
