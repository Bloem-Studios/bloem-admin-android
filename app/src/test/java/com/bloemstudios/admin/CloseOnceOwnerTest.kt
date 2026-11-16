package com.bloemstudios.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

class CloseOnceOwnerTest {
    @Test
    fun `attached resource closes exactly once`() {
        var closes = 0
        val owner = CloseOnceOwner<Closeable>()
        assertTrue(owner.attach(Closeable { closes++ }))

        owner.close()
        owner.close()

        assertEquals(1, closes)
    }

    @Test
    fun `resource arriving after owner closes is immediately closed`() {
        var closes = 0
        val owner = CloseOnceOwner<Closeable>()
        owner.close()

        assertFalse(owner.attach(Closeable { closes++ }))
        assertEquals(1, closes)
    }
}
