package com.bloemstudios.admin.dashboard

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class HostFiguresTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `resources decode disks and gpus when the server sends them`() {
        val resources = json.decodeFromString<SystemResources>(
            """{"available":true,"stale":false,"sampled_at":"2026-11-14T10:00:00Z",
               "system":{"cpu_pct":34,"load1":1.2,"cores":8,"mem_used_mb":12000,"mem_total_mb":32000,"net_rx_bps":80000000,"net_tx_bps":8000000,
                 "disks":[{"path":"/media","role":"media","used_gb":412.5,"total_gb":500.0,"scratch":false},{"role":"scratch","used_gb":10,"total_gb":100,"scratch":true}]},
               "gpu":[{"device":"NVIDIA T400","vendor":"nvidia","sessions":2,"source":"nvml","total_busy_pct":41,"video_busy_pct":30,"render_busy_pct":12,"vram_used_mb":2100,"vram_total_mb":4096}]}""",
        )
        assertEquals(2, resources.system?.disks?.size)
        assertEquals("NVIDIA T400", resources.gpu.single().device)
        assertEquals(41, resources.gpu.single().total_busy_pct)
    }

    @Test fun `resources without disks or gpus still decode`() {
        val resources = json.decodeFromString<SystemResources>("""{"available":true,"system":{"cpu_pct":3.5}}""")
        assertTrue(resources.system?.disks.orEmpty().isEmpty())
        assertTrue(resources.gpu.isEmpty())
    }

    private fun disk(used: Double, total: Double, unavailable: Boolean = false, role: String? = null) =
        DiskStats(role = role, used_gb = used, total_gb = total, unavailable = unavailable)

    @Test fun `the fullest disk is the one with the highest share used`() {
        val disks = listOf(disk(100.0, 1000.0), disk(90.0, 100.0), disk(400.0, 500.0))
        assertEquals(90.0, fullestDisk(disks)?.used_gb)
    }

    @Test fun `a disk that cannot be read or has no size is never the fullest`() {
        assertEquals(10.0, fullestDisk(listOf(disk(99.0, 100.0, unavailable = true), disk(5.0, 0.0), disk(10.0, 100.0)))?.used_gb)
        assertNull(fullestDisk(emptyList()))
        assertNull(fullestDisk(listOf(disk(1.0, 0.0))))
    }

    @Test fun `disk percent rounds and stays within zero to one hundred`() {
        assertEquals(83, diskPercent(disk(412.5, 500.0)))
        assertEquals(100, diskPercent(disk(600.0, 500.0)))
        assertEquals(0, diskPercent(disk(-3.0, 500.0)))
    }

    @Test fun `a disk warns from 85 percent and fails from 95`() {
        assertEquals(FigureTone.Normal, diskTone(84))
        assertEquals(FigureTone.Warn, diskTone(85))
        assertEquals(FigureTone.Warn, diskTone(94))
        assertEquals(FigureTone.Error, diskTone(95))
    }

    private fun gpu(source: String = "nvml", total: Int? = 41, video: Int? = null, render: Int? = null) =
        GpuStats(device = "gpu", source = source, total_busy_pct = total, video_busy_pct = video, render_busy_pct = render)

    @Test fun `only measured gpus are shown`() {
        val shown = measuredGpus(listOf(gpu("nvml"), gpu(""), gpu("unavailable"), gpu(" Unavailable "), gpu("intel_gpu_top")))
        assertEquals(listOf("nvml", "intel_gpu_top"), shown.map { it.source })
    }

    @Test fun `gpu busy is the whole gpu figure, clamped`() {
        assertEquals(41, gpuBusyPercent(gpu(total = 41)))
        assertEquals(100, gpuBusyPercent(gpu(total = 140)))
        assertNull(gpuBusyPercent(gpu(total = null, video = 30, render = 12)))
    }

    @Test fun `sizes in gigabytes switch to terabytes from 1000`() {
        assertEquals("412 GB", formatGigabytes(412.4, Locale.US))
        assertEquals("1.5 TB", formatGigabytes(1500.0, Locale.US))
        assertEquals("0 GB", formatGigabytes(-1.0, Locale.US))
    }

    @Test fun `a rate never breaks inside its unit`() {
        val rate = perSecond("5.2 MB")
        assertEquals("5.2 MB/⁠s", rate)
        assertTrue(!rate.contains(' '))
    }
}
