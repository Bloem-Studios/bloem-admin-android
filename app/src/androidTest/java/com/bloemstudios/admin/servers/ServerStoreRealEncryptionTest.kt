package com.bloemstudios.admin.servers

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Real Android cryptography with exclusively fixture-owned tokens, keys, and persistent metadata. */
@RunWith(AndroidJUnit4::class)
class ServerStoreRealEncryptionTest {
    private class Fixture : AutoCloseable {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val preferencesName = "encryption-fixture-$id"
        val keyAlias = "encryption-fixture-key-$id"
        val directory = File(base.filesDir, "encryption-fixture-$id").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        // Explicit reader/writer pair avoids the process-wide production DataStore delegate cache.
        val metadata = File(directory, "servers.json")
        private val stores = mutableListOf<ServerStore>()
        fun prefs() = ServerStore.createEncryptedPrefs(context, preferencesName, keyAlias)
        fun store(): ServerStore = ServerStore(context,
            ServerStore.openTokenPreferences(context, preferencesName, keyAlias),
            metadataWriter = { servers ->
                val rows = JSONArray()
                servers.forEach { server -> rows.put(JSONObject().put("id", server.id).put("url", server.baseUrl).put("name", server.displayName)) }
                metadata.writeText(rows.toString())
            },
            metadataReader = {
                if (!metadata.exists()) emptyList() else {
                    val rows = JSONArray(metadata.readText())
                    List(rows.length()) { index -> rows.getJSONObject(index).let {
                        AdminServer(it.getString("id"), it.getString("url"), it.getString("name"))
                    } }
                }
            }
        ).also { stores += it }
        override fun close() {
            stores.forEach { it.close() }
            context.deleteSharedPreferences(preferencesName)
            deleteKey(keyAlias)
            directory.deleteRecursively()
        }
    }

    @Test fun encryptedTokenPreferencesAreASeparateNonBackupFile() = Fixture().use { fixture ->
        assertTrue(fixture.prefs().edit().putString("server", "fixture-secret").commit())
        val file = File(fixture.base.applicationInfo.dataDir, "shared_prefs/${fixture.preferencesName}.xml")
        assertTrue(file.exists())
        assertFalse(file.readText().contains("fixture-secret"))
        // Check backup XML resources separately; never open the production preference file.
        assertEquals("admin_tokens", TOKEN_PREFERENCES_NAME)
        assertNotEquals(TOKEN_PREFERENCES_NAME, fixture.preferencesName)
        for (resourceName in listOf("backup_rules", "data_extraction_rules")) {
            val resource = fixture.context.resources.getIdentifier(resourceName, "xml", fixture.context.packageName)
            assertNotEquals(0, resource)
            val parser = fixture.context.resources.getXml(resource)
            var exclusions = 0
            parser.use {
                while (it.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (it.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && it.name == "exclude" &&
                        it.getAttributeValue(null, "domain") == "sharedpref" && it.getAttributeValue(null, "path") == "admin_tokens.xml") exclusions++
                    it.next()
                }
            }
            assertEquals(if (resourceName == "backup_rules") 1 else 2, exclusions)
        }
    }

    @Test fun invalidatedDedicatedMasterKeyRecoversTokensButPreservesServerMetadata() = Fixture().use { fixture ->
        val server = AdminServer("fixture", "https://recovery.example.test", "Recovery Fixture")
        val seed = fixture.store()
        runBlocking { seed.addServer(server, "fixture-token") }
        assertTrue(fixture.prefs().edit().putString(server.id, "fixture-token").commit())
        seed.close()
        deleteKey(fixture.keyAlias)
        val reopened = fixture.store()
        assertTrue(reopened.recoveredCredentials.value)
        assertTrue(reopened.credentialStorageAvailable.value)
        assertEquals(listOf(server), reopened.servers.value)
        assertNull(reopened.accessToken(server.id))
    }

    @Test fun platformPreferenceDeletionInvalidatesCachedEncryptedInstance() = Fixture().use { fixture ->
        assertTrue(fixture.prefs().edit().putString("server", "fixture-token").commit())
        assertTrue(fixture.context.deleteSharedPreferences(fixture.preferencesName))
        assertNull(fixture.prefs().getString("server", null))
    }

    @Test fun realEncryptedSharedPreferencesRoundTripsAccessTokensCorrectly() = Fixture().use { fixture ->
        val server = AdminServer("fixture", "https://example.test", "Encrypted Fixture")
        val store = fixture.store()
        runBlocking { store.addServer(server, "fixture-token") }
        assertEquals("fixture-token", store.accessToken(server.id))
        store.close()
        val reopened = fixture.store()
        assertEquals("fixture-token", reopened.accessToken(server.id))
        assertEquals(listOf(server), reopened.servers.value)
    }

    @Test fun removeServerWithRealEncryptedSharedPreferencesTrulyDeletesToken() = Fixture().use { fixture ->
        val server = AdminServer("fixture", "https://example.test", "Removable Fixture")
        val store = fixture.store()
        runBlocking { store.addServer(server, "fixture-token"); store.removeServer(server.id) }
        assertNull(store.accessToken(server.id))
        store.close()
        val reopened = fixture.store()
        assertNull(reopened.accessToken(server.id))
        assertTrue(reopened.servers.value.isEmpty())
    }

    @Test fun simultaneousFixturesNeverShareMetadataOrCredentials() = Fixture().use { a -> Fixture().use { b ->
        val server = AdminServer("same-id", "https://example.test", "Only A")
        runBlocking { a.store().addServer(server, "fixture-token") }
        assertEquals(listOf(server), a.store().servers.value)
        assertTrue(b.store().servers.value.isEmpty())
        assertNull(b.store().accessToken(server.id))
    } }

    companion object {
        private fun deleteKey(alias: String) {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.run {
                if (containsAlias(alias)) deleteEntry(alias)
            }
        }
    }
}
