package com.prism.music

import com.prism.music.playback.MixEngine
import com.prism.music.playback.Tempo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The automatic transition's planning rules (the Queue card reads these):
 * which branch picks the length, and the reason always says which one.
 */
class MixPlanTest {
    private fun tempo(fadeOutMs: Long = -1, quietIntroMs: Long = -1) = Tempo(
        bpm = 120.0, confidence = 4.0, firstSoundMs = 0L, lastSoundMs = 200_000L,
        firstBeatMs = 200L, outroBeatMs = 190_000L,
        fadeOutMs = fadeOutMs, quietIntroMs = quietIntroMs,
    )

    @Test
    fun unscannedEndingFallsBack() {
        assertEquals(6_000L, MixEngine.autoLengthMs(null, tempo()))
        assertEquals("not scanned yet", MixEngine.autoReason(null, tempo()))
    }

    @Test
    fun fadeOutWins() {
        assertEquals(8_000L, MixEngine.autoLengthMs(tempo(fadeOutMs = 8_000), tempo()))
        assertEquals("over the fade-out", MixEngine.autoReason(tempo(fadeOutMs = 8_000), tempo()))
    }

    @Test
    fun quietIntroCapped() {
        // A 10 s intro only fades the last 4 s (more would cut off the ending's last bars).
        assertEquals(4_000L, MixEngine.autoLengthMs(tempo(), tempo(quietIntroMs = 10_000)))
        assertEquals("over the quiet intro", MixEngine.autoReason(tempo(), tempo(quietIntroMs = 10_000)))
    }

    @Test
    fun hardEdgesTouchBriefly() {
        assertEquals(1_500L, MixEngine.autoLengthMs(tempo(), tempo()))
        assertEquals("short touch", MixEngine.autoReason(tempo(), tempo()))
    }

    @Test
    fun postponeRules() {
        // Put off enough times already, or no room left: 0 (the blend goes ahead or not as-is).
        assertEquals(0L, MixEngine.postponeMs(12_000L, 99, 500.0))
        assertEquals(0L, MixEngine.postponeMs(2_000L, 0, 0.0))
        // Otherwise a whole step, in whole beats of the ending song when beat-matched.
        assertEquals(2_000L, MixEngine.postponeMs(12_000L, 0, 0.0))
        assertEquals(1_500L, MixEngine.postponeMs(12_000L, 0, 500.0))
    }
}
