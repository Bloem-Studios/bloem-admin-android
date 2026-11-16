package com.bloemstudios.admin.libraries

/** The one-line answer to "can the server reach this library's folders". */
enum class MountVerdict { Reachable, NotReachable, LooksEmpty, Problem }

fun mountVerdict(check: MountCheck): MountVerdict = when {
    check.roots.any { !it.reachable } -> MountVerdict.NotReachable
    // Reachable but every known file under it is missing: what a lost mount shows as an empty mountpoint.
    check.roots.any { it.suspect_empty } -> MountVerdict.LooksEmpty
    check.healthy -> MountVerdict.Reachable
    else -> MountVerdict.Problem
}

/** The roots that need a look, in the server's order. */
fun MountCheck.problemRoots(): List<MountRootCheck> = roots.filter { !it.reachable || it.suspect_empty }

enum class ScanWarningKind { EmptyRoot, DeadRoot, PartialWalk, Other }

/** A library's outstanding scan warning (scan_warning_code, scan_warning_message, scan_warning_at). */
data class ScanWarning(val kind: ScanWarningKind, val code: String, val message: String?, val raisedAt: String?) {
    /** The server clears an empty-root or dead-root warning when a mount check comes back healthy. */
    val clearedByMountCheck: Boolean get() = kind == ScanWarningKind.EmptyRoot || kind == ScanWarningKind.DeadRoot
}

fun scanWarning(library: AdminLibrary): ScanWarning? {
    val code = library.scan_warning_code?.takeIf { it.isNotBlank() } ?: return null
    val kind = when (code) {
        "empty_root" -> ScanWarningKind.EmptyRoot
        "dead_root" -> ScanWarningKind.DeadRoot
        "partial_walk" -> ScanWarningKind.PartialWalk
        else -> ScanWarningKind.Other
    }
    return ScanWarning(kind, code, library.scan_warning_message?.takeIf { it.isNotBlank() }, library.scan_warning_at)
}
