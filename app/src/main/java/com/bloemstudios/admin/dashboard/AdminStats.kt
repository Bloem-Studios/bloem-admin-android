package com.bloemstudios.admin.dashboard

import kotlinx.serialization.Serializable

@Serializable
data class WatchProviderStats(
    val provider: String,
    val display_name: String,
    val registered: Boolean,
    val scrobbling: Boolean,
    val exporting: Boolean,
    val connected_profiles: Long,
    val enabled_profiles: Long,
    val export_enabled_profiles: Long,
    val scrobble_enabled_profiles: Long,
    val last_sync_completed_at: String? = null,
    val sync_runs_24h: Long,
    val sync_errors_24h: Long,
    val imported_watched_24h: Long,
    val imported_progress_24h: Long,
    val exported_watched_24h: Long,
    val pending_exports: Long,
    val failed_exports: Long,
    val open_scrobbles: Long,
    val scrobbles_24h: Long
)

// Mirrors GET /api/v2/admin/stats's response body. Property names are
// already the server's snake_case field names -- same convention as
// AdminSession/PlaybackHistoryEntry elsewhere in this app, which rely on
// kotlinx.serialization matching JSON keys to Kotlin property names directly
// rather than declaring @SerialName on every field.
@Serializable
data class AdminStats(
    val total_items: Int,
    val total_files: Int,
    val total_users: Int,
    val total_movies: Int,
    val total_movie_files: Int,
    val total_shows: Int,
    val total_show_files: Int,
    val active_streams: Int,
    val total_storage_bytes: Long,
    val watch_providers: List<WatchProviderStats>,
    val advisory_titles: Int = 0
)

// Mirrors GET /api/v2/admin/system/resources. `available:false` means the host cannot be
// sampled (not an error); `stale:true` means `system` is the last good sample, taken at
// `sampled_at`, and sampling has since stopped.
@Serializable
data class SystemResources(
    val available: Boolean,
    val stale: Boolean = false,
    val sampled_at: String? = null,
    val system: SystemStats? = null,
    /** One entry per GPU the host reports; empty when it has none. */
    val gpu: List<GpuStats> = emptyList()
)

@Serializable
data class SystemStats(
    val cpu_pct: Double? = null,
    val mem_used_mb: Long? = null,
    val mem_total_mb: Long? = null,
    val net_rx_bps: Double? = null,
    val net_tx_bps: Double? = null,
    val cores: Int? = null,
    val load1: Double? = null,
    val disks: List<DiskStats> = emptyList()
) {
    // The v2 contract reports net_rx_bps/net_tx_bps in bits per second; the screen shows bytes per second.
    val netRxBytesPerSec: Double? get() = net_rx_bps?.let { it / BITS_PER_BYTE }
    val netTxBytesPerSec: Double? get() = net_tx_bps?.let { it / BITS_PER_BYTE }
}

private const val BITS_PER_BYTE = 8.0

/** One mounted disk of the host (AdminDiskStats). [unavailable] means it could not be read. */
@Serializable
data class DiskStats(
    val path: String? = null,
    val role: String? = null,
    val used_gb: Double = 0.0,
    val total_gb: Double = 0.0,
    val scratch: Boolean = false,
    val stale: Boolean = false,
    val unavailable: Boolean = false
)

/** One GPU of the host (AdminGPUStats). [source] says where the numbers come from; "unavailable" or empty means none were measured. */
@Serializable
data class GpuStats(
    val device: String = "",
    val vendor: String? = null,
    val source: String = "",
    val sessions: Int = 0,
    val total_busy_pct: Int? = null,
    val video_busy_pct: Int? = null,
    val render_busy_pct: Int? = null,
    val vram_used_mb: Long? = null,
    val vram_total_mb: Long? = null
)
