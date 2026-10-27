package com.prism.music

import com.prism.music.playback.MixEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/** Beat-grid nudges move the grid without breaking its math. */
class GridOffsetTest {
    @Test
    fun outroWrapsIntoOnePeriod() {
        // 500 ms beats: 100 ms late is the same grid as 400 ms early.
        assertEquals(100.0, MixEngine.griddedOutroBeat(0.0, 100, 500.0), 1e-9)
        assertEquals(400.0, MixEngine.griddedOutroBeat(0.0, -100, 500.0), 1e-9)
        assertEquals(250.0, MixEngine.griddedOutroBeat(250.0, 0, 500.0), 1e-9)
    }

    @Test
    fun firstBeatNeverGoesBeforeZero() {
        assertEquals(0L, MixEngine.griddedFirstBeat(3, -100))
        assertEquals(503L, MixEngine.griddedFirstBeat(500, 3))
    }

    @Test
    fun badPeriodPassesThrough() {
        assertEquals(123.0, MixEngine.griddedOutroBeat(123.0, 50, 0.0), 1e-9)
    }
}
