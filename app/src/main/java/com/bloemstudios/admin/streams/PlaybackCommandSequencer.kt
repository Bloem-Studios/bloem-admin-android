package com.bloemstudios.admin.streams

import kotlinx.serialization.Serializable
import java.util.UUID

/** Identifies one playback command. The server refuses a sequence below the latest it applied. */
@Serializable
data class CommandIdentity(val command_id: String, val sequence: Long)

/**
 * Allocates command identities for one owner (a view model). Sequences follow the wall clock so they
 * keep rising across app restarts, and rise by at least one per session within a run. A failed
 * command is not retried here: the next attempt asks for a new identity.
 */
class PlaybackCommandSequencer(private val clock: () -> Long = System::currentTimeMillis) {
    private val last = HashMap<String, Long>()

    @Synchronized
    fun next(sessionId: String): CommandIdentity {
        val previous = last[sessionId] ?: 0L
        val sequence = minOf(MAX_SEQUENCE, maxOf(clock(), previous + 1))
        last[sessionId] = sequence
        return CommandIdentity(UUID.randomUUID().toString(), sequence)
    }

    /** The server reported [latest] as applied for this key; the next allocation goes above it. */
    @Synchronized
    fun observe(sessionKey: String, latest: Long) {
        last[sessionKey] = maxOf(last[sessionKey] ?: 0L, minOf(latest, MAX_SEQUENCE))
    }

    private companion object {
        /** 2^53 - 1: the largest sequence every client can represent exactly. */
        const val MAX_SEQUENCE = 9007199254740991L
    }
}
