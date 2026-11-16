package com.bloemstudios.admin.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bloemstudios.admin.links.DeepLink
import com.bloemstudios.admin.links.DeepLinkTarget
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.api.deletePushDevice
import com.bloemstudios.admin.network.api.registerPushDevice
import com.bloemstudios.admin.network.api.setPushWidgets
import com.bloemstudios.admin.network.api.setPushKinds
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class PushContentClientTest {
    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test fun theContentRouteIsCalledWithTheDisplayTokenAndNothingElse() = runBlocking {
        var method: HttpMethod? = null
        var path = ""
        var headers = emptyMap<String, List<String>>()
        val client = PushContentClient(engineFactory = {
            MockEngine { request ->
                method = request.method
                path = request.url.encodedPath
                headers = request.headers.entries().associate { it.key to it.value }
                respond("""{"delivery_id":"d","kind":"admin.backup_failed","title":"t","lock_screen":"l"}""", HttpStatusCode.OK, json)
            }
        })
        val content = client.fetch("https://bloem.example.test", "bad_secret", "dev1.d")
        assertEquals(HttpMethod.Get, method)
        assertEquals("/api/bloem/v2/admin/push/content/dev1.d", path)
        assertEquals(listOf("Bearer bad_secret"), headers[HttpHeaders.Authorization])
        assertNull("no cookie or other credential travels", headers[HttpHeaders.Cookie])
        assertEquals("admin.backup_failed", content!!.kind)
        assertTrue(content.actions.isEmpty())
    }

    @Test fun anythingButASuccessfulReadIsNull() = runBlocking {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.NotFound, HttpStatusCode.InternalServerError)) {
            assertNull(PushContentClient(engineFactory = { MockEngine { respond("", status) } }).fetch("https://bloem.example.test", "bad_x", "d.d"))
        }
        assertNull(PushContentClient(engineFactory = { MockEngine { respond("<html>", HttpStatusCode.OK, json) } }).fetch("https://bloem.example.test", "bad_x", "d.d"))
        assertNull(PushContentClient(engineFactory = { MockEngine { throw java.io.IOException("down") } }).fetch("https://bloem.example.test", "bad_x", "d.d"))
    }

    @Test fun anAddressThatIsNotAValidServerOriginIsNeverCalled() = runBlocking {
        var called = false
        val client = PushContentClient(engineFactory = { MockEngine { called = true; respond("", HttpStatusCode.OK) } })
        assertNull(client.fetch("http://insecure.example.test", "bad_x", "d.d"))
        assertNull(client.fetch("not a url", "bad_x", "d.d"))
        assertFalse(client.retire("", "bad_x", "dev"))
        assertFalse(called)
    }

    @Test fun retireCountsAnAlreadyForgottenDeviceAsDone() = runBlocking {
        assertTrue(PushContentClient(engineFactory = { MockEngine { respond("", HttpStatusCode.NoContent) } }).retire("https://bloem.example.test", "bad_x", "dev"))
        assertTrue(PushContentClient(engineFactory = { MockEngine { respond("", HttpStatusCode.Unauthorized) } }).retire("https://bloem.example.test", "bad_x", "dev"))
        assertFalse(PushContentClient(engineFactory = { MockEngine { respond("", HttpStatusCode.InternalServerError) } }).retire("https://bloem.example.test", "bad_x", "dev"))
        assertFalse(PushContentClient(engineFactory = { MockEngine { throw java.io.IOException("down") } }).retire("https://bloem.example.test", "bad_x", "dev"))
    }

    @Test fun theServersContentShapeDecodes() {
        val raw = """{"delivery_id":"0b9e","kind":"admin.request_waiting","channel":"requests","title":"sam requested Dune","body":"3 waiting",
            "lock_screen":"New request waiting","page":"requests","item_id":"req-1","subject_type":"request","actions":["approve","decline"],
            "badge":3,"created_at":"2026-11-15T22:00:00Z","a_field_added_later":true}"""
        val content = Json { ignoreUnknownKeys = true }.decodeFromString(PushContent.serializer(), raw)
        assertEquals("New request waiting", content.lockScreen)
        assertEquals("req-1", content.itemId)
        assertEquals(3, content.badge)
    }
}

@RunWith(RobolectricTestRunner::class)
class PushApiRequestTest {
    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test fun registrationPostsTheInstallationPlatformAndTokenAndReadsTheDeviceAndDisplayToken() = runBlocking {
        var body: JsonObject? = null
        var path = ""
        var method: HttpMethod? = null
        val engine = MockEngine { request ->
            method = request.method
            path = request.url.encodedPath
            body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            respond(
                """{"device_id":"dev1","display_token":"bad_x","kinds":[{"kind":"admin.scan_failed","enabled":true,"server_emitted":true}]}""",
                HttpStatusCode.OK, json,
            )
        }
        val client = ApiClient("https://bloem.example.test", "access", engine)
        val registered = client.registerPushDevice(PushRegisterRequest("install-0001", "android", "f".repeat(64)))
        assertEquals(HttpMethod.Post, method)
        assertEquals("/api/bloem/v2/admin/push/devices", path)
        assertEquals("install-0001", body!!["installation_id"]!!.jsonPrimitive.content)
        assertEquals("android", body!!["platform"]!!.jsonPrimitive.content)
        assertEquals("f".repeat(64), body!!["token"]!!.jsonPrimitive.content)
        assertFalse("switches the app has not set are not sent", body!!.containsKey("disabled_kinds"))
        assertEquals("dev1", registered.deviceId)
        assertEquals("bad_x", registered.displayToken)
        assertEquals(PushKindState("admin.scan_failed", true, true), registered.kinds.single())
    }

    @Test fun registrationSendsTheSwitchedOffKindsWhenThereAreSome() = runBlocking {
        var body: JsonObject? = null
        val engine = MockEngine { request ->
            body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            respond("""{"device_id":"d","display_token":"bad_x"}""", HttpStatusCode.OK, json)
        }
        ApiClient("https://bloem.example.test", "access", engine)
            .registerPushDevice(PushRegisterRequest("install-0001", "android", "f".repeat(64), listOf("admin.backup_failed")))
        assertEquals("admin.backup_failed", body!!["disabled_kinds"].toString().trim('[', ']', '"'))
    }

    @Test fun kindSwitchesArePatchedToTheDevicesOwnRoute() = runBlocking {
        var body: JsonObject? = null
        var path = ""
        var method: HttpMethod? = null
        val engine = MockEngine { request ->
            method = request.method
            path = request.url.encodedPath
            body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            respond("""{"kinds":[]}""", HttpStatusCode.OK, json)
        }
        ApiClient("https://bloem.example.test", "access", engine).setPushKinds("dev1", mapOf("admin.scan_failed" to false))
        assertEquals(HttpMethod.Patch, method)
        assertEquals("/api/bloem/v2/admin/push/devices/dev1/kinds", path)
        assertEquals(false, body!!["kinds"]!!.jsonObject["admin.scan_failed"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun theWidgetReportIsPutToTheDevicesOwnRouteWithTheChannels() = runBlocking {
        var body: JsonObject? = null
        var path = ""
        var method: HttpMethod? = null
        val engine = MockEngine { request ->
            method = request.method
            path = request.url.encodedPath
            body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            respond("""{"widgets_placed":true,"channels":["nodes","requests"]}""", HttpStatusCode.OK, json)
        }
        val reply = ApiClient("https://bloem.example.test", "access", engine).setPushWidgets("dev1", true, listOf("nodes", "requests"))
        assertEquals(HttpMethod.Put, method)
        assertEquals("/api/bloem/v2/admin/push/devices/dev1/widgets", path)
        assertEquals("true", body!!["widgets_placed"]!!.jsonPrimitive.content)
        assertEquals(listOf("nodes", "requests"), body!!["channels"]!!.toString().trim('[', ']').split(',').map { it.trim('"') })
        assertEquals(PushWidgetsResponse(true, listOf("nodes", "requests")), reply)
    }

    @Test fun registrationCarriesTheWidgetReportOnlyWhenTheCallerSetsIt() = runBlocking {
        val bodies = mutableListOf<JsonObject>()
        val engine = MockEngine { request ->
            bodies += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            respond("""{"device_id":"d","display_token":"bad_x","widgets_placed":true,"widget_channels":["nodes"]}""", HttpStatusCode.OK, json)
        }
        val client = ApiClient("https://bloem.example.test", "access", engine)
        val reply = client.registerPushDevice(PushRegisterRequest("install-0001", "android", "f".repeat(64), widgetsPlaced = true, widgetChannels = listOf("nodes")))
        client.registerPushDevice(PushRegisterRequest("install-0001", "android", "f".repeat(64)))
        assertEquals("true", bodies[0]["widgets_placed"]!!.jsonPrimitive.content)
        assertTrue(bodies[0].containsKey("widget_channels"))
        assertFalse("an older server is not sent fields it does not know", bodies[1].containsKey("widgets_placed") || bodies[1].containsKey("widget_channels"))
        assertTrue(reply.widgetsPlaced)
        assertEquals(listOf("nodes"), reply.widgetChannels)
    }

    @Test fun deletingADeviceUsesTheSignInAndTheDevicesOwnRoute() = runBlocking {
        var method: HttpMethod? = null
        var path = ""
        val engine = MockEngine { request -> method = request.method; path = request.url.encodedPath; respond("", HttpStatusCode.NoContent) }
        ApiClient("https://bloem.example.test", "access", engine).deletePushDevice("dev1")
        assertEquals(HttpMethod.Delete, method)
        assertEquals("/api/bloem/v2/admin/push/devices/dev1", path)
    }
}

@RunWith(RobolectricTestRunner::class)
class PushSettingsLogicTest {
    @Test fun aPlainSiloServerOffersOnlyTheTwoServerDownSwitchesAndSaysSo() {
        val kinds = kindsToShow(PushStatus.NotAvailable)
        assertEquals(listOf(PushKind.SERVER_DOWN, PushKind.SERVER_UP), kinds.map { it.id })
        assertTrue(kinds.all { it.local })
        assertEquals(com.bloemstudios.admin.R.string.push_status_not_available, pushStatusText(PushStatus.NotAvailable))
        val context: Context = ApplicationProvider.getApplicationContext()
        val sentence = context.getString(pushStatusText(PushStatus.NotAvailable))
        assertEquals("Push isn't available on this server. Only the server-down checks apply", sentence)
    }

    @Test fun aBuildWithoutFirebaseOffersOnlyTheLocalKindsToo() {
        assertEquals(2, kindsToShow(PushStatus.NotConfigured).size)
        assertEquals(2, kindsToShow(PushStatus.NotAvailable).size)
    }

    @Test fun aServerThatCanPushOffersEveryKind() {
        for (status in listOf(PushStatus.Registered, PushStatus.NeedsPermission, PushStatus.Failed, PushStatus.Unknown)) {
            assertEquals(PushKinds.all.map { it.id }, kindsToShow(status).map { it.id })
        }
    }

    @Test fun everyStatusHasPlainWords() {
        val context: Context = ApplicationProvider.getApplicationContext()
        for (status in PushStatus.entries) {
            val text = context.getString(pushStatusText(status))
            assertTrue("$status text is empty", text.isNotBlank())
            assertFalse("$status text ends with a period", text.endsWith("."))
            assertFalse("$status text reads as an error code", text.contains("error", ignoreCase = true))
        }
    }

    @Test fun thePermissionIsAskedOnceAtTheFirstUsefulMomentAndNeverAtLaunch() {
        // Android 13 and later only, once, only when not already allowed, and only when a server can actually push.
        assertTrue(shouldAskForPermission(sdk = 34, alreadyAsked = false, granted = false, anyServerCanPush = true, firstPageLoaded = true))
        assertFalse("never before a server can push (that is, at launch)", shouldAskForPermission(34, false, false, anyServerCanPush = false, firstPageLoaded = true))
        assertFalse("never twice", shouldAskForPermission(34, alreadyAsked = true, granted = false, anyServerCanPush = true, firstPageLoaded = true))
        assertFalse("never when allowed", shouldAskForPermission(34, false, granted = true, anyServerCanPush = true, firstPageLoaded = true))
        assertFalse("Android 12 has no runtime permission", shouldAskForPermission(sdk = 32, alreadyAsked = false, granted = false, anyServerCanPush = true, firstPageLoaded = true))
        assertFalse("never straight after sign-in: the first page has to have shown data first",
            shouldAskForPermission(sdk = 34, alreadyAsked = false, granted = false, anyServerCanPush = true, firstPageLoaded = false))
    }

    @Test fun preferencesKeepSwitchesStatusAndThePermissionFlagAcrossInstances() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("push_settings_logic", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val first = PushPreferences(prefs)
        assertTrue("every kind is on by default", PushKinds.all.all { first.isKindEnabled("s", it.id) })
        first.setKindEnabled("s", PushKind.NODE_OFFLINE, false)
        first.setStatus("s", PushStatus.Registered)
        first.markPermissionAsked()
        val second = PushPreferences(prefs)
        assertFalse(second.isKindEnabled("s", PushKind.NODE_OFFLINE))
        assertTrue(second.isKindEnabled("s", PushKind.SCAN_FAILED))
        assertTrue("another server is not affected", second.isKindEnabled("t", PushKind.NODE_OFFLINE))
        assertEquals(PushStatus.Registered, second.status("s"))
        assertTrue(second.permissionAsked)
        second.setKindEnabled("s", PushKind.NODE_OFFLINE, true)
        assertTrue(second.disabledFor("s").isEmpty())
        assertNotNull(second.installationId())
    }

    @Test fun anAdminPushLinkWithAnItemRoundTripsThroughTheAppsLinkGrammar() {
        for ((slug, destination) in mapOf("requests" to "work.requests", "libraries" to "content.libraries", "nodes" to "playback.nodes", "backups" to "system.backups", "plugins" to "system.plugins")) {
            val link = DeepLink.build("srv 1", slug, "item/7")
            assertEquals(DeepLinkTarget("srv 1", destination, "item/7"), DeepLink.parse(link))
        }
    }
}
