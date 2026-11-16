package com.bloemstudios.admin.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The rules for when the lock screen opens the system prompt by itself.
class LockPromptTrackerTest {
    @Test
    fun `the prompt opens once for a new lock`() {
        val tracker = LockPromptTracker()

        assertTrue(tracker.takeAutoPrompt())
        assertFalse(tracker.takeAutoPrompt())
    }

    @Test
    fun `it does not reopen after a cancel`() {
        val tracker = LockPromptTracker()
        tracker.takeAutoPrompt()

        tracker.onPromptEnded(cancelledBySystem = false)

        assertFalse(tracker.takeAutoPrompt())
    }

    @Test
    fun `it opens again after returning from the background`() {
        val tracker = LockPromptTracker()
        tracker.takeAutoPrompt()
        tracker.onPromptEnded(cancelledBySystem = false)

        tracker.onStopped(changingConfigurations = false)

        assertTrue(tracker.takeAutoPrompt())
    }

    @Test
    fun `a stop for a configuration change opens nothing`() {
        val tracker = LockPromptTracker()
        tracker.takeAutoPrompt()
        tracker.onPromptEnded(cancelledBySystem = false)

        tracker.onStopped(changingConfigurations = true)

        assertFalse(tracker.takeAutoPrompt())
    }

    @Test
    fun `no second prompt while one is showing, even after a recreate`() {
        val tracker = LockPromptTracker()
        tracker.takeAutoPrompt()

        tracker.onStopped(changingConfigurations = true)

        assertFalse(tracker.takeAutoPrompt())
    }

    @Test
    fun `a stop while the prompt is up does not reopen it when the person backs out`() {
        val tracker = LockPromptTracker()
        tracker.takeAutoPrompt()

        // The screen-lock check of API 26 to 29 is an activity of its own.
        tracker.onStopped(changingConfigurations = false)
        tracker.onPromptEnded(cancelledBySystem = false)

        assertFalse(tracker.takeAutoPrompt())
    }

    @Test
    fun `a prompt the system closed because the app left counts as a new lock`() {
        val tracker = LockPromptTracker()
        tracker.takeAutoPrompt()

        tracker.onStopped(changingConfigurations = false)
        tracker.onPromptEnded(cancelledBySystem = true)

        assertTrue(tracker.takeAutoPrompt())
    }

    @Test
    fun `a system cancel with no stop does not reopen it`() {
        val tracker = LockPromptTracker()
        tracker.takeAutoPrompt()

        tracker.onPromptEnded(cancelledBySystem = true)

        assertFalse(tracker.takeAutoPrompt())
    }

    @Test
    fun `the button is not held back by a prompt that went with the app`() {
        val tracker = LockPromptTracker()
        tracker.takeAutoPrompt()
        tracker.onStopped(changingConfigurations = false)

        // No answer ever came: the next automatic prompt waits for one, the button does not.
        assertFalse(tracker.takeAutoPrompt())
        tracker.takeManualPrompt()
        tracker.onPromptEnded(cancelledBySystem = false)
        assertFalse(tracker.takeAutoPrompt())
    }

    @Test
    fun `an unlock ends the lock`() {
        val tracker = LockPromptTracker()
        tracker.takeAutoPrompt()

        tracker.onUnlocked()

        assertFalse(tracker.takeAutoPrompt())
    }
}
