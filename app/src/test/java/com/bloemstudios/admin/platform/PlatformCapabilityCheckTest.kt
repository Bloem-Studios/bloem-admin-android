package com.bloemstudios.admin.platform

import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformCapabilityCheckTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(status: HttpStatusCode, body: String, seen: MutableList<String> = mutableListOf()) =
        ApiClient("https://example.test", null, MockEngine { request -> seen += request.url.encodedPath; respond(body, status, json) })

    @Test
    fun `a server that says it has organizations is offered the page`() = runTest {
        val seen = mutableListOf<String>()
        assertTrue(client(HttpStatusCode.OK, """{"api":"bloem/v2","features":{"organization_memberships":true},"feature_tokens":[]}""", seen).supportsPlatformOrganizations())
        assertEquals(listOf("/api/bloem/v2/capabilities"), seen)
    }

    @Test
    fun `a server that says it has none, or says nothing, is not`() = runTest {
        assertFalse(client(HttpStatusCode.OK, """{"features":{"organization_memberships":false},"feature_tokens":[]}""").supportsPlatformOrganizations())
        assertFalse(client(HttpStatusCode.OK, """{"feature_tokens":[]}""").supportsPlatformOrganizations())
    }

    @Test
    fun `an older server, a plain Silo server or a failed probe is not`() = runTest {
        assertFalse(client(HttpStatusCode.NotFound, """{"error":"not_found"}""").supportsPlatformOrganizations())
        assertFalse(client(HttpStatusCode.ServiceUnavailable, "unavailable").supportsPlatformOrganizations())
        assertFalse(client(HttpStatusCode.OK, "not json").supportsPlatformOrganizations())
    }
}
