package com.bloemstudios.admin.ui.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellInteractionLockTest {
    @Test
    fun `overlapping leases lock until the final idempotent release`() {
        val lock = ShellInteractionLockController()

        assertFalse(lock.isLocked.value)
        val first = lock.acquire()
        assertTrue(lock.isLocked.value)
        val second = lock.acquire()
        assertTrue(lock.isLocked.value)

        first.release()
        assertTrue(lock.isLocked.value)
        first.release()
        assertTrue(lock.isLocked.value)

        second.release()
        assertFalse(lock.isLocked.value)
        second.release()
        assertFalse(lock.isLocked.value)
    }

    @Test
    fun `default no-op lease never locks and release is harmless`() {
        assertFalse(NoOpShellInteractionLock.isLocked.value)

        val lease = NoOpShellInteractionLock.acquire()
        assertFalse(NoOpShellInteractionLock.isLocked.value)
        lease.release()
        lease.release()

        assertFalse(NoOpShellInteractionLock.isLocked.value)
    }
}
