package com.bloemstudios.admin.backups

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupsViewModelTest {
    private class Seen(val method: HttpMethod, val path: String, val ifMatch: String?, val body: String)

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun list(busy: Boolean = false, items: String = "") =
        """{"server_id":"s","destination":"default","destination_available":true,"encrypted":false,"key_check":"x","key_confirmed":true,"enabled":true,"busy":$busy,"keep":7,"last_success_at":"2026-11-14T04:03:00.000Z","items":[$items],"other_server_ids":[],"warnings":[]}"""

    private fun item(id: String) =
        """{"id":"$id","kind":"nightly","status":"complete","created_at":"2026-11-12T04:00:00.000Z","size_bytes":1024,"location":"/b","blob_count":0,"missing_blobs":[],"missing_blobs_total":0,"restore_set_omitted":[],"objects":[],"encrypted":false,"key_matches":true,"clean":true}"""

    private val settings = """{"backup.enabled":"true","backup.time":"04:00","backup.keep":"7"}"""

    /** A server that records every request and answers reads from [state]; writes to settings change the stored values. */
    private class Server(var values: MutableMap<String, String>, var revision: Int = 1) {
        val seen = mutableListOf<Seen>()
        var failPut: HttpStatusCode? = null
    }

    private fun client(server: Server, listBody: () -> String = { "" }, onPost: () -> Pair<HttpStatusCode, String> = { HttpStatusCode.Accepted to """{"id":"20261114T100000Z-manual","kind":"manual","status":"in_progress"}""" }): ApiClient =
        ApiClient("https://server.test", "token", MockEngine { req ->
            val entry = Seen(req.method, req.url.encodedPath, req.headers[HttpHeaders.IfMatch], String(req.body.toByteArray()))
            server.seen += entry
            when {
                req.url.encodedPath == "/api/v2/admin/backups/capabilities" -> respond("""{"state":"available","allowed":true}""", HttpStatusCode.OK, json)
                req.url.encodedPath == "/api/v2/admin/backups" && req.method == HttpMethod.Get -> respond(listBody(), HttpStatusCode.OK, json)
                req.url.encodedPath == "/api/v2/admin/backups" && req.method == HttpMethod.Post -> onPost().let { respond(it.second, it.first, json) }
                req.url.encodedPath.startsWith("/api/v2/admin/backups/") && req.method == HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                req.url.encodedPath == "/api/v2/admin/settings/effective" ->
                    respond(
                        server.values.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }, HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"rev-${server.revision}\"")),
                    )
                req.url.encodedPath.startsWith("/api/v2/admin/settings/") && req.method == HttpMethod.Put -> {
                    val fail = server.failPut
                    if (fail != null) respond(V2Fixtures.problem(fail.value, "precondition_failed", "Settings changed"), fail, json)
                    else {
                        val key = req.url.encodedPath.substringAfterLast('/')
                        val value = Regex("\"value\":\"([^\"]*)\"").find(entry.body)!!.groupValues[1]
                        server.values[key] = value; server.revision++
                        respond("""{"key":"$key","value":"$value","restart_required":false}""", HttpStatusCode.OK, json)
                    }
                }
                req.url.encodedPath == "/api/v2/admin/tasks/backup" -> respond(V2Fixtures.adminTask("backup", optional = """"next_run_at":"2026-11-15T04:00:00.000Z""""), HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        })

    @Test fun `load reads the list, the settings and the next run from the backup task`() = runTest {
        val server = Server(mutableMapOf("backup.enabled" to "true", "backup.time" to "02:30", "backup.keep" to "14"))
        val api = client(server, listBody = { list(items = item("a")) })
        val vm = BackupsViewModel()
        vm.load(api)
        val snapshot = vm.snapshot.value!!
        assertEquals(listOf("a"), snapshot.list.items.map { it.id })
        assertEquals(BackupSettings(true, "02:30", 14), snapshot.settings)
        assertEquals("2026-11-15T04:00:00.000Z", snapshot.nextRunAt)
        assertTrue(snapshot.configured)
        api.close()
    }

    @Test fun `Back up now posts to the backups route once and reloads`() = runTest {
        val server = Server(mutableMapOf())
        val api = client(server, listBody = { list(busy = true) })
        val vm = BackupsViewModel()
        vm.backUpNow(api)
        assertEquals(1, server.seen.count { it.method == HttpMethod.Post && it.path == "/api/v2/admin/backups" })
        assertNull(vm.actionError.value)
        api.close()
    }

    @Test fun `a refused backup reports the server's reason`() = runTest {
        val server = Server(mutableMapOf())
        val api = client(server, listBody = { list() }, onPost = { HttpStatusCode.Conflict to V2Fixtures.problem(409, "conflict", "Another backup or restore is running. Try again when it finishes.") })
        val vm = BackupsViewModel()
        vm.backUpNow(api)
        assertNotNull(vm.actionError.value)
        api.close()
    }

    @Test fun `delete calls the backup's own route and reloads`() = runTest {
        val server = Server(mutableMapOf())
        val api = client(server, listBody = { list() })
        val vm = BackupsViewModel()
        vm.delete(api, "20261112T040000Z-nightly")
        assertTrue(server.seen.any { it.method == HttpMethod.Delete && it.path == "/api/v2/admin/backups/20261112T040000Z-nightly" })
        assertNull(vm.actionError.value)
        api.close()
    }

    @Test fun `saving writes each changed key with the validator, one after the other`() = runTest {
        val server = Server(mutableMapOf("backup.enabled" to "true", "backup.time" to "04:00", "backup.keep" to "7"))
        val api = client(server, listBody = { list() })
        val edit = loadBackupSettingsForEdit(api)
        val result = saveBackupSettings(api, edit, BackupSettingsDraft(false, "03:15", "10"))
        assertEquals(BackupSaveResult.Saved, result)
        val puts = server.seen.filter { it.method == HttpMethod.Put }
        assertEquals(3, puts.size)
        assertEquals("\"rev-1\"", puts.first().ifMatch)
        assertEquals("\"rev-2\"", puts[1].ifMatch)
        assertEquals("\"rev-3\"", puts[2].ifMatch)
        assertEquals(mapOf("backup.enabled" to "false", "backup.time" to "03:15", "backup.keep" to "10"), server.values.toMap())
        api.close()
    }

    @Test fun `a save that finds the settings changed since editing began stops with a conflict`() = runTest {
        val server = Server(mutableMapOf("backup.enabled" to "true", "backup.time" to "04:00", "backup.keep" to "7"))
        val api = client(server, listBody = { list() })
        val edit = loadBackupSettingsForEdit(api)
        server.failPut = HttpStatusCode.PreconditionFailed
        assertEquals(BackupSaveResult.Conflict, saveBackupSettings(api, edit, BackupSettingsDraft(true, "05:00", "7")))
        assertEquals("04:00", server.values["backup.time"])
        api.close()
    }

    @Test fun `a second key is not written when someone changed it after the first write`() = runTest {
        val server = Server(mutableMapOf("backup.enabled" to "true", "backup.time" to "04:00", "backup.keep" to "7"))
        val api = client(server, listBody = { list() })
        val edit = loadBackupSettingsForEdit(api)
        // Another admin changes the keep count between the editor opening and the save reaching its second key.
        val first = saveBackupSettings(api, edit.copy(settings = edit.settings.copy(keep = 9)), BackupSettingsDraft(true, "05:00", "12"))
        assertEquals(BackupSaveResult.Conflict, first)
        assertEquals("05:00", server.values["backup.time"])
        assertEquals("7", server.values["backup.keep"])
        api.close()
    }

    @Test fun `nothing is sent when nothing changed`() = runTest {
        val server = Server(mutableMapOf("backup.enabled" to "true", "backup.time" to "04:00", "backup.keep" to "7"))
        val api = client(server, listBody = { list() })
        val edit = loadBackupSettingsForEdit(api)
        assertEquals(BackupSaveResult.Saved, saveBackupSettings(api, edit, edit.settings.toDraft()))
        assertFalse(server.seen.any { it.method == HttpMethod.Put })
        api.close()
    }
}
