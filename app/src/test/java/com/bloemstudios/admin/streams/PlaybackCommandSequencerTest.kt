package com.bloemstudios.admin.streams

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PlaybackCommandSequencerTest {
    @Test fun `sequencer uses wall clock and stays increasing per session`() {
        val times = ArrayDeque(listOf(1000L, 1000L, 999L, 5000L))
        val sequencer = PlaybackCommandSequencer { times.removeFirst() }
        assertEquals(1000L, sequencer.next("a").sequence)
        assertEquals(1001L, sequencer.next("a").sequence)
        assertEquals(1002L, sequencer.next("a").sequence)
        assertEquals(5000L, sequencer.next("b").sequence)
    }

    @Test fun `command ids are random canonical UUIDs`() {
        val sequencer = PlaybackCommandSequencer()
        val first = sequencer.next("a").command_id
        val second = sequencer.next("a").command_id
        assertNotEquals(first, second)
        assertEquals(first, UUID.fromString(first).toString())
    }

    @Test fun `sequence is capped below two to the fifty third`() {
        val limit = 9007199254740991L
        val sequencer = PlaybackCommandSequencer { Long.MAX_VALUE }
        assertEquals(limit, sequencer.next("a").sequence)
        assertTrue(sequencer.next("a").sequence <= limit)
    }

    @Test fun `observed latest sequence moves the next allocation above it`() {
        val sequencer = PlaybackCommandSequencer { 100L }
        sequencer.observe("a", 5000L)
        assertEquals(5001L, sequencer.next("a").sequence)
        sequencer.observe("a", 10L)
        assertEquals(5002L, sequencer.next("a").sequence)
    }
}
