package com.bloemstudios.admin.servers

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.graphics.toPixelMap
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bloemstudios.admin.R
import com.bloemstudios.admin.auth.AuthProvider
import com.bloemstudios.admin.auth.AuthProviders
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddServerFormTest {
    @get:Rule
    val compose = createComposeRule()

    private val corp = AuthProvider("26", "Corp SSO", "oauth", false, "26", "/api/v2/auth/oauth/26/native/start")
    private val wave = AuthProvider("27", "Example Portal Login", "oauth", true, "27", "/api/v2/auth/oauth/27/native/start")
    private val local = AuthProvider("local", "Silo account", "credentials", false)

    private fun setStepTwo(
        providers: AuthProviders,
        error: @Composable () -> String? = { null },
        isConnecting: Boolean = false,
        connectingProviderId: String? = null,
        onPassword: () -> Unit = {},
        onProvider: (AuthProvider) -> Unit = {},
        onChangeServer: () -> Unit = {},
    ) = compose.setContent {
        VeilAdminTheme {
            SignInOptionsStep(
                serverName = "Bloem West", providers = providers,
                username = "admin", onUsernameChange = {}, password = "x", onPasswordChange = {},
                errorMessage = error(), isConnecting = isConnecting, connectingProviderId = connectingProviderId,
                compact = true, onPasswordSignIn = onPassword, onProviderSignIn = onProvider,
                onChangeServer = onChangeServer, onDismiss = {},
            )
        }
    }

    @Test
    fun loginRetainsErrorAndAllowsPasswordVisibilityAtLargeFontScale() {
        var username by mutableStateOf("")
        var password by mutableStateOf("")
        compose.setContent {
            // Raise only the font scale. Keeping the device's own density matters: a fixed density of 2
            // on a density-1 emulator (CI's) halves the screen to 160x320 dp, leaving no room once the
            // keyboard is up.
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                VeilAdminTheme {
                    SignInOptionsStep(
                        serverName = "Bloem West", providers = AuthProviders(true, listOf(local)),
                        username = username, onUsernameChange = { username = it },
                        password = password, onPasswordChange = { password = it },
                        errorMessage = "Invalid credentials", isConnecting = false, connectingProviderId = null,
                        compact = true, onPasswordSignIn = {}, onProviderSignIn = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Sign in").assertIsNotEnabled()
        compose.onNodeWithText("Username").performScrollTo().performTextInput("admin")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("test-value")
        compose.waitForIdle()
        compose.onNodeWithText("Show").performScrollTo().performClick()
        compose.onNodeWithText("Hide").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Invalid credentials").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun addressStepAsksForTheAddressAndContinues() {
        var continues = 0
        var url by mutableStateOf("")
        compose.setContent {
            VeilAdminTheme {
                AddServerAddressStep(url, { url = it }, null, false, true, {}, { continues += 1 })
            }
        }
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Server URL").performTextInput("https://example.test")
        compose.onNodeWithText("Continue").assertIsEnabled().performClick()
        compose.onNodeWithText("Username").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, continues) }
    }

    @Test
    fun addressStaysEditableWhileItIsChecked() {
        var url by mutableStateOf("example.test")
        compose.setContent {
            VeilAdminTheme {
                AddServerAddressStep(url, { url = it }, null, true, true, {}, {})
            }
        }
        compose.onNodeWithText("Checking server…").assertIsNotEnabled()
        compose.onNodeWithText("Server URL").assertIsEnabled().performTextReplacement("example.org")
        compose.runOnIdle { assertEquals("example.org", url) }
    }

    @Test
    fun stepTwoShowsPasswordFieldsAndTheProviderButton() {
        var provider: AuthProvider? = null
        var changes = 0
        setStepTwo(AuthProviders(true, listOf(local, corp)), onProvider = { provider = it }, onChangeServer = { changes += 1 })
        compose.onNodeWithText("Username").assertIsDisplayed()
        compose.onNodeWithText("Password").assertIsDisplayed()
        compose.onNodeWithText("Sign in").assertIsDisplayed()
        compose.onNodeWithText("Choose how to sign in to Bloem West").assertIsDisplayed()
        compose.onNodeWithText("Sign in with Corp SSO").assertIsDisplayed().performClick()
        compose.onNodeWithText("Sign in with Silo account").assertDoesNotExist()
        compose.onNodeWithText("Change server").performClick()
        compose.runOnIdle {
            assertEquals("26", provider?.id)
            assertEquals(1, changes)
        }
    }

    @Test
    fun stepTwoListsTheDefaultProviderFirst() {
        setStepTwo(AuthProviders(false, listOf(corp, wave)))
        val ys = listOf("Sign in with Example Portal Login", "Sign in with Corp SSO").map {
            compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("default provider is listed first", ys[0] < ys[1])
    }

    @Test
    fun stepTwoWithoutPasswordLoginHidesThePasswordFields() {
        setStepTwo(AuthProviders(false, listOf(corp)))
        compose.onNodeWithText("Sign in with Corp SSO").assertIsDisplayed()
        compose.onNodeWithText("Username").assertDoesNotExist()
        compose.onNodeWithText("Password").assertDoesNotExist()
        compose.onNodeWithText("Sign in").assertDoesNotExist()
    }

    @Test
    fun stepTwoWithNoUsableOptionsSaysSo() {
        // A network provider and an oauth provider without a native start path are not offered.
        val network = AuthProvider("n", "Network", "network", false)
        val noStart = AuthProvider("o", "No start", "oauth", false)
        setStepTwo(AuthProviders(false, listOf(network, noStart)))
        compose.onNodeWithText("This server's sign-in options aren't available in this app").assertIsDisplayed()
        compose.onNodeWithText("Username").assertDoesNotExist()
        compose.onNodeWithText("Sign in with No start").assertDoesNotExist()
        compose.onNodeWithText("Change server").assertIsDisplayed()
    }

    @Test
    fun providerSignInInProgressFreezesTheFormAndShowsProgress() {
        setStepTwo(AuthProviders(true, listOf(corp)), isConnecting = true, connectingProviderId = "26")
        compose.onNodeWithText("Signing in…").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Username").assertIsNotEnabled()
        compose.onNodeWithText("Sign in").assertIsNotEnabled()
    }

    @Test
    fun everyFailureShowsItsCopyFromTheSpec() {
        val cases = listOf(
            Triple(AddServerFailure.OAUTH_FAILED, "not_permitted", "This account isn't allowed to sign in to this server"),
            Triple(AddServerFailure.OAUTH_FAILED, "account_required", "No account on this server uses that sign-in. Ask the server's owner for an invitation."),
            Triple(AddServerFailure.OAUTH_FAILED, "email_in_use", "An account with that email already exists. Sign in with its password instead."),
            Triple(AddServerFailure.OAUTH_FAILED, "identity_linked_elsewhere", "That sign-in is linked to a different account"),
            Triple(AddServerFailure.OAUTH_FAILED, "account_disabled", "This account is disabled"),
            Triple(AddServerFailure.OAUTH_FAILED, "provider_unavailable", "Corp SSO isn't available right now. Try again later."),
            Triple(AddServerFailure.OAUTH_FAILED, "state_invalid", "The sign-in took too long or was interrupted. Try again."),
            Triple(AddServerFailure.OAUTH_FAILED, "session_expired", "The sign-in took too long or was interrupted. Try again."),
            Triple(AddServerFailure.OAUTH_FAILED, "login_failed", "Sign-in didn't finish. Try again."),
            Triple(AddServerFailure.OAUTH_FAILED, "already_linked", "Sign-in didn't finish. Try again."),
            Triple(AddServerFailure.OAUTH_FAILED, "<b>surprise</b>", "Sign-in didn't finish. Try again."),
            Triple(AddServerFailure.OAUTH_FAILED, null, "Sign-in didn't finish. Try again."),
            Triple(AddServerFailure.OAUTH_UNTRUSTED, null, "The sign-in response didn't come from this server. Try again."),
            Triple(AddServerFailure.NO_BROWSER, null, "Install or turn on a web browser to sign in with Corp SSO"),
            Triple(AddServerFailure.NO_SUPPORTED_SIGN_IN, null, "This server's sign-in options aren't available in this app"),
        )
        var index by mutableStateOf(0)
        setStepTwo(AuthProviders(false, listOf(corp)), error = {
            val (reason, detail, _) = cases[index]
            addServerFailureMessage(reason, detail, "Corp SSO")
        })
        cases.indices.forEach { i ->
            compose.runOnIdle { index = i }
            compose.onNodeWithText(cases[i].third).performScrollTo().assertIsDisplayed()
        }
        compose.runOnIdle { index = 0 }
    }

    @Test
    fun aCancelledSignInShowsNoMessage() {
        compose.setContent { assertEquals(null, addServerFailureMessage(AddServerFailure.OAUTH_CANCELLED, null, "Corp SSO")) }
        compose.waitForIdle()
    }

    @Test
    fun aRawServerReasonIsNeverRendered() {
        setStepTwo(AuthProviders(false, listOf(corp)), error = { addServerFailureMessage(AddServerFailure.OAUTH_FAILED, "raw_server_text", "Corp SSO") })
        compose.onNodeWithText("raw_server_text", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Sign-in didn't finish. Try again.").assertIsDisplayed()
    }

    @Test
    fun reLoginShowsStepTwoWithoutChangeServerOrCancel() {
        compose.setContent {
            VeilAdminTheme {
                SignInOptionsStep(
                    serverName = "Bloem West", providers = AuthProviders(true, listOf(local, corp)),
                    username = "", onUsernameChange = {}, password = "", onPasswordChange = {},
                    errorMessage = null, isConnecting = false, connectingProviderId = null, compact = true,
                    onPasswordSignIn = {}, onProviderSignIn = {},
                    title = "Sign in", guidance = "Your session has expired.", rememberedProviderId = "26",
                    passwordActionLabel = "Sign in",
                )
            }
        }
        compose.onNodeWithText("Your session has expired.").assertIsDisplayed()
        compose.onNodeWithText("Sign in with Corp SSO").assertIsDisplayed()
        compose.onNodeWithText("Username").assertIsDisplayed()
        compose.onNodeWithText("Server URL").assertDoesNotExist()
        compose.onNodeWithText("Change server").assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        val titlePixels = compose.onAllNodesWithText("Sign in")[0].captureToImage().toPixelMap()
        var readablePixels = 0
        for (y in 0 until titlePixels.height) for (x in 0 until titlePixels.width) {
            val pixel = titlePixels[x, y]
            if (pixel.red > 0.6f && pixel.green > 0.6f && pixel.blue > 0.6f) readablePixels++
        }
        assertTrue("Login heading must have light text against the dark background", readablePixels > 20)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(instrumentation.targetContext.getExternalFilesDir(null), "management-login.png")
            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun reLoginListsTheRememberedProviderFirstAndMakesItPrimary() {
        val order = listOf(wave, corp)
        fun tops(remembered: String?): List<Float> {
            compose.setContent {
                VeilAdminTheme {
                    SignInOptionsStep(
                        serverName = "Bloem West", providers = AuthProviders(false, order),
                        username = "", onUsernameChange = {}, password = "", onPasswordChange = {},
                        errorMessage = null, isConnecting = false, connectingProviderId = null, compact = true,
                        onPasswordSignIn = {}, onProviderSignIn = {}, rememberedProviderId = remembered,
                    )
                }
            }
            return listOf("Sign in with Example Portal Login", "Sign in with Corp SSO").map {
                compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
            }
        }
        val ys = tops("26")
        assertTrue("remembered provider is listed before the default", ys[1] < ys[0])
    }

    @Test
    fun reLoginFallsBackToServerOrderWhenTheRememberedProviderIsGone() {
        compose.setContent {
            VeilAdminTheme {
                SignInOptionsStep(
                    serverName = "Bloem West", providers = AuthProviders(false, listOf(corp, wave)),
                    username = "", onUsernameChange = {}, password = "", onPasswordChange = {},
                    errorMessage = null, isConnecting = false, connectingProviderId = null, compact = true,
                    onPasswordSignIn = {}, onProviderSignIn = {}, rememberedProviderId = "removed",
                )
            }
        }
        val ys = listOf("Sign in with Example Portal Login", "Sign in with Corp SSO").map {
            compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("default provider first when the remembered one is not offered", ys[0] < ys[1])
    }

    /**
     * A phone in landscape is about 411 dp high. The footer (Continue, Cancel and the security note) used to sit
     * outside the scrolling part and took most of that, which left the address field a sliver you could not type in.
     * The whole step scrolls now: the field is whole, and so is every button once scrolled to.
     */
    @Test
    fun addressFieldIsWholeAndButtonsAreReachableOnAShortWindow() {
        compose.setContent {
            VeilAdminTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.requiredSize(640.dp, 300.dp)) {
                    AddServerAddressStep("", {}, null, false, false, {}, {})
                }
            }
        }
        val field = compose.onNodeWithText("Server URL").assertIsDisplayed().fetchSemanticsNode()
        assertEquals("the address field is cut off", field.size.height, field.boundsInRoot.height.toInt())
        assertTrue("the address field is too short to type in", field.boundsInRoot.height >= 48f)
        compose.onNodeWithText("Continue").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("HTTPS required. Credentials stored encrypted on this device.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun signInOptionsAreReachableOnAShortWindow() {
        compose.setContent {
            VeilAdminTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.requiredSize(640.dp, 300.dp)) {
                    SignInOptionsStep(
                        serverName = "Bloem West", providers = AuthProviders(true, listOf(corp)),
                        username = "", onUsernameChange = {}, password = "", onPasswordChange = {},
                        errorMessage = null, isConnecting = false, connectingProviderId = null, compact = false,
                        onPasswordSignIn = {}, onProviderSignIn = {}, onChangeServer = {}, onDismiss = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Sign in with Corp SSO").assertIsDisplayed()
        listOf("Username", "Password", "Sign in", "Change server", "Cancel").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
    }

    /** With room to spare the footer still sits at the bottom edge, as it did before the step scrolled as one. */
    @Test
    fun footerStaysAtTheBottomWhenThereIsRoom() {
        compose.setContent {
            VeilAdminTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.testTag("window").requiredSize(360.dp, 800.dp)) {
                    AddServerAddressStep("", {}, null, false, true, {}, {})
                }
            }
        }
        val window = compose.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
        val note = compose.onNodeWithText("HTTPS required. Credentials stored encrypted on this device.")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val margin = with(compose.density) { 48.dp.toPx() }
        assertTrue("the footer should sit at the bottom edge (${note.bottom} of ${window.bottom})", window.bottom - note.bottom < margin)
    }
}
