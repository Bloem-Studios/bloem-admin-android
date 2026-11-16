package com.bloemstudios.admin.dashboard

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

// The numbers behind the server-health resource cells (CPU, memory, network, the fullest disk, GPU), kept free of Compose
// and Android resources so the choices can be tested. The words are the screen's.

/** How loud a figure is: normal text, warning, or failure. */
enum class FigureTone { Normal, Warn, Error }

/** The disk with the highest share used among those that could be read and have a size; null when there is none. */
fun fullestDisk(disks: List<DiskStats>): DiskStats? =
    disks.filter { !it.unavailable && it.total_gb > 0.0 }.maxByOrNull { it.used_gb / it.total_gb }

/** Share of the disk in use, whole percent, kept within 0 to 100. */
fun diskPercent(disk: DiskStats): Int =
    if (disk.total_gb <= 0.0) 0 else (disk.used_gb / disk.total_gb * 100).roundToInt().coerceIn(0, 100)

/** A disk is worth a look from 85 percent and is a failure risk from 95. */
fun diskTone(percent: Int): FigureTone = when {
    percent >= 95 -> FigureTone.Error
    percent >= 85 -> FigureTone.Warn
    else -> FigureTone.Normal
}

/** The GPUs that report real measurements; the web leaves out a device whose source is empty or "unavailable". */
fun measuredGpus(gpus: List<GpuStats>): List<GpuStats> =
    gpus.filter { it.source.trim().lowercase().let { source -> source.isNotEmpty() && source != "unavailable" } }

/** How busy the whole GPU is, 0 to 100; null when the host does not report a whole-GPU figure. */
fun gpuBusyPercent(gpu: GpuStats): Int? = gpu.total_busy_pct?.coerceIn(0, 100)

/** "412 GB", or "1.5 TB" from 1000 GB. */
fun formatGigabytes(gigabytes: Double, locale: Locale = Locale.getDefault()): String {
    val value = if (gigabytes.isFinite()) gigabytes.coerceAtLeast(0.0) else 0.0
    return if (value >= 1000.0) {
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1; minimumFractionDigits = 0 }.format(value / 1000.0) + " TB"
    } else {
        NumberFormat.getIntegerInstance(locale).format(value) + " GB"
    }
}

/**
 * "5.2 MB" as a rate that cannot break across lines: a no-break space between number and unit, and a word joiner after
 * the slash, where a line may otherwise break ("MB/" then "s").
 */
fun perSecond(size: String): String = size.replace(' ', ' ') + "/⁠s"
