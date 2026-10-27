package com.prism.music.playback

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the set: exactly what's on the speaker (both players through a blend,
 * EQ and all), via AudioPlaybackCapture scoped to our own UID — no mic, no other
 * apps. API 29+: every entry point below that refuses politely.
 *
 * The framework wants RECORD_AUDIO even for our own playback; the manifest
 * already has it (song recognition) and the card asks at first use. Files land
 * in Music/Prism sets as WAV (no encoder dependency): about 10 MB/min.
 */
class SetRecorder(private val app: Context) {
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording

    private val _startedAtMs = MutableStateFlow(0L)
    val startedAtMs: StateFlow<Long> = _startedAtMs

    @Volatile private var stopAsked = false
    private var thread: Thread? = null
    private var lastUri: Uri? = null

    /** Starts recording our own playback. False when unsupported, busy, or it failed. */
    @Synchronized
    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < 29 || _recording.value) return false
        val session = try {
            Engine(app).open()
        } catch (e: Exception) {
            null
        } ?: return false
        stopAsked = false
        lastUri = null
        thread = Thread({ pumpLoop(session) }, "prism-set-recorder").also { it.start() }
        _recording.value = true
        _startedAtMs.value = System.currentTimeMillis()
        return true
    }

    /** Stops and keeps the file: its Uri, or null when nothing worth keeping came out. */
    @Synchronized
    fun stop(): Uri? {
        if (!_recording.value) return null
        stopAsked = true
        val t = thread
        thread = null
        runCatching { t?.join(3000) }
        _recording.value = false
        return lastUri
    }

    /** Best-effort stop for service teardown. */
    fun release() {
        runCatching { stop() }
    }

    private fun pumpLoop(s: Engine.Session) {
        try {
            val shorts = ShortArray(s.framesPerRead)
            val bytes = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            while (!stopAsked) {
                val n = s.record.read(shorts, 0, shorts.size)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION) break else continue
                }
                bytes.clear()
                for (i in 0 until n) bytes.putShort(shorts[i])
                s.out.write(bytes.array(), 0, n * 2)
                s.dataBytes += n * 2
            }
        } catch (e: Exception) {
            // A partial take is still playable: finalize whatever landed.
        } finally {
            finalize(s)
        }
    }

    private fun finalize(s: Engine.Session) {
        try {
            runCatching { s.record.stop() }
            s.record.release()
            runCatching { s.out.close() }
            if (s.dataBytes > 0 && patchAndPublish(s)) {
                // lastUri set inside.
            } else {
                runCatching { s.cache.delete() }
                lastUri = null
            }
        } catch (e: Exception) {
            lastUri = null
        }
    }

    /** Patches the WAV header, moves the take into Music/Prism sets, and remembers it. */
    private fun patchAndPublish(s: Engine.Session): Boolean {
        return try {
            RandomAccessFile(s.cache, "rw").use { it.seek(0); it.write(Engine.wavHeader(s.dataBytes)) }
            val name = "Prism set " + SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date(_startedAtMs.value)) + ".wav"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Prism sets")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = app.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            try {
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    s.cache.inputStream().use { it.copyTo(out) }
                } ?: run {
                    app.contentResolver.delete(uri, null, null)
                    return false
                }
            } catch (e: Exception) {
                runCatching { app.contentResolver.delete(uri, null, null) }
                return false
            }
            Engine(app).markPending(uri, false)
            runCatching { s.cache.delete() }
            lastUri = uri
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Everything that touches API 29-only classes, opened synchronously (fast: config + buffers). */
    private class Engine(private val app: Context) {
        class Session(
            val record: AudioRecord,
            val out: FileOutputStream,
            val cache: File,
            val framesPerRead: Int,
            var dataBytes: Long = 0,
        )

        @RequiresApi(29)
        fun open(): Session? {
            // Records to a cache take first (a MediaStore FD can't be seeked for the header patch).
            val dir = File(app.cacheDir, "set-takes").also { it.mkdirs() }
            val cache = File(dir, "take.wav")
            val config = PlaybackCapture.ownUid()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return null
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 4)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            val out = try {
                FileOutputStream(cache).also { it.write(ByteArray(44)) }
            } catch (e: Exception) {
                record.release()
                return null
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { out.close() }
                record.release()
                runCatching { cache.delete() }
                return null
            }
            return Session(record, out, cache, (minBuf * 2 / FRAME_BYTES).coerceAtLeast(512))
        }

        fun markPending(uri: Uri, pending: Boolean) {
            val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, if (pending) 1 else 0) }
            app.contentResolver.update(uri, values, null, null)
        }

        companion object {
            private const val SAMPLE_RATE = 44100
            private const val FRAME_BYTES = 2 * 2 // stereo 16-bit

            /** A 44-byte WAV header for [dataBytes] of 44.1 kHz stereo 16-bit PCM, little-endian. */
            fun wavHeader(dataBytes: Long): ByteArray {
                val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                b.put("RIFF".toByteArray())
                b.putInt((36 + dataBytes).toInt())
                b.put("WAVE".toByteArray())
                b.put("fmt ".toByteArray())
                b.putInt(16)
                b.putShort(1) // PCM
                b.putShort(2) // stereo
                b.putInt(SAMPLE_RATE)
                b.putInt(SAMPLE_RATE * FRAME_BYTES)
                b.putShort(FRAME_BYTES.toShort())
                b.putShort(16) // bits
                b.put("data".toByteArray())
                b.putInt(dataBytes.toInt())
                return b.array()
            }
        }
    }
}
