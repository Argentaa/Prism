package com.prism.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * The transition's filter, driven by MixEngine while two songs overlap: a sweep that
 * closes the outgoing song down and opens the incoming one (FILTER style), or a bass
 * cut on what's coming in that lets go near the end (BASS_SWAP). Built on [Biquad]
 * (RBJ cookbook, same as the headphone EQ). With [Job.Kind.NONE] the audio passes
 * through untouched, so other styles cost one buffer copy at most.
 *
 * Engine thread calls [setSweep]; the audio thread applies. Cutoffs move in small
 * quantized steps with a short crossfade, so a sweep never clicks.
 */
@UnstableApi
class TransitionFilterAudioProcessor : BaseAudioProcessor() {
    /** One filter job; [step] quantizes progress 0..1 into [STEPS] slices. */
    data class Job(val kind: Kind, val step: Int) {
        enum class Kind { NONE, LOWPASS_OUT, HIGHPASS_IN, LOWSHELF_IN }
    }

    @Volatile private var wanted = Job(Job.Kind.NONE, 0)
    private var applied: Job? = null
    private var rate = 48_000
    private var channels = 2
    private var float = false

    /** The filters in use, and the ones being faded out (after a change). */
    private var chain: Chain? = null
    private var fading: Chain? = null
    private var fadeLeft = 0
    private var fadeLength = 1

    /** Sets the sweep point; any thread. Rebuilds only when the quantized job changes. */
    fun setSweep(kind: Job.Kind, progress: Float) {
        val step = (progress.coerceIn(0f, 1f) * STEPS).roundToInt()
        val next = Job(kind, if (kind == Job.Kind.NONE) 0 else step)
        if (next != wanted) wanted = next
    }

    /** Back to untouched; any thread. */
    fun clear() {
        wanted = Job(Job.Kind.NONE, 0)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        float = enc == C.ENCODING_PCM_FLOAT
        fadeLength = (rate * 0.005).roundToInt().coerceAtLeast(1)
        applied = null
        chain = null
        fading = null
        return inputAudioFormat
    }

    /** One job's biquads for every channel, with their running state (transposed direct form II). */
    private class Chain(job: Job, rate: Int, private val channels: Int) {
        private val filters = filtersFor(job, rate)
        private val z1 = Array(channels) { DoubleArray(filters.size) }
        private val z2 = Array(channels) { DoubleArray(filters.size) }
        val empty = filters.isEmpty()

        fun process(x: Double, ch: Int): Double {
            var y = x
            val s1 = z1[ch]; val s2 = z2[ch]
            for (k in filters.indices) {
                val f = filters[k]
                val out = f.b0 * y + s1[k]
                s1[k] = f.b1 * y - f.a1 * out + s2[k]
                s2[k] = f.b2 * y - f.a2 * out
                y = out
            }
            return y
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val want = wanted
        if (want !== applied) {
            // Crossfade to the new filters over a few ms, so a sweep never clicks.
            val next = Chain(want, rate, channels)
            fading = chain ?: Chain(Job(Job.Kind.NONE, 0), rate, channels)
            chain = next
            fadeLeft = fadeLength
            applied = want
        }
        val current = chain
        if (fadeLeft <= 0 && (current == null || current.empty)) {
            replaceOutputBuffer(bytes).put(inputBuffer).flip()
            return
        }
        val out = replaceOutputBuffer(bytes)
        val frameBytes = (if (float) 4 else 2) * channels
        val frames = bytes / frameBytes
        for (f in 0 until frames) {
            val fade = if (fadeLeft > 0) fadeLeft.toDouble() / fadeLength else 0.0
            for (ch in 0 until channels) {
                val x = if (float) inputBuffer.float.toDouble() else inputBuffer.short / 32768.0
                var y = current!!.process(x, ch)
                val old = fading
                if (fade > 0 && old != null) y = y * (1 - fade) + old.process(x, ch) * fade
                if (float) out.putFloat(y.toFloat())
                else out.putShort((y * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort())
            }
            if (fadeLeft > 0 && --fadeLeft == 0) fading = null
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush() {
        // A seek: the filters' memory of the old position goes, the job stays.
        applied = null
        chain = null
        fading = null
        fadeLeft = 0
    }

    override fun onReset() {
        onFlush()
    }

    companion object {
        private const val STEPS = 32

        private fun filtersFor(job: Job, rate: Int): Array<Biquad> {
            val p = job.step / STEPS.toDouble()
            // Full at one end of the blend, gone at the other.
            val sweep = { lo: Double, hi: Double -> (lo + (hi - lo) * (1 - p) * (1 - p)).coerceIn(40.0, rate * 0.45) }
            return when (job.kind) {
                Job.Kind.NONE -> emptyArray()
                // Outgoing: full at the start, muffled by the end.
                Job.Kind.LOWPASS_OUT -> listOfNotNull(
                    Biquad.of(EqFilter(EqFilter.Type.LOW_PASS, sweep(200.0, 20000.0), 0.0, 0.7), rate, 1.0)
                ).toTypedArray()
                // Incoming: thin at the start, full by the end.
                Job.Kind.HIGHPASS_IN -> listOfNotNull(
                    Biquad.of(EqFilter(EqFilter.Type.HIGH_PASS, sweep(200.0, 8000.0), 0.0, 0.7), rate, 1.0)
                ).toTypedArray()
                // Incoming bass: cut until three quarters, then let go.
                Job.Kind.LOWSHELF_IN -> {
                    val cut = if (p < 0.75) -12.0 else -12.0 * (1 - (p - 0.75) / 0.25)
                    if (cut >= -0.5) emptyArray()
                    else listOfNotNull(
                        Biquad.of(EqFilter(EqFilter.Type.LOW_SHELF, 150.0, cut, 0.7), rate, 1.0)
                    ).toTypedArray()
                }
            }
        }
    }
}
