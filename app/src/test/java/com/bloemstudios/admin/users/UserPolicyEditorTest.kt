package com.bloemstudios.admin.users

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserPolicyEditorTest {
    private val effective = AdminEffectivePolicy(
        library_ids = listOf("1"),
        max_playback_quality = "1080p",
        max_streams = 6,
        max_profiles = 4,
        max_transcodes = 2,
        transcode_allowed = true,
        audio_transcode_allowed = true,
        download_allowed = true,
        download_transcode_allowed = false,
        requests_allowed = true,
        permissions = listOf("playback")
    )

    @Test
    fun `inherited controls display effective values`() {
        val user = AdminUser("1", "viewer", "v@example.test", "user", true, max_profiles = 4, effective_policy = effective)

        val state = user.policyEditorValues()

        assertEquals("6", state.maxStreamsText)
        assertTrue(state.maxStreamsInherited)
        assertEquals(true, state.downloadAllowed)
        assertTrue(state.downloadAllowedInherited)
        assertTrue(buildUserPolicyUpdate(user, state).isEmpty())
    }

    @Test
    fun `null policy collections display as unrestricted libraries and no permissions`() {
        val user = AdminUser(
            "1",
            "viewer",
            "v@example.test",
            "user",
            true,
            effective_policy = effective.copy(library_ids = null, permissions = null)
        )

        val state = user.policyEditorValues()

        assertEquals(EffectiveLibraryAccess.Unrestricted, state.libraryAccess)
        assertEquals(EffectivePermissionAccess.None, state.permissionAccess)
    }

    @Test
    fun `empty policy collections remain explicit restrictions`() {
        val user = AdminUser(
            "1",
            "viewer",
            "v@example.test",
            "user",
            true,
            effective_policy = effective.copy(library_ids = emptyList(), permissions = emptyList())
        )

        val state = user.policyEditorValues()

        assertEquals(EffectiveLibraryAccess.Restricted(emptyList()), state.libraryAccess)
        assertEquals(EffectivePermissionAccess.Explicit(emptyList()), state.permissionAccess)
    }

    @Test
    fun `clearing an override emits explicit null and leaves inherited fields absent`() {
        val user = AdminUser("1", "viewer", "v@example.test", "user", true, max_streams = 3, max_profiles = 4, effective_policy = effective)
        val edited = user.policyEditorValues().copy(maxStreamsInherited = true)

        assertEquals("""{"max_streams":null}""", buildUserPolicyUpdate(user, edited).toJsonObject().toString())
    }

    @Test
    fun `opting out of inheritance emits an explicit effective value`() {
        val user = AdminUser("1", "viewer", "v@example.test", "user", true, max_profiles = 4, effective_policy = effective)
        val edited = user.policyEditorValues().copy(downloadAllowedInherited = false)

        assertEquals("""{"download_allowed":true}""", buildUserPolicyUpdate(user, edited).toJsonObject().toString())
    }
    @Test
    fun `max profiles can restore inheritance with explicit null`() {
        val user = AdminUser("1", "viewer", "v@example.test", "user", true, max_profiles = 4, effective_policy = effective)
        val edited = user.policyEditorValues().copy(maxProfilesInherited = true)
        assertEquals("{\"max_profiles\":null}", buildUserPolicyUpdate(user, edited).toJsonObject().toString())
    }

}
