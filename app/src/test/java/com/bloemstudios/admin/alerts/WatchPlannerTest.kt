package com.bloemstudios.admin.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchPlannerTest {
    private val saved = setOf("a", "b", "c")

    @Test fun nothingWantedMeansNoBackgroundWork() {
        val plan = WatchPlanner.plan(saved, alerted = emptySet(), widgetTargets = emptyList(), tileAdded = false)
        assertTrue(plan.isEmpty)
        assertTrue(plan.checked.isEmpty())
    }

    @Test fun alertedServersAreCheckedButGetNoCounts() {
        val plan = WatchPlanner.plan(saved, alerted = setOf("a"), widgetTargets = emptyList(), tileAdded = false)
        assertEquals(setOf("a"), plan.checked)
        assertEquals(setOf("a"), plan.alerted)
        assertTrue(plan.withCounts.isEmpty())
        assertFalse(plan.isEmpty)
    }

    @Test fun aWidgetForOneServerChecksThatServerWithCounts() {
        val plan = WatchPlanner.plan(saved, emptySet(), listOf("b"), tileAdded = false)
        assertEquals(setOf("b"), plan.checked)
        assertEquals(setOf("b"), plan.withCounts)
        assertTrue(plan.alerted.isEmpty())
    }

    @Test fun anAllServersWidgetChecksEverySavedServer() {
        val plan = WatchPlanner.plan(saved, emptySet(), listOf(null), tileAdded = false)
        assertEquals(saved, plan.checked)
        assertEquals(saved, plan.withCounts)
    }

    @Test fun theTileCoversEveryServerToo() {
        val plan = WatchPlanner.plan(saved, emptySet(), emptyList(), tileAdded = true)
        assertEquals(saved, plan.withCounts)
    }

    @Test fun partsAddUp() {
        val plan = WatchPlanner.plan(saved, alerted = setOf("a"), widgetTargets = listOf("b"), tileAdded = false)
        assertEquals(setOf("a", "b"), plan.checked)
        assertEquals(setOf("b"), plan.withCounts)
    }

    @Test fun aRemovedServerIsNeverChecked() {
        val plan = WatchPlanner.plan(saved, alerted = setOf("gone"), widgetTargets = listOf("gone", "a"), tileAdded = false)
        assertEquals(setOf("a"), plan.checked)
        assertFalse(plan.alerted.contains("gone"))
    }

    @Test fun whenEveryWantedServerIsRemovedThePlanIsEmpty() {
        val plan = WatchPlanner.plan(setOf("a"), alerted = setOf("gone"), widgetTargets = listOf("gone"), tileAdded = false)
        assertTrue(plan.isEmpty)
    }

    @Test fun backgroundWorkIsWantedOnlyForAnAlertedServerAWidgetOrTheTile() {
        assertFalse(WatchPlanner.wantsBackgroundWork(alertedServerIds = emptySet(), widgetCount = 0, tileAdded = false))
        assertTrue(WatchPlanner.wantsBackgroundWork(setOf("a"), 0, false))
        assertTrue(WatchPlanner.wantsBackgroundWork(emptySet(), 1, false))
        assertTrue(WatchPlanner.wantsBackgroundWork(emptySet(), 0, true))
    }
}
