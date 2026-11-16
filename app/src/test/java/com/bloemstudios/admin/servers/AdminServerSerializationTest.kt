package com.bloemstudios.admin.servers

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdminServerSerializationTest {
    @Test
    fun aStoredServerWithoutTheProviderFieldStillLoads() {
        val server = Json.decodeFromString<AdminServer>("""{"id":"a","baseUrl":"https://a.test","displayName":"A"}""")
        assertNull(server.signInProviderId)
    }

    @Test
    fun theRememberedProviderRoundTrips() {
        val server = AdminServer("a", "https://a.test", "A", signInProviderId = "26")
        assertEquals(server, Json.decodeFromString<AdminServer>(Json.encodeToString(server)))
    }
}
