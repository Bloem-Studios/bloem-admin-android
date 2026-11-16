package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.users.AdminUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAccessTest {
    private fun user(
        id: String = "2", role: String = "user", enabled: Boolean = true,
        owner: Boolean = false, passwordLogin: Boolean = true
    ) = AdminUser(id, "name$id", "n$id@example.test", role, enabled, password_login = passwordLogin, is_owner = owner)

    private val orgAdmin = Viewer("1", isOwner = false)
    private val owner = Viewer("9", isOwner = true)

    @Test
    fun `nothing is offered until the viewer is known`() {
        val actions = userActions(user(), null)
        assertFalse(actions.manageable)
        assertFalse(actions.canDelete)
        assertFalse(actions.canDisable)
        assertFalse(actions.canResetPassword)
    }

    @Test
    fun `an organization admin supports a regular user fully`() {
        val actions = userActions(user(), orgAdmin)
        assertTrue(actions.manageable && actions.policyManageable)
        assertTrue(actions.canDisable && actions.canDelete && actions.canResetPassword)
        assertTrue("a temporary password is offered for any account that may be managed", actions.canSetPassword)
        assertFalse(actions.canEnable)
        assertNull(actions.viewOnly)
    }

    @Test
    fun `an organization admin cannot change another admin or the owner`() {
        val otherAdmin = userActions(user(role = "admin"), orgAdmin)
        assertFalse(otherAdmin.manageable)
        assertFalse(otherAdmin.canDelete || otherAdmin.canResetPassword || otherAdmin.canDisable)
        assertEquals(ViewOnlyReason.AnotherAdmin, otherAdmin.viewOnly)

        val theOwner = userActions(user(role = "admin", owner = true), orgAdmin)
        assertFalse(theOwner.manageable)
        assertEquals(ViewOnlyReason.Owner, theOwner.viewOnly)
    }

    @Test
    fun `the owner manages another admin`() {
        val actions = userActions(user(role = "admin"), owner)
        assertTrue(actions.manageable && actions.policyManageable && actions.canDelete)
    }

    @Test
    fun `your own account cannot be disabled or deleted`() {
        val actions = userActions(user(id = "1", role = "admin"), orgAdmin)
        assertTrue(actions.ownAccount)
        assertTrue(actions.manageable)
        assertFalse(actions.canDisable)
        assertFalse(actions.canDelete)
        // An admin does not change their own limits; only the owner does.
        assertFalse(actions.policyManageable)
    }

    @Test
    fun `the owner cannot be disabled or deleted even by themselves`() {
        val actions = userActions(user(id = "9", role = "admin", owner = true), owner)
        assertTrue(actions.manageable && actions.policyManageable)
        assertFalse(actions.canDisable || actions.canDelete)
    }

    @Test
    fun `a disabled account offers Enable and Delete but not Disable`() {
        val actions = userActions(user(enabled = false), orgAdmin)
        assertTrue(actions.canEnable && actions.canDelete)
        assertFalse(actions.canDisable)
    }

    @Test
    fun `password sign-in offers Reset password and Set temporary password, external sign-in only the temporary password`() {
        val password = userActions(user(passwordLogin = true), orgAdmin)
        assertTrue(password.canResetPassword)
        assertTrue(password.canSetPassword)

        val external = userActions(user(passwordLogin = false), orgAdmin)
        assertFalse(external.canResetPassword)
        assertTrue(external.canSetPassword)
    }

    @Test
    fun `Set password is not offered for a disabled external account`() {
        assertFalse(userActions(user(passwordLogin = false, enabled = false), orgAdmin).canSetPassword)
    }

    @Test
    fun `the owner is labelled owner`() {
        assertEquals("owner", user(role = "admin", owner = true).roleLabel())
        assertEquals("admin", user(role = "admin").roleLabel())
    }

    @Test
    fun `a password needs 8 characters and at most 72 bytes`() {
        assertEquals(PasswordProblem.TooShort, passwordProblem("short"))
        assertNull(passwordProblem("eight888"))
        assertNull(passwordProblem("a".repeat(72)))
        assertEquals(PasswordProblem.TooLong, passwordProblem("a".repeat(73)))
        // 40 two-byte characters are 80 bytes.
        assertEquals(PasswordProblem.TooLong, passwordProblem("é".repeat(40)))
    }
}
