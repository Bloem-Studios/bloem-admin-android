package com.bloemstudios.admin.libraries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryMountAndWarningTest {
    private fun root(path: String, reachable: Boolean = true, empty: Boolean = false, code: String? = null, message: String? = null) =
        MountRootCheck(path, reachable, code, message, empty)

    private fun check(healthy: Boolean, vararg roots: MountRootCheck) = MountCheck(healthy = healthy, roots = roots.toList(), summary = "s")

    @Test
    fun `every root reachable and a healthy answer is reachable`() {
        assertEquals(MountVerdict.Reachable, mountVerdict(check(true, root("/a"), root("/b"))))
    }

    @Test
    fun `one unreachable root makes the mount not reachable`() {
        val verdict = mountVerdict(check(false, root("/a"), root("/b", reachable = false, code = "not_found")))
        assertEquals(MountVerdict.NotReachable, verdict)
    }

    @Test
    fun `a reachable but empty root is called out, not passed as fine`() {
        assertEquals(MountVerdict.LooksEmpty, mountVerdict(check(false, root("/a", empty = true))))
        // Even when the server calls the answer healthy, an empty-looking root is the sign of a lost mount.
        assertEquals(MountVerdict.LooksEmpty, mountVerdict(check(true, root("/a", empty = true))))
    }

    @Test
    fun `unreachable beats empty`() {
        assertEquals(MountVerdict.NotReachable, mountVerdict(check(false, root("/a", empty = true), root("/b", reachable = false))))
    }

    @Test
    fun `an unhealthy answer with nothing wrong on any root is still a problem`() {
        assertEquals(MountVerdict.Problem, mountVerdict(check(false, root("/a"))))
        assertEquals(MountVerdict.Problem, mountVerdict(check(false)))
    }

    @Test
    fun `problem roots are the unreachable and empty ones, in order`() {
        val c = check(false, root("/ok"), root("/dead", reachable = false), root("/empty", empty = true))
        assertEquals(listOf("/dead", "/empty"), c.problemRoots().map { it.path })
    }

    @Test
    fun `scan warnings are told apart by code`() {
        fun kind(code: String?) = scanWarning(AdminLibrary("1", "L", scan_warning_code = code, scan_warning_message = "m"))?.kind
        assertEquals(ScanWarningKind.EmptyRoot, kind("empty_root"))
        assertEquals(ScanWarningKind.DeadRoot, kind("dead_root"))
        assertEquals(ScanWarningKind.PartialWalk, kind("partial_walk"))
        assertEquals(ScanWarningKind.Other, kind("something_new"))
        assertNull(kind(null))
        assertNull(kind(""))
    }

    @Test
    fun `a warning keeps the server's message, the code and when it was raised`() {
        val warning = scanWarning(AdminLibrary("1", "L", scan_warning_code = "dead_root", scan_warning_message = "Root is unreachable", scan_warning_at = "2026-11-14T07:00:00.000Z"))!!
        assertEquals("dead_root", warning.code)
        assertEquals("Root is unreachable", warning.message)
        assertEquals("2026-11-14T07:00:00.000Z", warning.raisedAt)
    }

    @Test
    fun `a blank message counts as none so the page uses its own words`() {
        assertNull(scanWarning(AdminLibrary("1", "L", scan_warning_code = "empty_root", scan_warning_message = "  "))!!.message)
    }

    @Test
    fun `only empty and dead roots can be cleared by a mount check`() {
        assertEquals(true, scanWarning(AdminLibrary("1", "L", scan_warning_code = "dead_root"))!!.clearedByMountCheck)
        assertEquals(true, scanWarning(AdminLibrary("1", "L", scan_warning_code = "empty_root"))!!.clearedByMountCheck)
        assertEquals(false, scanWarning(AdminLibrary("1", "L", scan_warning_code = "partial_walk"))!!.clearedByMountCheck)
    }
}
