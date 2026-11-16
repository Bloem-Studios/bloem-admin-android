package com.bloemstudios.admin

import java.util.concurrent.atomic.AtomicLong

/** Monotonic request identity used by reusable ViewModels across client/filter changes. */
class LatestRequestGate {
    private val generation = AtomicLong(0)

    fun current(): Long = generation.get()

    fun begin(): Long = generation.incrementAndGet()

    fun isCurrent(requestGeneration: Long): Boolean = generation.get() == requestGeneration
}
