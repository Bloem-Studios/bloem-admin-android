package com.bloemstudios.admin.lock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.theme.ThemeMode
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class AppLockVeilSurfaceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun appSettingsCanEnableAndDisableTheOptionalUnlock() {
        var enabled by mutableStateOf(false)
        compose.setContent {
            VeilAdminTheme { AppSettingsScreen(enabled, { enabled = it }, {}, ThemeMode.System, {}, null) }
        }
        compose.onNodeWithContentDescription("Require device unlock").assertIsOff().performClick()
        compose.onNodeWithContentDescription("Require device unlock").assertIsOn().performClick()
        compose.onNodeWithContentDescription("Require device unlock").assertIsOff()
    }

    @Test
    fun defaultGateOpensLoginWithoutLockCallbacks() {
        var locks = 0
        compose.setContent {
            VeilAdminTheme {
                AppLockScreen(onLocked = { locks++ }) { Text("login-form") }
            }
        }
        compose.onNodeWithText("login-form").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, locks) }
    }

    @Test
    fun lockSurfaceShowsLocalizedCopyAndForwardsUnlockAction() {
        val unlockRequested = AtomicBoolean(false)
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val title = targetContext.getString(R.string.app_lock_title)
        val unlock = targetContext.getString(R.string.app_lock_unlock)

        compose.setContent {
            VeilAdminTheme {
                LockScreen(
                    message = "Hardware unavailable",
                    onUnlockRequested = { unlockRequested.set(true) },
                )
            }
        }

        // The lock says what is locked, a hardware problem if there is one, and the one way forward; no wordmark,
        // eyebrow, tagline or capture caption.
        compose.onNode(
            hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        ).assertIsDisplayed()
        compose.onNodeWithText("Hardware unavailable").assertIsDisplayed()
        // The hint line sits between the title and the one button.
        compose.onNodeWithText("Unlock with your fingerprint, face or device PIN.").assertIsDisplayed()
        compose.onNodeWithText("BLOEM").assertDoesNotExist()
        compose.onNodeWithText("SECURE ADMIN").assertDoesNotExist()
        compose.onNodeWithText(unlock)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(160.dp)
            .performClick()
        compose.runOnIdle { assertTrue(unlockRequested.get()) }
    }

    /** Title and Unlock are one group in the middle, not Unlock pinned to the bottom edge. */
    @Test
    fun unlockSitsRightBelowTheTitleInTheMiddleOfTheScreen() {
        compose.setContent { VeilAdminTheme { LockScreen(message = null, onUnlockRequested = {}) } }
        val title = compose.onNodeWithText("Bloem Admin is locked").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val unlock = compose.onNodeWithText("Unlock").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val gap = with(compose.density) { 96.dp.toPx() }
        assertTrue("Unlock should follow the title closely (${unlock.top - title.bottom}px)", unlock.top - title.bottom < gap)
        val middle = (unlock.bottom + title.top) / 2f
        assertTrue("the group should be centred (middle $middle of ${root.height})", kotlin.math.abs(middle - root.height / 2f) < root.height * 0.15f)
    }

    @Test
    fun shortLargeTextSurfaceKeepsCriticalActionsReachable() {
        val unlockRequested = AtomicBoolean(false)
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val title = targetContext.getString(R.string.app_lock_title)
        val hardwareMessage = targetContext.getString(R.string.app_lock_hardware_unavailable)
        val unlock = targetContext.getString(R.string.app_lock_unlock)

        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale = 2f),
            ) {
                Box(Modifier.size(width = 360.dp, height = 420.dp)) {
                    VeilAdminTheme {
                        LockScreen(
                            message = hardwareMessage,
                            onUnlockRequested = { unlockRequested.set(true) },
                        )
                    }
                }
            }
        }

        compose.onNode(
            hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(hardwareMessage).performScrollTo().assertIsDisplayed()
        // The page scrolls, so Unlock is reached by scrolling to it.
        compose.onNodeWithText(unlock)
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle { assertTrue(unlockRequested.get()) }
    }
}
