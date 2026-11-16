package com.bloemstudios.admin.nodes

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NodeStatusTest {
    private val now = Instant.parse("2026-11-14T10:00:00Z")
    private val json = Json { ignoreUnknownKeys = true }

    private fun node(
        enabled: Boolean = true,
        healthy: Boolean = true,
        type: String = "transcode",
        drainRequested: Boolean = false,
        drainState: String = "none",
        activeJobs: Int = 0,
        maxJobs: Int? = null,
        lastCheck: String? = "2026-11-14T09:59:40Z",
        resources: NodeResources? = null,
    ) = NodeSummary(
        id = "7", name = "gpu-1", type = type, enabled = enabled, healthy = healthy, active_jobs = activeJobs, group = "rack-a",
        max_jobs = maxJobs, last_health_check = lastCheck, drain_requested = drainRequested, drain_state = drainState, resources = resources,
    )

    private val sample = NodeResources(
        sampled_at = "2026-11-14T09:59:40Z",
        system = NodeSystem(
            cpu_pct = 37, load1 = 1.5, cores = 8, mem_used_mb = 6_144, mem_total_mb = 16_384,
            disks = listOf(NodeDisk(used_gb = 100.0, total_gb = 400.0), NodeDisk(used_gb = 90.0, total_gb = 100.0, role = "scratch")),
            net_rx_bps = 12_500_000, net_tx_bps = 2_500_000,
        ),
    )

    @Test fun `the summary decodes with a null group and last check`() {
        val raw = """{"id":"3","name":"edge","type":"proxy","enabled":true,"healthy":false,"active_jobs":2,"group":null,"max_jobs":null,"max_bandwidth_kbps":50000,"egress_kbps":1200,"last_health_check":null,"created_at":"2026-01-01T00:00:00.000Z","drain_requested":false,"drain_state":"none"}"""
        val decoded = json.decodeFromString<NodeSummary>(raw)
        assertNull(decoded.group); assertNull(decoded.last_health_check); assertNull(decoded.resources)
        assertEquals(50_000, decoded.max_bandwidth_kbps)
    }

    @Test fun `disabled outranks unhealthy, which outranks draining`() {
        assertEquals(NodeState.Disabled, nodeState(node(enabled = false, healthy = false)))
        assertEquals(NodeState.Unhealthy, nodeState(node(healthy = false, drainRequested = true)))
        assertEquals(NodeState.Draining, nodeState(node(drainRequested = true)))
        assertEquals(NodeState.Draining, nodeState(node(drainState = "draining")))
        assertEquals(NodeState.Drained, nodeState(node(drainRequested = true, drainState = "drained")))
        assertEquals(NodeState.Healthy, nodeState(node()))
    }

    @Test fun `drain is for transcode nodes, and resume only lifts a request an administrator made`() {
        assertEquals(DrainAction.Drain, drainAction(node(), drainSupported = true))
        assertEquals(DrainAction.Resume, drainAction(node(drainRequested = true), drainSupported = true))
        assertNull(drainAction(node(type = "proxy"), drainSupported = true))
        assertNull(drainAction(node(), drainSupported = false))
        // A node draining because it got SIGTERM keeps draining; there is nothing for resume to lift.
        assertEquals(DrainAction.Drain, drainAction(node(drainState = "draining"), drainSupported = true))
    }

    @Test fun `enable or disable follows the node's own switch`() {
        assertEquals(false, enabledAfterToggle(node(enabled = true)))
        assertEquals(true, enabledAfterToggle(node(enabled = false)))
    }

    @Test fun `load readings come from the last sample`() {
        val load = nodeLoad(node(resources = sample), now)!!
        assertEquals(37, load.cpuPercent)
        assertEquals(37, load.memoryPercent)
        assertEquals(6_144L to 16_384L, load.memoryUsedTotalMb)
        assertEquals(90, load.diskPercent)
        assertEquals(12_500_000L, load.rxBytesPerSecond)
        assertEquals(2_500_000L, load.txBytesPerSecond)
    }

    @Test fun `an unhealthy node, no sample or a stale sample shows no load`() {
        assertNull(nodeLoad(node(healthy = false, resources = sample), now))
        assertNull(nodeLoad(node(), now))
        val stale = sample.copy(sampled_at = "2026-11-14T09:50:00Z")
        assertNull(nodeLoad(node(resources = stale, lastCheck = "2026-11-14T09:59:40Z"), now))
        assertNull(nodeLoad(node(resources = sample, lastCheck = "2026-11-14T09:30:00Z"), now))
    }

    @Test fun `the worst disk sets the disk reading and unavailable disks are skipped`() {
        val disks = listOf(NodeDisk(50.0, 100.0), NodeDisk(0.0, 0.0, unavailable = true), NodeDisk(95.0, 100.0), NodeDisk(1.0, 0.0))
        assertEquals(95, worstDiskPercent(disks))
        assertNull(worstDiskPercent(listOf(NodeDisk(0.0, 0.0, unavailable = true))))
        assertNull(worstDiskPercent(emptyList()))
    }

    @Test fun `capacity reads active against the cap, and a zero cap means unlimited`() {
        assertEquals(NodeCapacity(active = 3, cap = 4, atCap = false), nodeCapacity(node(activeJobs = 3, maxJobs = 4)))
        assertEquals(NodeCapacity(active = 4, cap = 4, atCap = true), nodeCapacity(node(activeJobs = 4, maxJobs = 4)))
        assertEquals(NodeCapacity(active = 2, cap = null, atCap = false), nodeCapacity(node(activeJobs = 2, maxJobs = 0)))
        assertEquals(NodeCapacity(active = 2, cap = null, atCap = false), nodeCapacity(node(activeJobs = 2, maxJobs = null)))
    }

    @Test fun `the last check is aged from now and a node never checked says so`() {
        assertEquals(com.bloemstudios.admin.jobs.Elapsed.LessThanAMinute, lastCheckAge(node(), now))
        assertEquals(com.bloemstudios.admin.jobs.Elapsed.Hours(1), lastCheckAge(node(lastCheck = "2026-11-14T08:30:00Z"), now))
        assertNull(lastCheckAge(node(lastCheck = null), now))
    }

    @Test fun `a node needs attention when it is enabled and not healthy`() {
        assertTrue(needsAttention(node(healthy = false)))
        assertFalse(needsAttention(node(enabled = false, healthy = false)))
        assertFalse(needsAttention(node()))
    }

    @Test fun `megabits per second are shown to one decimal`() {
        assertEquals("100", megabitsPerSecond(12_500_000, java.util.Locale.ENGLISH))
        assertEquals("0.1", megabitsPerSecond(10_000, java.util.Locale.ENGLISH))
        assertEquals("0", megabitsPerSecond(0, java.util.Locale.ENGLISH))
    }
}
