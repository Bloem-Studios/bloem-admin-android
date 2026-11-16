package com.bloemstudios.admin.dashboard

import com.bloemstudios.admin.ui.theme.VeilMotionTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendMotionTest {
    @Test fun `reduced motion draws charts in no time`() {
        assertEquals(0, chartRevealMillis(reducedMotion = true))
        assertEquals(0, chartRevealMillis(reducedMotion = true, motion = VeilMotionTokens(sheetMillis = 900)))
    }

    @Test fun `normal motion reveals a chart over the sheet duration`() {
        assertEquals(VeilMotionTokens().sheetMillis, chartRevealMillis(reducedMotion = false))
        assertTrue(chartRevealMillis(reducedMotion = false) > 0)
    }
}
