package com.bloemstudios.admin

import com.bloemstudios.admin.network.ErrorText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** An invocation receipt; dropped intents never masquerade as confirmed writes. */
enum class ActionResult { Success, Failure, Busy }

/** Load publication cannot acknowledge an action; only the latest accepted action owns its banner. */
class ActionFeedback {
    private var accepted = 0L
    private val _loadError = MutableStateFlow<ErrorText?>(null)
    val loadError: StateFlow<ErrorText?> = _loadError
    private val _actionError = MutableStateFlow<ErrorText?>(null)
    val actionError: StateFlow<ErrorText?> = _actionError
    private val _errorMessage = MutableStateFlow<ErrorText?>(null)
    val errorMessage: StateFlow<ErrorText?> = _errorMessage
    @Synchronized fun begin(): Long = ++accepted
    @Synchronized fun load(error: ErrorText?) {
        _loadError.value = error
        _errorMessage.value = _actionError.value ?: error
    }
    @Synchronized fun publish(generation: Long, error: ErrorText?) {
        if (generation != accepted) return
        _actionError.value = error
        _errorMessage.value = error ?: _loadError.value
    }
    @Synchronized fun dismiss() {
        _actionError.value = null
        _errorMessage.value = _loadError.value
    }
}
