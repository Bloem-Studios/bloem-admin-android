package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.ApiCollection
import com.bloemstudios.admin.network.api.PageInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class UserLoadersTest {
    @Test
    fun `a section loads once and keeps its value when a re-read fails`() = runTest {
        var fail = false
        val loader = SectionLoader<Int> { if (fail) error("offline") else 7 }

        loader.load()
        fail = true
        loader.load()

        assertEquals(Section.Loaded(7), loader.state.value)
    }

    @Test
    fun `a section that fails first shows a generic message, an api error its own`() = runTest {
        val generic = SectionLoader<Int> { error("boom") }
        generic.load()
        assertEquals(Section.Failed(ErrorText.Res(R.string.user_section_failed)), generic.state.value)

        val api = SectionLoader<Int>(serverName = "Server A") { throw ApiError(500, "db is down") }
        api.load()
        assertEquals(Section.Failed(ErrorText.Prefixed("Server A", ErrorText.Raw("db is down"))), api.state.value)
    }

    @Test
    fun `a feed appends pages until the server says there are no more`() = runTest {
        val pages = mapOf<String?, ApiCollection<Int>>(
            null to ApiCollection(listOf(1, 2), PageInfo(true, "c2")),
            "c2" to ApiCollection(listOf(3), PageInfo(false, null))
        )
        val feed = PagedFeed { pages.getValue(it) }

        feed.reload()
        assertEquals(listOf(1, 2), feed.items.value)
        assertTrue(feed.hasMore.value)

        feed.loadMore()
        assertEquals(listOf(1, 2, 3), feed.items.value)
        assertFalse(feed.hasMore.value)
        assertTrue(feed.loaded.value)
    }

    @Test
    fun `a feed stops when the server repeats a cursor`() = runTest {
        val feed = PagedFeed { ApiCollection(listOf(1), PageInfo(true, "same")) }

        feed.reload()
        feed.loadMore()

        assertEquals(listOf(1, 1), feed.items.value)
        assertFalse(feed.hasMore.value)
    }

    @Test
    fun `a failed page keeps the rows and shows the error, and reload clears it`() = runTest {
        var fail = false
        val feed = PagedFeed { if (fail) throw ApiError(503, "The server is busy.") else ApiCollection(listOf(1), PageInfo(true, "c")) }
        feed.reload()

        fail = true
        feed.loadMore()
        assertEquals(listOf(1), feed.items.value)
        assertEquals(ErrorText.Raw("The server is busy."), feed.error.value)

        fail = false
        feed.reload()
        assertNull(feed.error.value)
    }

    @Test
    fun `dates read in the viewer's zone and unknown text is kept`() {
        assertEquals("Jan 2, 2026", formatDate("2026-01-02T03:04:05.678Z", ZoneOffset.UTC, Locale.US))
        assertEquals("not a date", formatDate("not a date", ZoneOffset.UTC, Locale.US))
        assertNull(formatDate(null))
    }

    @Test
    fun `watch time and sizes are short`() {
        assertEquals("30 s", formatWatchTime(30.0))
        assertEquals("45 min", formatWatchTime(2700.0))
        assertEquals("2 h 5 min", formatWatchTime(7500.0))
        assertEquals("12 B", formatBytes(12))
        assertEquals("1.5 MB", formatBytes(1_500_000))
        assertEquals("2.0 GB", formatBytes(2_000_000_000))
    }
}
