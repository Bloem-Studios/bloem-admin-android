package com.bloemstudios.admin.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the first page (Now playing) has shown real data from a server in this run of the app. The notification permission
 * is asked for only after this, never straight after sign-in: the person has seen something useful first.
 */
object FirstDataLoad {
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    fun mark() {
        _loaded.value = true
    }
}
