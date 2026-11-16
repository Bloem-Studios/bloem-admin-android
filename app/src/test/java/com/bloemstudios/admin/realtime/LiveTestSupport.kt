package com.bloemstudios.admin.realtime

import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

internal fun testApi(onRequest: (String) -> Unit = {}) = ApiClient("https://example.com", "fixture", MockEngine { request ->
    onRequest(request.url.encodedPath)
    respond("""{"items":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
})

/** A device the tests drive by hand. */
internal class FakeEnvironment : LiveEnvironment {
    override val foreground = MutableStateFlow(true)
    val network = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val networkChanges = network
}

/**
 * A scripted connection. [script] runs once per attempt (1-based) with that attempt's control; by default an attempt is
 * accepted at once for every wanted channel, answers with a snapshot each, and then stays quiet.
 */
internal class FakeLiveTransport(private val now: () -> Long = { 0L }) : LiveTransport {
    var attempts = 0
    var active = 0
    val times = mutableListOf<Long>()
    val controls = mutableListOf<LiveControl>()
    /** A healthy transport proves it is alive on a timer, as the real one does by pinging; the watchdog tests turn it off. */
    var keepAliveMillis: Long? = 20_000
    var script: suspend (LiveControl, Int) -> Unit = { control, _ -> accept(control); quiet(control, keepAliveMillis) }

    override suspend fun connect(control: LiveControl) {
        attempts++; active++; times += now(); controls += control
        try { script(control, attempts) } finally { active-- }
    }

    companion object {
        /** Stays open with nothing to say until cancelled, proving it is alive on a timer as the real transport does. */
        suspend fun quiet(control: LiveControl, keepAliveMillis: Long? = 20_000): Nothing = coroutineScope {
            keepAliveMillis?.let { every -> launch { while (true) { delay(every); control.onActivity() } } }
            awaitCancellation()
        }
        /** What a healthy server does after the hello: take every wanted channel and send its snapshot. */
        fun accept(control: LiveControl, rejected: Map<String, String> = emptyMap()) {
            val wanted = control.wanted() - rejected.keys
            control.onSubscribed(wanted, rejected)
            wanted.forEach { control.onSnapshot(it) }
        }
        fun fail(failure: SessionFailure): Nothing = throw SessionTransportException(failure)
    }
}
