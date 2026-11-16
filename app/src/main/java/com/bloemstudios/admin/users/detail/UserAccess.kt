package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.users.AdminUser

/** The signed-in admin as the User page sees them. Owner status is read from the viewer's own account. */
data class Viewer(val id: String, val isOwner: Boolean)

/** Why an account is view-only, shown above its actions. */
enum class ViewOnlyReason { Owner, AnotherAdmin }

/**
 * What the signed-in admin may do with one account, mirroring the web's lib/accountOwner.ts and
 * UserDetailHeader.tsx. The server enforces the same rules; this only keeps the page from offering
 * actions it would refuse. With no [Viewer] yet, nothing is offered.
 */
data class UserActions(
    /** Edit, reset the password or manage sign-in. */
    val manageable: Boolean,
    /** Change libraries, limits, downloads and requests. Only the owner changes an admin's, including their own. */
    val policyManageable: Boolean,
    val ownAccount: Boolean,
    val canResetPassword: Boolean,
    val canSetPassword: Boolean,
    val canDisable: Boolean,
    val canEnable: Boolean,
    val canDelete: Boolean,
    val viewOnly: ViewOnlyReason?
)

internal fun userActions(user: AdminUser, viewer: Viewer?): UserActions {
    if (viewer == null) {
        return UserActions(false, false, false, false, false, false, false, false, null)
    }
    val own = user.id == viewer.id
    val isAdmin = user.role == "admin"
    val manageable = own || (!user.is_owner && (!isAdmin || viewer.isOwner))
    val policyManageable = manageable && (!isAdmin || viewer.isOwner)
    return UserActions(
        manageable = manageable,
        policyManageable = policyManageable,
        ownAccount = own,
        canResetPassword = manageable && user.password_login,
        // A temporary password for every account the admin may manage: a way back in when mail is off or the address is wrong.
        canSetPassword = manageable && user.enabled,
        canDisable = manageable && user.enabled && !user.is_owner && !own,
        canEnable = manageable && !user.enabled,
        canDelete = manageable && !user.is_owner && !own,
        viewOnly = when {
            manageable -> null
            user.is_owner -> ViewOnlyReason.Owner
            else -> ViewOnlyReason.AnotherAdmin
        }
    )
}

/** The role shown for an account: the owner is labelled "owner" rather than "admin". */
internal fun AdminUser.roleLabel(): String = if (is_owner) "owner" else role

/** A password the server accepts: at least 8 characters, at most 72 bytes. */
internal enum class PasswordProblem { TooShort, TooLong }

internal fun passwordProblem(password: String): PasswordProblem? = when {
    password.length < 8 -> PasswordProblem.TooShort
    password.toByteArray(Charsets.UTF_8).size > 72 -> PasswordProblem.TooLong
    else -> null
}
