package com.bloemstudios.admin.dashboard

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Invalid origin, no ServerStore/credentials: real sampled GETs held at a controllable engine. */
@RunWith(AndroidJUnit4::class)
class SampledRefreshLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }

    @Test fun aggregateManualSamplesCancelOnStopAndRequireAnotherExplicitRefresh() = checkRefresh(false)
    @Test fun individualManualSamplesCancelOnStopAndRequireAnotherExplicitRefresh() = checkRefresh(true)

    private fun checkRefresh(individual: Boolean) {
        val fixture = SampledRefreshFixture(individual)
        lateinit var owner: Owner
        compose.runOnIdle { owner = Owner(); owner.registry.currentState = Lifecycle.State.STARTED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                VeilAdminTheme {
                    fixture.Render()
                }
            }
        }
        try {
            if (individual) compose.waitUntil(5_000) { fixture.statsReads.get() == 1 && fixture.hostReads.get() == 1 && fixture.model.errorMessage.value != null }
            val refreshLabel = if (individual) "Try again" else "Refresh"
            compose.onNodeWithText(refreshLabel).performClick()
            compose.waitUntil(5_000) { fixture.held.get() == 2 }
            assertEquals(1, fixture.manualStatsReads.get())
            if (!individual) compose.onNodeWithText("Refresh").assertIsNotEnabled()
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
            compose.waitUntil(5_000) { fixture.canceled.get() == 2 }
            fixture.releaseHeldReads()
            compose.waitForIdle()
            assertNull(fixture.model.stats.value)
            assertNull(fixture.model.hostStats.value)
            if (!individual) compose.onNodeWithText("Refresh").assertIsEnabled()

            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            if (individual) compose.waitUntil(5_000) { fixture.statsReads.get() == 3 && fixture.hostReads.get() == 3 }
            compose.waitForIdle()
            assertEquals("Canceled manual intent must not replay on START", 1, fixture.manualStatsReads.get())
            assertNull(fixture.model.stats.value)
            compose.onNodeWithText(refreshLabel).performClick()
            compose.waitUntil(5_000) { fixture.model.stats.value?.total_items == 42 && fixture.model.hostStats.value != null }
            assertEquals(2, fixture.manualStatsReads.get())
            if (!individual) compose.onNodeWithText("Refresh").assertIsEnabled()
        } finally {
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.DESTROYED }
            fixture.close()
        }
    }
}
