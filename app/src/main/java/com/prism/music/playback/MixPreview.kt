package com.prism.music.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the next automatic transition looks like: the blend MixEngine planned, if any.
 * Pure data for the Queue's transition card (PR1: see the blend, steer nothing).
 */
data class TransitionPreview(
    val fromId: String,
    val toId: String,
    /** Where in the ending song the blend starts. */
    val startMs: Long,
    /** How long the overlap lasts. */
    val lengthMs: Long,
    /** Where in the next song it starts playing. */
    val inMs: Long,
    /** The next song's speed while they overlap (its tempo matched to the ending song's); 1 without beat matching. */
    val ratio: Float,
    /** The two songs' beats are lined up. */
    val beats: Boolean,
    /** Short human reason, e.g. "harmonic keys · beats locked" or "over the fade-out". */
    val reason: String,
)

/**
 * The automatic transition's public state: the planned blend, if any, and why the current song
 * isn't blended when it isn't. Fed by MixEngine, read by the Queue card.
 *
 * Queue-scoped in spirit like DJ mode's state: a new song (or a new plan) replaces whatever was here.
 */
class MixPreview {
    private val _preview = MutableStateFlow<TransitionPreview?>(null)
    val preview: StateFlow<TransitionPreview?> = _preview

    private val _noBlendReason = MutableStateFlow<String?>(null)
    val noBlendReason: StateFlow<String?> = _noBlendReason

    internal fun publish(p: TransitionPreview) {
        _preview.value = p
        _noBlendReason.value = null
    }

    internal fun noBlend(why: String) {
        _preview.value = null
        _noBlendReason.value = why
    }

    internal fun clear() {
        _preview.value = null
        _noBlendReason.value = null
    }
}
