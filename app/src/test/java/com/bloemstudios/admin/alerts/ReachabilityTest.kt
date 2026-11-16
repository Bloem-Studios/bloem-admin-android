package com.bloemstudios.admin.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachabilityTest {
    private fun step(state: ReachabilityState, reachable: Boolean, alertsOn: Boolean = true, now: Long = 1_000L) =
        Reachability.next(state, reachable, alertsOn, now)

    @Test fun oneMissedCheckIsNotAnAlert() {
        val result = step(ReachabilityState(), reachable = false)
        assertEquals(AlertEvent.None, result.event)
        assertEquals(1, result.state.failures)
        assertFalse(result.state.downNotified)
    }

    @Test fun twoMissedChecksInARowRaiseOneDownAlert() {
        val first = step(ReachabilityState(), reachable = false, now = 1_000L)
        val second = step(first.state, reachable = false, now = 2_000L)
        assertEquals(AlertEvent.Down, second.event)
        assertTrue(second.state.downNotified)
        // The streak started at the first miss, not at the alert.
        assertEquals(1_000L, second.state.failingSince)
    }

    @Test fun aLongOutageDoesNotRepeatTheAlert() {
        var state = ReachabilityState()
        val events = (1..10).map { n -> step(state, reachable = false, now = n * 1_000L).also { state = it.state }.event }
        assertEquals(1, events.count { it == AlertEvent.Down })
        assertEquals(AlertEvent.Down, events[1])
    }

    @Test fun aFlappingServerNeverAlerts() {
        var state = ReachabilityState()
        val events = listOf(false, true, false, true, false, true).map { up ->
            step(state, reachable = up).also { state = it.state }.event
        }
        assertTrue(events.all { it == AlertEvent.None })
        assertEquals(0, state.failures)
    }

    @Test fun recoveryAfterAnAlertRaisesOneBackNoteAndResetsTheStreak() {
        val down = step(step(ReachabilityState(), false).state, false)
        val back = step(down.state, reachable = true)
        assertEquals(AlertEvent.Back, back.event)
        assertEquals(ReachabilityState(), back.state)
        assertEquals(AlertEvent.None, step(back.state, reachable = true).event)
    }

    @Test fun recoveryBeforeAnyAlertIsSilent() {
        val oneMiss = step(ReachabilityState(), false)
        assertEquals(AlertEvent.None, step(oneMiss.state, true).event)
    }

    @Test fun withAlertsOffTheCountersMoveButNothingIsRaised() {
        var state = ReachabilityState()
        repeat(3) { state = step(state, reachable = false, alertsOn = false).also { assertEquals(AlertEvent.None, it.event) }.state }
        assertEquals(3, state.failures)
        assertFalse(state.downNotified)
    }

    @Test fun turningAlertsOnDuringAnOutageRaisesTheDownAlertAtTheNextCheck() {
        var state = ReachabilityState()
        repeat(3) { state = step(state, reachable = false, alertsOn = false).state }
        assertEquals(AlertEvent.Down, step(state, reachable = false, alertsOn = true).event)
    }

    @Test fun turningAlertsOffWhileAlertedClearsTheOpenNotificationOnce() {
        val alerted = step(step(ReachabilityState(), false).state, false).state
        val off = step(alerted, reachable = false, alertsOn = false)
        assertEquals(AlertEvent.Clear, off.event)
        assertFalse(off.state.downNotified)
        assertEquals(AlertEvent.None, step(off.state, reachable = false, alertsOn = false).event)
    }

    @Test fun recoveringWhileAlertsAreOffClearsQuietlyInsteadOfSayingBack() {
        val alerted = step(step(ReachabilityState(), false).state, false).state
        assertEquals(AlertEvent.Clear, step(alerted, reachable = true, alertsOn = false).event)
    }

    @Test fun theFailureCountCannotOverflow() {
        val state = step(ReachabilityState(failures = Int.MAX_VALUE), reachable = false).state
        assertEquals(Int.MAX_VALUE, state.failures)
    }

    @Test fun aSuccessClearsTheFailingSince() {
        val result = step(ReachabilityState(failures = 1, failingSince = 5L), reachable = true)
        assertNull(result.state.failingSince)
    }
}
