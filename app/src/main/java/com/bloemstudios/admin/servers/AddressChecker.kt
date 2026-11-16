package com.bloemstudios.admin.servers

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bloemstudios.admin.network.InvalidServerOrigin
import com.bloemstudios.admin.network.ServerOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** How long typing must pause before a finished-looking address is checked by itself. */
internal const val AUTO_CHECK_PAUSE_MILLIS = 700L

/**
 * Whether a typed address looks finished enough to check without a tap: a valid HTTPS origin whose
 * host ends in a top-level label of two or more characters with a letter (`media.example.com`).
 * Hosts such as `localhost` and IP addresses are still checked, but only on Continue.
 */
internal fun isFinishedAddress(raw: String): Boolean {
    val host = try {
        ServerOrigin.parseTyped(raw).resolve("/").host
    } catch (_: InvalidServerOrigin) {
        return false
    }
    val topLabel = host.substringAfterLast('.', missingDelimiterValue = "")
    return topLabel.length >= 2 && topLabel.any(Char::isLetter)
}

/**
 * Step one's address check. A finished-looking address is checked by itself once typing pauses;
 * Continue checks at once. Any edit cancels the check in flight, so the answer for an older
 * address can never move the form on.
 */
internal class AddressChecker(
    private val scope: CoroutineScope,
    private val discover: suspend (String) -> DiscoveryResult,
    private val onResult: (DiscoveryResult) -> Unit,
    private val pauseMillis: Long = AUTO_CHECK_PAUSE_MILLIS,
) {
    /** True while the server is being asked; not during the pause before an automatic check. */
    var isChecking by mutableStateOf(false)
        private set

    private var job: Job? = null

    fun addressChanged(address: String) {
        cancel()
        if (isFinishedAddress(address)) start(address, pause = true)
    }

    fun checkNow(address: String) {
        cancel()
        start(address, pause = false)
    }

    fun cancel() {
        job?.cancel()
        job = null
        isChecking = false
    }

    private fun start(address: String, pause: Boolean) {
        job = scope.launch {
            val self = coroutineContext[Job]
            if (pause) delay(pauseMillis)
            isChecking = true
            val result = try {
                discover(address)
            } finally {
                if (job === self) isChecking = false
            }
            ensureActive()
            if (job === self) {
                job = null
                onResult(result)
            }
        }
    }
}
