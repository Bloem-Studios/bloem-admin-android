package com.bloemstudios.admin.navigation

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.AdminApp
import com.bloemstudios.admin.links.DeepLink
import com.bloemstudios.admin.links.DeepLinkInbox
import com.bloemstudios.admin.lock.AppLockPreferences
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.SensitiveViewModelStoreRegistry
import com.bloemstudios.admin.servers.ServerStore
import com.bloemstudios.admin.ui.theme.ThemePreferences
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * A link that arrives (from an alert, the widget, the tile or a shortcut) opens a saved server through the admin gate, a
 * link for a server that is gone opens nothing, and either way the link is used up. The app lock sits above [AdminApp],
 * so none of this runs while the lock is up.
 */
@RunWith(AndroidJUnit4::class)
class DeepLinkNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val fixtureId = UUID.randomUUID().toString()
    private val fixtureContext = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(base.filesDir, "link-test-$fixtureId").apply { mkdirs() }
    }
    private val prefs = base.getSharedPreferences("link-test-$fixtureId", Context.MODE_PRIVATE)
    private val tokenPrefs = base.getSharedPreferences("link-tokens-$fixtureId", Context.MODE_PRIVATE)
    private val store = ServerStore(fixtureContext, tokenPrefs, metadataWriter = {}, metadataReader = { emptyList() })
    private val sensitiveStores = SensitiveViewModelStoreRegistry()
    private lateinit var nav: NavHostController

    @Before fun seedServers() = runBlocking {
        repeat(2) { index ->
            val id = "link-$index"
            store.addServer(AdminServer(id, "https://link-$index.example.test", "Link $index"), "fixture")
            store.forgetCredentials(id) // The gate reaches its sign-in state locally, with no server request.
        }
    }

    @After fun cleanup() {
        DeepLinkInbox.pending.value?.let(DeepLinkInbox::consume)
        sensitiveStores.clearAll()
        store.close()
        base.deleteSharedPreferences("link-test-$fixtureId")
        base.deleteSharedPreferences("link-tokens-$fixtureId")
    }

    private fun launch() {
        compose.setContent {
            VeilAdminTheme {
                nav = rememberNavController()
                AdminApp(store, AppLockPreferences(prefs), ThemePreferences(prefs), ActivityScopePreferences(prefs), false, nav, sensitiveStores)
            }
        }
        compose.waitForIdle()
    }

    private fun route() = compose.runOnIdle {
        nav.currentBackStackEntry?.destination?.route to nav.currentBackStackEntry?.arguments?.getString("serverId")
    }

    @Test fun aServerLinkOpensThatServersGateAndIsUsedUp() {
        launch()
        assertEquals("allServers" to null, route())
        DeepLinkInbox.offer(DeepLink.parse(DeepLink.build("link-1", "requests"))!!)
        compose.waitForIdle()
        assertEquals("adminGate/{serverId}" to "link-1", route())
        compose.runOnIdle { assertNull(DeepLinkInbox.pending.value) }
    }

    @Test fun aLinkForAServerThatWasRemovedOpensNothingAndIsDropped() {
        launch()
        DeepLinkInbox.offer(DeepLink.parse(DeepLink.build("removed-server", "requests"))!!)
        compose.waitForIdle()
        assertEquals("allServers" to null, route())
        compose.runOnIdle { assertNull(DeepLinkInbox.pending.value) }
    }

    @Test fun anAllServersLinkOpensAllServersAndIsUsedUp() {
        launch()
        DeepLinkInbox.offer(DeepLink.parse(DeepLink.build("link-0"))!!)
        compose.waitForIdle()
        assertEquals("adminGate/{serverId}" to "link-0", route())
        DeepLinkInbox.offer(DeepLink.parse(DeepLink.buildAll("now-playing"))!!)
        compose.waitForIdle()
        assertEquals("allServers" to null, route())
        compose.runOnIdle { assertNull(DeepLinkInbox.pending.value) }
    }

    @Test fun aLinkThatWaitedTooLongIsDroppedNotOpened() {
        launch()
        DeepLinkInbox.offer(DeepLink.parse(DeepLink.build("link-1"))!!, nowMillis = System.currentTimeMillis() - DeepLinkInbox.MAX_AGE_MILLIS - 1)
        compose.waitForIdle()
        assertEquals("allServers" to null, route())
        compose.runOnIdle { assertNull(DeepLinkInbox.pending.value) }
    }
}
