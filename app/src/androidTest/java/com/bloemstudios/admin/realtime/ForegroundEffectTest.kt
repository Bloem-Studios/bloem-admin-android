package com.bloemstudios.admin.realtime

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.awaitCancellation
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.ui.shell.AdminShell
import com.bloemstudios.admin.ui.shell.AdminShellContext
import com.bloemstudios.admin.ui.shell.singleServerDestinations
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import com.bloemstudios.admin.ui.theme.LocalReducedMotionOverride
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForegroundEffectTest {
    @get:Rule val compose = createComposeRule()
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }
    @Test fun startedOwnsWorkAndBackgroundCancelsWithoutAnyUnlockGate() {
        lateinit var owner: Owner
        var starts = 0; var stops = 0
        compose.runOnIdle { owner = Owner(); owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ForegroundEffect(Unit) { starts++; try { awaitCancellation() } finally { stops++ } }
            }
        }
        compose.runOnIdle { assertEquals(0,starts); owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { assertEquals(1,starts); owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { assertEquals(1,stops); owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { assertEquals(2,starts); owner.registry.currentState = Lifecycle.State.DESTROYED }
        compose.runOnIdle { assertEquals(2,stops) }
    }
    @Test fun completedInitializationKeepsDraftAndInterruptedInitializationResumes() {
        lateinit var owner: Owner
        var reads = 0; var draft = ""; var ready = false
        compose.runOnIdle { owner = Owner(); owner.registry.currentState = Lifecycle.State.STARTED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ForegroundOnceEffect(Unit) { reads++; if (!ready) awaitCancellation(); draft = "server value" }
            }
        }
        compose.runOnIdle { assertEquals(1,reads); owner.registry.currentState = Lifecycle.State.CREATED; ready = true }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { assertEquals(2,reads); draft = "unsaved edit"; owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { assertEquals(2,reads); assertEquals("unsaved edit",draft) }
    }
    @Test fun animatedDestinationsShareConnectionUntilLastDisposal() = overlap(false)
    @Test fun reducedMotionDestinationsNeverOverlapConnections() = overlap(true)

    private fun overlap(reduced: Boolean) {
        val active = AtomicInteger(); val attempts = AtomicInteger(); val maximum = AtomicInteger()
        val api = ApiClient("https://fixture.invalid", null)
        val transport = LiveTransport {
            attempts.incrementAndGet()
            maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }
            try { awaitCancellation() } finally { active.decrementAndGet() }
        }
        // The socket is shared by every destination and closes shortly after the last of them (ten seconds in the app).
        val live = LiveConnection(api, transport, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            config = LiveConfig(leaveGraceMillis = 300))
        val repo = LiveSessionsRepository(api, live, { emptyList() })
        var selected by mutableStateOf("overview.dashboard")
        var visible by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotionOverride provides reduced) {
                VeilAdminTheme {
                    if (visible) AdminShell(AdminShellContext("Fixture",false),singleServerDestinations(false),selected,
                        { selected=it },false,{}) { destination ->
                        ForegroundEffect(repo,destination) { repo.observe() }
                    }
                }
            }
        }
        try {
            compose.waitUntil { active.get()==1 }
            compose.runOnIdle { selected="live.streams" }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(1,maximum.get()); if (!reduced) assertEquals(1,attempts.get()); visible=false }
            compose.waitUntil { active.get()==0 }
        } finally { repo.close(); live.close(); api.close() }
    }

}
