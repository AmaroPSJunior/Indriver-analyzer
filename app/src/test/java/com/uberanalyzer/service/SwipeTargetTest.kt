package com.uberanalyzer.service

import org.junit.Assert.*
import org.junit.Test

class SwipeTargetTest {
    @Test fun usesActualRowInRightAndBottomSplitWindows() {
        val gesture = SwipeTarget.plan(SwipeTarget.Window(500, 400, 1000, 1200), 850, 1000, 2000)!!
        assertEquals(600f, gesture.startX)
        assertEquals(960f, gesture.endX)
        assertEquals(850f, gesture.y)
    }
    @Test fun rejectsMissingStaleAndOffscreenTargets() {
        val window = SwipeTarget.Window(0, 300, 500, 900)
        assertNull(SwipeTarget.plan(window, null, 1000, 2000))
        assertNull(SwipeTarget.plan(window, 500, 1000, 5001))
        assertNull(SwipeTarget.plan(window, 200, 1000, 2000))
        assertNull(SwipeTarget.plan(window, 900, 1000, 2000))
    }
    @Test fun followsChangedRowInsteadOfOldIndexRatio() {
        val window = SwipeTarget.Window(0, 0, 1080, 2400)
        assertEquals(1100f, SwipeTarget.plan(window, 1100, 1000, 2000)!!.y)
        assertEquals(550f, SwipeTarget.plan(window, 550, 3000, 3500)!!.y)
    }
}
