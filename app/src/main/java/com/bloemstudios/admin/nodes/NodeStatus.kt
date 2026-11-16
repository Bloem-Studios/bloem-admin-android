package com.bloemstudios.admin.nodes

import com.bloemstudios.admin.jobs.Elapsed
import com.bloemstudios.admin.jobs.elapsedOf
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * A node as GET /api/v2/admin/nodes/summary sends it: health, drain state, load and capacity, without addresses or
 * configuration. [drain_requested] means an administrator asked a transcode node to drain; [drain_state] is what the node
 * reported on its last health check.
 */
@Serializable
data class NodeSummary(
    val id: String,
    val name: String,
    val type: String,
    val enabled: Boolean,
    val healthy: Boolean,
    val active_jobs: Int = 0,
    val group: String? = null,
    val max_jobs: Int? = null,
    val max_bandwidth_kbps: Int? = null,
    val egress_kbps: Int = 0,
    val last_health_check: String? = null,
    val drain_requested: Boolean = false,
    val drain_state: String = "none",
    val resources: NodeResources? = null,
)

/** The resource sample the node stored at its last health check. */
@Serializable
data class NodeResources(
    val sampled_at: String? = null,
    val system: NodeSystem? = null,
    val attribution: NodeAttribution? = null,
)

@Serializable
data class NodeAttribution(val sample_interval_seconds: Double = 5.0)

@Serializable
data class NodeSystem(
    val cpu_pct: Int = 0,
    val load1: Double = 0.0,
    val cores: Int = 0,
    val mem_used_mb: Long = 0,
    val mem_total_mb: Long = 0,
    val disks: List<NodeDisk> = emptyList(),
    val net_rx_bps: Long = 0,
    val net_tx_bps: Long = 0,
)

@Serializable
data class NodeDisk(
    val used_gb: Double = 0.0,
    val total_gb: Double = 0.0,
    val role: String? = null,
    val stale: Boolean = false,
    val unavailable: Boolean = false,
)

/** What GET /api/v2/admin/nodes/capabilities says this server supports. */
@Serializable
data class NodeCapabilities(
    val state: String = "",
    val allowed: Boolean = true,
    val drain: Boolean = false,
    val summary: Boolean = false,
)

/** One node's answer to Drain or Resume. */
@Serializable
data class NodeDrainResult(val node_id: String = "", val drain_requested: Boolean = false, val drain_state: String = "none", val active_jobs: Int = 0)

/** Whether a node can take work, and why not: one state out of enabled, healthy and drain. Disabled outranks the rest. */
enum class NodeState { Healthy, Draining, Drained, Unhealthy, Disabled }

fun nodeState(node: NodeSummary): NodeState = when {
    !node.enabled -> NodeState.Disabled
    !node.healthy -> NodeState.Unhealthy
    node.drain_state == "drained" -> NodeState.Drained
    node.drain_requested || node.drain_state == "draining" -> NodeState.Draining
    else -> NodeState.Healthy
}

enum class DrainAction { Drain, Resume }

/**
 * Drain stops new work on a transcode node while its sessions finish; Resume lifts that request. A proxy node cannot drain
 * (the server answers 409), and a server that does not state drain support gets neither. A node draining only because it
 * received SIGTERM has no request to lift, so it is offered Drain, not Resume.
 */
fun drainAction(node: NodeSummary, drainSupported: Boolean): DrainAction? = when {
    !drainSupported || node.type != "transcode" -> null
    node.drain_requested -> DrainAction.Resume
    else -> DrainAction.Drain
}

fun enabledAfterToggle(node: NodeSummary): Boolean = !node.enabled

/** An enabled node that is not healthy: what the Needs attention count and the dashboard cell are about. */
fun needsAttention(node: NodeSummary): Boolean = node.enabled && !node.healthy

/** The four readings of a node's last sample. */
data class NodeLoad(
    val cpuPercent: Int,
    val memoryPercent: Int,
    val memoryUsedTotalMb: Pair<Long, Long>,
    val diskPercent: Int?,
    val rxBytesPerSecond: Long,
    val txBytesPerSecond: Long,
)

/**
 * The node's load from its stored sample, or null when there is nothing current to show. An unhealthy node reports nothing
 * (its last numbers predate the failed check), and a sample the health check has stopped refreshing is stale.
 */
fun nodeLoad(node: NodeSummary, now: Instant): NodeLoad? {
    val resources = node.resources ?: return null
    val system = resources.system ?: return null
    if (!node.healthy || sampleIsStale(node, resources, now)) return null
    return NodeLoad(
        cpuPercent = system.cpu_pct.coerceIn(0, 100),
        memoryPercent = percentOf(system.mem_used_mb.toDouble(), system.mem_total_mb.toDouble()) ?: 0,
        memoryUsedTotalMb = system.mem_used_mb to system.mem_total_mb,
        diskPercent = worstDiskPercent(system.disks),
        rxBytesPerSecond = system.net_rx_bps,
        txBytesPerSecond = system.net_tx_bps,
    )
}

/** The node answers, but its sampler or its health checks have stopped moving (the web's rule). */
private fun sampleIsStale(node: NodeSummary, resources: NodeResources, now: Instant): Boolean {
    val sampledAt = resources.sampled_at?.let(::parse) ?: return false
    val checkedAt = node.last_health_check?.let(::parse) ?: return false
    val interval = resources.attribution?.sample_interval_seconds ?: 5.0
    return Duration.between(sampledAt, checkedAt).toMillis() > interval * 3_000 || Duration.between(checkedAt, now).toMillis() > 90_000
}

private fun parse(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

private fun percentOf(used: Double, total: Double): Int? =
    if (total > 0) ((used / total) * 100).toInt().coerceIn(0, 100) else null

/** The fullest mount, as a percentage; mounts the node cannot read or that report no size are left out. */
fun worstDiskPercent(disks: List<NodeDisk>): Int? =
    disks.filterNot { it.unavailable }.mapNotNull { percentOf(it.used_gb, it.total_gb) }.maxOrNull()

/** Concurrent work against the node's cap. A cap of zero or none means unlimited. */
data class NodeCapacity(val active: Int, val cap: Int?, val atCap: Boolean)

fun nodeCapacity(node: NodeSummary): NodeCapacity {
    val active = node.active_jobs.coerceAtLeast(0)
    val cap = node.max_jobs?.takeIf { it > 0 }
    return NodeCapacity(active, cap, atCap = cap != null && active >= cap)
}

/** How long ago the node was last health-checked; null when it never was. */
fun lastCheckAge(node: NodeSummary, now: Instant): Elapsed? {
    val at = node.last_health_check?.let(::parse) ?: return null
    return elapsedOf(Duration.between(at, now).seconds)
}

/** Megabits per second from bytes per second, to one decimal with a trailing zero dropped. */
fun megabitsPerSecond(bytesPerSecond: Long, locale: Locale = Locale.getDefault()): String {
    val mbps = bytesPerSecond.coerceAtLeast(0) * 8 / 1_000_000.0
    return NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1; minimumFractionDigits = 0 }.format(mbps)
}

/** The few fields of the full node row the app reads: its id and the validator for a configuration write. */
@Serializable
data class NodeConfigRef(val id: String, val config_etag: String? = null)

/** PUT /api/v2/admin/nodes/{id} takes only the fields to change. */
@Serializable
data class NodeEnabledUpdate(val enabled: Boolean)

/** The acknowledgement of a configuration write. */
@Serializable
data class NodeConfigAck(val id: String, val enabled: Boolean = true)
