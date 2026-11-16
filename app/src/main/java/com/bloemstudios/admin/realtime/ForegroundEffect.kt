package com.bloemstudios.admin.realtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Automatic reads, polling and observation belong to the visible STARTED lifetime.
 *
 * The block runs on the main thread. `repeatOnLifecycle` launches it from the caller's scope, so
 * it keeps the LaunchedEffect's dispatcher; where that is not the main dispatcher (the Compose
 * test rule's unconfined one) a read resumes on the HTTP engine's thread, publishes the view
 * model's flows there and the collectors write snapshot state off the main thread. A LazyColumn
 * measuring at that moment sees its item count change between two reads and throws
 * IndexOutOfBoundsException.
 */
@Composable
fun ForegroundEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(block)
    LaunchedEffect(owner, *keys) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.Main.immediate) { latest() }
        }
    }
}

/** Initialization resumes if canceled, but never replaces completed drafts/pages on resume. */
@Composable
fun ForegroundOnceEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(block)
    LaunchedEffect(owner, *keys) {
        var complete = false
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!complete) {
                withContext(Dispatchers.Main.immediate) { latest() }
                complete = true
            }
        }
    }
}

/** Explicit GET intents are canceled on STOP and never queued/replayed on START. */
class ForegroundReadLauncher internal constructor(
    private val lifecycle: Lifecycle,
    private val scope: CoroutineScope
) {
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(Dispatchers.Main.immediate) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
        val readJob = coroutineContext.job
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) readJob.cancel()
        }
        lifecycle.addObserver(observer)
        try { coroutineScope { block() } }
        finally { lifecycle.removeObserver(observer) }
    }
}

@Composable
fun rememberForegroundReadLauncher(): ForegroundReadLauncher {
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    return remember(owner, scope) { ForegroundReadLauncher(owner.lifecycle, scope) }
}
