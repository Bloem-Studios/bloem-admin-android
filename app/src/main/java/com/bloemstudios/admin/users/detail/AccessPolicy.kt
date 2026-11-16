package com.bloemstudios.admin.users.detail

/** The permissions an admin can assign to one account, as the server names them. */
internal const val PERMISSION_MARKER_EDIT = "marker_edit"
internal const val PERMISSION_METADATA_CURATION = "metadata_curation"
internal const val PERMISSION_WATCH_LIVE_TV = "watch_live_tv"

internal val ASSIGNABLE_PERMISSIONS = listOf(PERMISSION_MARKER_EDIT, PERMISSION_METADATA_CURATION, PERMISSION_WATCH_LIVE_TV)

/** Admins are never grouped: the server clears the group of an admin, so a form never offers or sends one. */
internal fun effectiveGroupId(role: String, groupId: String?): String? = if (role == "admin") null else groupId

/** Whether a group keeps an account from using a permission: the server intersects the two sets. */
internal data class PermissionLock(val locked: Boolean, val groupName: String? = null)

internal fun permissionLock(role: String, groupId: String?, groups: List<AccessGroup>, permission: String): PermissionLock {
    val id = effectiveGroupId(role, groupId) ?: return PermissionLock(false)
    val group = groups.firstOrNull { it.id == id } ?: return PermissionLock(false)
    val allowed = group.allowed_permissions
    return PermissionLock(locked = allowed != null && permission !in allowed, groupName = group.name)
}

/** The group's display name, or "#id" while the group list lacks it. */
internal fun accessGroupName(groupId: String, groups: List<AccessGroup>): String =
    groups.firstOrNull { it.id == groupId }?.name ?: "#$groupId"

/** The libraries an account that does not set its own list would see. */
internal sealed interface InheritedLibraries {
    /** The source is not loaded yet (or the group was deleted), so nothing true can be said. */
    data object Unknown : InheritedLibraries
    data object All : InheritedLibraries
    data class Some(val ids: List<String>) : InheritedLibraries
}

private fun List<String>?.asInherited(): InheritedLibraries = if (this == null) InheritedLibraries.All else InheritedLibraries.Some(this)

/**
 * What "inherit" means for libraries under the group picked in the form, not the group last saved, so the
 * label follows the picker. An admin or an ungrouped account takes the server's defaults; a grouped account
 * takes its group's list. While the account is saved with the same group and inherits, the server's own
 * resolved list is the freshest answer.
 */
internal fun inheritedLibraries(
    role: String,
    draftGroupId: String?,
    savedGroupId: String?,
    inheritsNow: Boolean,
    effectiveLibraryIds: List<String>?,
    groups: List<AccessGroup>?,
    defaults: UserPolicyDefaults?
): InheritedLibraries {
    val groupId = effectiveGroupId(role, draftGroupId)
    if (inheritsNow && groupId == effectiveGroupId(role, savedGroupId)) return effectiveLibraryIds.asInherited()
    if (role == "admin") return defaults?.admin?.let { it.library_ids.asInherited() } ?: InheritedLibraries.Unknown
    if (groupId == null) return defaults?.ungrouped?.let { it.library_ids.asInherited() } ?: InheritedLibraries.Unknown
    val group = groups?.firstOrNull { it.id == groupId } ?: return InheritedLibraries.Unknown
    return group.library_ids.asInherited()
}

/** Whether inherited libraries come from a group (true) or from the server's defaults (false). */
internal fun inheritsFromGroup(role: String, draftGroupId: String?): Boolean = effectiveGroupId(role, draftGroupId) != null

/** Library ids in the order of [all], so a saved list does not depend on the order of the taps. */
internal fun toggleLibrary(selected: List<String>, all: List<String>, id: String, on: Boolean): List<String> =
    all.filter { candidate -> if (candidate == id) on else candidate in selected }

internal fun setAssignedPermission(permissions: List<String>, permission: String, on: Boolean): List<String> =
    (if (on) permissions + permission else permissions - permission).distinct().sorted()
