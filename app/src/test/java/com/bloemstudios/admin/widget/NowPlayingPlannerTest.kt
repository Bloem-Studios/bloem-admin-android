package com.bloemstudios.admin.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one rule that turns the exact size a launcher gives into a layout: cards follow the height, detail follows the width, no band is left. */
class NowPlayingPlannerTest {
    private fun plan(w: Float, h: Float, rows: Int, scale: Float = 1f) = NowPlayingPlanner.plan(w, h, rows, scale)

    @Test fun smallIsTheCountAndTheTopStreamOnly() {
        val plan = plan(158f, 158f, rows = 4)
        assertTrue(plan.small)
        assertFalse(plan.header)
    }

    @Test fun aWideShortWidgetHasNoHeaderAndOneCompactCard() {
        val plan = plan(320f, 62f, rows = 4)
        assertFalse(plan.header)
        assertEquals(NpTier.Compact, plan.tier)
        assertEquals(1, plan.visible)
        assertTrue("the rest scrolls", plan.scrolls)
    }

    @Test fun aFourByTwoShowsTwoCompactCardsUnderASlimHeader() {
        val plan = plan(320f, 112f, rows = 4)
        assertTrue(plan.header)
        assertTrue(plan.slimHeader)
        assertEquals(NpTier.Compact, plan.tier)
        assertEquals(2, plan.visible)
        assertTrue(plan.scrolls)
    }

    @Test fun aFourByFourShowsThreeRoomyCardsAndTheTotals() {
        val plan = plan(320f, 276f, rows = 4)
        assertEquals(NpTier.Roomy, plan.tier)
        assertEquals(3, plan.visible)
        assertFalse(plan.slimHeader)
        assertEquals("the rest scrolls", true, plan.scrolls)
        assertTrue(plan.footer != NpFooter.None)
    }

    @Test fun aTabletShowsTheWholePathOnEveryCard() {
        val plan = plan(520f, 420f, rows = 4)
        assertEquals(NpTier.Full, plan.tier)
        assertTrue(plan.showBitrate)
    }

    @Test fun narrowAndTallKeepsRoomyCardsWithoutTheBitrate() {
        val plan = plan(158f, 276f, rows = 4)
        assertFalse(plan.small)
        assertEquals(NpTier.Roomy, plan.tier)
        assertFalse(plan.showBitrate)
    }

    @Test fun theNumberOfCardsFollowsTheHeightOneTwoThenMore() {
        val rows = 6
        val counts = listOf(62f, 112f, 150f, 200f, 276f, 400f).map { plan(320f, it, rows).visible }
        assertEquals("never fewer cards as the widget grows taller", counts.sorted(), counts)
        assertEquals(1, counts.first())
        assertTrue(counts.last() >= 3)
    }

    @Test fun fewerStreamsThanFitAreNotScrolledAndFillTheHeight() {
        val plan = plan(320f, 276f, rows = 1)
        assertEquals(1, plan.visible)
        assertFalse(plan.scrolls)
        // One card, the header, and the stats card take the height: nothing is left as a band.
        val used = 8f + 22f + plan.cardHeight + 4f + plan.footerHeight
        assertTrue("left over: ${276f - used}", 276f - used < 20f)
    }

    @Test fun theListIsExactlyAsTallAsTheCardsItShows() {
        val plan = plan(320f, 276f, rows = 6)
        val expected = plan.visible * plan.cardHeight + (plan.visible - 1) * Page.GAP
        assertEquals(expected, plan.listHeight(), 0.001f)
    }

    @Test fun theLayoutNeverNeedsMoreHeightThanTheWidgetHas() {
        for (h in listOf(62f, 80f, 112f, 150f, 200f, 276f, 330f, 420f)) {
            for (w in listOf(158f, 200f, 250f, 320f, 400f, 520f)) {
                for (rows in listOf(1, 2, 4, 9)) {
                    val p = plan(w, h, rows)
                    if (p.small) continue
                    val header = if (!p.header) 0f else if (p.slimHeader) NowPlayingPlanner.SLIM_HEADER + NowPlayingPlanner.SLIM_GAP else Page.HEADER + Page.GAP.toFloat()
                    val footer = if (p.footer == NpFooter.None) 0f else p.footerHeight + Page.GAP
                    val total = 2 * Page.PAD_V + header + p.listHeight() + footer
                    // A card that cannot fit at all (a very short widget) is the one case allowed to be taller: it is one whole card.
                    if (p.visible > 1 || p.footer != NpFooter.None) assertTrue("w=$w h=$h rows=$rows needs $total", total <= h + 0.5f)
                }
            }
        }
    }

    @Test fun biggerTextMakesTheCardsTallerSoNoLineIsCutOff() {
        val normal = plan(320f, 276f, rows = 4, scale = 1f)
        val big = plan(320f, 276f, rows = 4, scale = 1.3f)
        assertTrue(big.cardHeight > normal.cardHeight || big.visible < normal.visible || big.tier != normal.tier)
    }

    @Test fun noStreamsMeansNoCards() {
        val plan = plan(320f, 276f, rows = 0)
        assertEquals(0, plan.visible)
    }

    @Test fun theGenericPlannerFollowsTheSameRulesForAnyWidget() {
        val tiers = listOf(TierSpec(78f, 220f), TierSpec(41f, 190f), TierSpec(74f, 0f))
        val medium = ListPlanner.plan(320f, 112f, rows = 4, tiers)
        assertEquals("two compact cards on a 4 x 2", 2, medium.visible)
        assertEquals(1, medium.tier)
        val narrow = ListPlanner.plan(158f, 276f, rows = 4, tiers)
        assertEquals("a narrow widget uses its narrow layout", 2, narrow.tier)
    }
}
