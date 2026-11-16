package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.users.AdminUser
import com.bloemstudios.admin.users.PolicyOverrideChange
import com.bloemstudios.admin.users.UserAccessEdit
import com.bloemstudios.admin.users.accessEdit
import com.bloemstudios.admin.users.buildUserAccessUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessPolicyTest {
    private val family = AccessGroup("g1", "Family", library_ids = listOf("1", "2"), allowed_permissions = listOf(PERMISSION_MARKER_EDIT))
    private val open = AccessGroup("g2", "Open", library_ids = null, allowed_permissions = null)
    private val groups = listOf(family, open)

    private fun user(role: String = "user", group: String? = null, libraries: List<String>? = null, permissions: List<String> = emptyList()) =
        AdminUser("2", "bob", "bob@example.test", role, true, access_group_id = group, library_ids = libraries, permissions = permissions)

    @Test
    fun `a group that does not list a permission locks it`() {
        assertEquals(PermissionLock(true, "Family"), permissionLock("user", "g1", groups, PERMISSION_WATCH_LIVE_TV))
        assertEquals(PermissionLock(false, "Family"), permissionLock("user", "g1", groups, PERMISSION_MARKER_EDIT))
    }

    @Test
    fun `a group that allows everything, no group, an admin and an unknown group lock nothing`() {
        assertFalse(permissionLock("user", "g2", groups, PERMISSION_WATCH_LIVE_TV).locked)
        assertFalse(permissionLock("user", null, groups, PERMISSION_WATCH_LIVE_TV).locked)
        assertFalse(permissionLock("admin", "g1", groups, PERMISSION_WATCH_LIVE_TV).locked)
        assertFalse(permissionLock("user", "gone", groups, PERMISSION_WATCH_LIVE_TV).locked)
    }

    @Test
    fun `inherited libraries follow the group picked in the form`() {
        val defaults = UserPolicyDefaults(admin = PolicyDefaultsHint(null), ungrouped = PolicyDefaultsHint(listOf("9")))
        // Saved without a group and inheriting: the server's own resolved list.
        assertEquals(InheritedLibraries.Some(listOf("9")), inheritedLibraries("user", null, null, true, listOf("9"), groups, defaults))
        // Picking a group changes what inherit means, before anything is saved.
        assertEquals(InheritedLibraries.Some(listOf("1", "2")), inheritedLibraries("user", "g1", null, true, listOf("9"), groups, defaults))
        assertEquals(InheritedLibraries.All, inheritedLibraries("user", "g2", null, true, listOf("9"), groups, defaults))
        // Picking no group again falls back to the ungrouped defaults when the account overrides now.
        assertEquals(InheritedLibraries.Some(listOf("9")), inheritedLibraries("user", null, "g1", false, listOf("1"), groups, defaults))
        assertEquals(InheritedLibraries.All, inheritedLibraries("admin", "g1", null, false, null, groups, defaults))
    }

    @Test
    fun `an inherit label is not invented while its source is unknown`() {
        assertEquals(InheritedLibraries.Unknown, inheritedLibraries("user", "g1", null, false, null, null, null))
        assertEquals(InheritedLibraries.Unknown, inheritedLibraries("user", "gone", null, false, null, groups, null))
        assertEquals(InheritedLibraries.Unknown, inheritedLibraries("user", null, null, false, null, groups, null))
        assertTrue(inheritsFromGroup("user", "g1"))
        assertFalse(inheritsFromGroup("user", null))
        assertFalse(inheritsFromGroup("admin", "g1"))
    }

    @Test
    fun `libraries are toggled in the order the server lists them`() {
        val all = listOf("1", "2", "3")
        assertEquals(listOf("1", "3"), toggleLibrary(listOf("3"), all, "1", true))
        assertEquals(listOf("3"), toggleLibrary(listOf("1", "3"), all, "1", false))
    }

    @Test
    fun `an unchanged form sends nothing`() {
        val u = user(group = "g1", libraries = listOf("1"), permissions = listOf("marker_edit"))
        assertTrue(buildUserAccessUpdate(u, u.accessEdit()).isEmpty())
        // Same libraries and permissions in another order are still unchanged.
        val reordered = UserAccessEdit("g1", listOf("1"), listOf("marker_edit"))
        assertTrue(buildUserAccessUpdate(u, reordered).isEmpty())
    }

    @Test
    fun `changing the group sends it, and no group clears it`() {
        val u = user(group = "g1")
        assertEquals(PolicyOverrideChange.Set("g2"), buildUserAccessUpdate(u, u.accessEdit().copy(groupId = "g2")).access_group_id)
        assertEquals(PolicyOverrideChange.Clear, buildUserAccessUpdate(u, u.accessEdit().copy(groupId = null)).access_group_id)
        assertEquals("""{"access_group_id":null}""", buildUserAccessUpdate(u, u.accessEdit().copy(groupId = null)).toJsonObject().toString())
    }

    @Test
    fun `an admin's group is never sent`() {
        val admin = user(role = "admin")
        assertTrue(buildUserAccessUpdate(admin, admin.accessEdit().copy(groupId = "g1")).isEmpty())
    }

    @Test
    fun `libraries inherit with null, choose with a list and may be chosen empty`() {
        val inheriting = user()
        assertEquals(
            """{"library_ids":["1","3"]}""",
            buildUserAccessUpdate(inheriting, inheriting.accessEdit().copy(libraryIds = listOf("3", "1"))).toJsonObject().toString()
        )
        assertEquals(
            """{"library_ids":[]}""",
            buildUserAccessUpdate(inheriting, inheriting.accessEdit().copy(libraryIds = emptyList())).toJsonObject().toString()
        )
        val own = user(libraries = listOf("1"))
        assertEquals(
            """{"library_ids":null}""",
            buildUserAccessUpdate(own, own.accessEdit().copy(libraryIds = null)).toJsonObject().toString()
        )
    }

    @Test
    fun `permissions are sent as the whole assigned set`() {
        val u = user(permissions = listOf("marker_edit"))
        val edited = u.accessEdit().copy(permissions = setAssignedPermission(u.permissions, PERMISSION_WATCH_LIVE_TV, true))
        assertEquals("""{"permissions":["marker_edit","watch_live_tv"]}""", buildUserAccessUpdate(u, edited).toJsonObject().toString())
        val cleared = u.accessEdit().copy(permissions = setAssignedPermission(u.permissions, PERMISSION_MARKER_EDIT, false))
        assertEquals("""{"permissions":[]}""", buildUserAccessUpdate(u, cleared).toJsonObject().toString())
    }

    @Test
    fun `limits and access travel in one body`() {
        val u = user(group = "g1")
        val request = buildUserAccessUpdate(
            u, u.accessEdit().copy(groupId = "g2"),
            com.bloemstudios.admin.users.UpdateUserQuotasRequest(max_streams = PolicyOverrideChange.Set(4))
        )
        assertEquals("""{"max_streams":4,"access_group_id":"g2"}""", request.toJsonObject().toString())
    }
}
