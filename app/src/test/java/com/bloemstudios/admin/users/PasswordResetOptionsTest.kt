package com.bloemstudios.admin.users

import com.bloemstudios.admin.R
import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.users.detail.AdminUserCapabilities
import com.bloemstudios.admin.users.detail.Viewer
import com.bloemstudios.admin.users.detail.userActions
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reset password: email or a link to hand over (gated on the server's capabilities), and a temporary password for any account the admin may manage. */
class PasswordResetOptionsTest {
    private fun account(
        email: String = "alex@example.test", enabled: Boolean = true, passwordLogin: Boolean = true,
        role: String = "user", owner: Boolean = false, id: String = "7"
    ) = AdminUser(id = id, username = "alex", email = email, role = role, enabled = enabled, password_login = passwordLogin, is_owner = owner)

    private val both = AdminUserCapabilities(password_reset_email = true, password_reset_link = true)

    @Test
    fun `both options are open when the server can email and make links`() {
        val options = resetOptions(account(), both)
        assertTrue(options.email is ResetChoiceState.Available)
        assertTrue(options.link is ResetChoiceState.Available)
        assertTrue(options.anyAvailable)
    }

    @Test
    fun `the link needs the link capability`() {
        val options = resetOptions(account(), both.copy(password_reset_link = false))
        assertEquals(ResetChoiceState.Unavailable(R.string.user_reset_link_off), options.link)
        assertTrue(options.email is ResetChoiceState.Available)
    }

    @Test
    fun `email needs the capability and an address`() {
        assertEquals(ResetChoiceState.Unavailable(R.string.user_reset_email_off), resetOptions(account(), both.copy(password_reset_email = false)).email)
        assertEquals(ResetChoiceState.Unavailable(R.string.user_reset_email_missing), resetOptions(account(email = " "), both).email)
        assertTrue("a link still works without an address", resetOptions(account(email = ""), both).link is ResetChoiceState.Available)
    }

    @Test
    fun `a turned off account gets neither`() {
        val options = resetOptions(account(enabled = false), both)
        assertEquals(ResetChoiceState.Unavailable(R.string.user_reset_disabled), options.email)
        assertEquals(ResetChoiceState.Unavailable(R.string.user_reset_disabled), options.link)
        assertFalse(options.anyAvailable)
    }

    @Test
    fun `without the capabilities nothing is offered`() {
        assertFalse(resetOptions(account(), null).anyAvailable)
    }

    @Test
    fun `a temporary password can be set on every account the admin may manage`() {
        val viewer = Viewer("1", isOwner = false)
        assertTrue("a password account", userActions(account(passwordLogin = true), viewer).canSetPassword)
        assertTrue("an external-sign-in account", userActions(account(passwordLogin = false), viewer).canSetPassword)
        assertFalse("not a turned off account", userActions(account(passwordLogin = true, enabled = false), viewer).canSetPassword)
        assertFalse("not the owner's account", userActions(account(owner = true, role = "admin"), viewer).canSetPassword)
        assertFalse("not another admin's, unless the owner", userActions(account(role = "admin"), viewer).canSetPassword)
        assertTrue(userActions(account(role = "admin"), Viewer("1", isOwner = true)).canSetPassword)
    }

    // --- issuing -------------------------------------------------------------------------------------------------

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(status: HttpStatusCode, body: String, seen: MutableList<String> = mutableListOf()) =
        ApiClient("https://example.test", null, MockEngine { request ->
            seen += request.url.encodedPath + " " + request.body.toByteArray().decodeToString()
            respond(body, status, json)
        })

    @Test
    fun `a link is asked for with delivery link and comes back once`() = runTest {
        val seen = mutableListOf<String>()
        val answer = """{"delivery":"link","delivery_status":"not_requested","expires_at":"2026-11-14T16:00:00Z","reset_url":"https://bloem.example/reset-password#abc123"}"""

        val outcome = issuePasswordReset(client(HttpStatusCode.Created, answer, seen), account(), ResetChoice.Link)

        assertEquals("/api/v2/admin/users/7/password-reset {\"delivery\":\"link\"}", seen.single())
        outcome as ResetOutcome.LinkIssued
        assertEquals("https://bloem.example/reset-password#abc123", outcome.link.url)
        assertEquals("2026-11-14T16:00:00Z", outcome.link.expiresAt)
    }

    @Test
    fun `the link is never in the text of what holds it`() = runTest {
        val answer = """{"delivery":"link","delivery_status":"not_requested","expires_at":"2026-11-14T16:00:00Z","reset_url":"https://bloem.example/reset-password#secret999"}"""
        val outcome = issuePasswordReset(client(HttpStatusCode.Created, answer), account(), ResetChoice.Link)
        assertFalse(outcome.toString().contains("secret999"))
        assertFalse((outcome as ResetOutcome.LinkIssued).link.toString().contains("secret999"))
        val receipt = PasswordResetReceipt("link", "not_requested", "2026-11-14T16:00:00Z", "https://x/y#secret999")
        assertFalse(receipt.toString().contains("secret999"))
    }

    @Test
    fun `a link the server did not return is an error, not a blank link`() = runTest {
        val answer = """{"delivery":"link","delivery_status":"not_requested","expires_at":"2026-11-14T16:00:00Z"}"""
        val outcome = issuePasswordReset(client(HttpStatusCode.Created, answer), account(), ResetChoice.Link)
        assertEquals(ResetOutcome.Failed(ErrorText.Res(R.string.user_reset_link_missing)), outcome)
    }

    @Test
    fun `email is asked for with delivery email`() = runTest {
        val seen = mutableListOf<String>()
        val answer = """{"delivery":"email","delivery_status":"sent","expires_at":"2026-11-15T10:00:00Z"}"""
        val outcome = issuePasswordReset(client(HttpStatusCode.Created, answer, seen), account(), ResetChoice.Email)
        assertTrue(seen.single().endsWith("{\"delivery\":\"email\"}"))
        assertEquals(ResetOutcome.EmailSent("2026-11-15T10:00:00Z"), outcome)
    }

    @Test
    fun `an email the mail server did not confirm says so`() = runTest {
        val answer = """{"delivery":"email","delivery_status":"failed_or_unknown","expires_at":"2026-11-15T10:00:00Z"}"""
        assertEquals(ResetOutcome.EmailUnconfirmed("2026-11-15T10:00:00Z"), issuePasswordReset(client(HttpStatusCode.Created, answer), account(), ResetChoice.Email))
    }

    @Test
    fun `a refusal keeps the server's words`() = runTest {
        val outcome = issuePasswordReset(
            client(HttpStatusCode.Conflict, V2Fixtures.problem(409, "conflict", "The account is disabled or does not sign in with a password")),
            account(), ResetChoice.Link,
        )
        assertEquals(ResetOutcome.Failed(ErrorText.Raw("The account is disabled or does not sign in with a password")), outcome)
    }

    @Test
    fun `a broken answer is uncertain and is not issued again`() = runTest {
        val outcome = issuePasswordReset(client(HttpStatusCode.Created, "not json"), account(), ResetChoice.Link)
        assertEquals(ResetOutcome.Uncertain, outcome)
        assertNull((outcome as? ResetOutcome.LinkIssued))
    }
}
