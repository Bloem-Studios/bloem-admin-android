package com.bloemstudios.admin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Drops duplicate intents while the original operation is still unresolved. */
class InFlightOperations {
    private val active = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = active

    suspend fun run(key: String, action: suspend () -> Unit) {
        runResult(key, Unit) { action() }
    }

    /** Conflicts are checked atomically with acceptance; the guard lasts through reconciliation. */
    suspend fun <T> runResult(
        key: String,
        busy: T,
        conflicts: (String) -> Boolean = { it == key },
        action: suspend () -> T
    ): T {
        while (true) {
            val current = active.value
            if (current.any(conflicts)) return busy
            if (active.compareAndSet(current, current + key)) break
        }
        return try { action() } finally { active.update { it - key } }
    }
}
