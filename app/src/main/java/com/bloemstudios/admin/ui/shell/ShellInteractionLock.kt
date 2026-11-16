package com.bloemstudios.admin.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf

internal fun interface ShellInteractionLease {
    fun release()
}

internal interface ShellInteractionLock {
    val isLocked: State<Boolean>
    fun acquire(): ShellInteractionLease
}

internal class ShellInteractionLockController : ShellInteractionLock {
    private val lockedState = mutableStateOf(false)
    private var leaseCount = 0

    override val isLocked: State<Boolean> = lockedState

    override fun acquire(): ShellInteractionLease {
        leaseCount += 1
        lockedState.value = true
        var released = false
        return ShellInteractionLease {
            if (!released) {
                released = true
                leaseCount -= 1
                lockedState.value = leaseCount > 0
            }
        }
    }
}

internal object NoOpShellInteractionLock : ShellInteractionLock {
    override val isLocked: State<Boolean> = mutableStateOf(false)
    override fun acquire(): ShellInteractionLease = ShellInteractionLease {}
}

internal val LocalShellInteractionLock = staticCompositionLocalOf<ShellInteractionLock> {
    NoOpShellInteractionLock
}

@Composable
internal fun ShellInteractionLockEffect(active: Boolean) {
    val lock = LocalShellInteractionLock.current
    DisposableEffect(lock, active) {
        val lease = if (active) lock.acquire() else null
        onDispose { lease?.release() }
    }
}
