package com.prism.music.playback

import com.prism.music.data.friendlyMessage
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withTimeoutOrNull
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import com.prism.music.data.prefs.SessionButton
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.guava.future
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.prism.music.MainActivity
import com.prism.music.R
import com.prism.music.container
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import kotlin.math.roundToInt

data class VideoLookup(val id: String?, val lookupFailed: Boolean = false)

@Serializable
private data class SavedQueue(
    val songs: List<Song>, val index: Int, val positionMs: Long, val title: String,
    // Shuffle (with its order) and repeat, so a restart keeps them (#101: shuffle was off again after Prism restarted).
    val shuffle: Boolean = false, val shuffleOrder: List<Int> = emptyList(), val repeat: Int = Player.REPEAT_MODE_OFF,
)

@OptIn(UnstableApi::class)
open class PlaybackService : MediaLibraryService(), Player.Listener {
    private val c by lazy { container }
    lateinit var player: ExoPlayer
        private set
    private lateinit var session: MediaLibrarySession
    private val tree by lazy { MediaTree(c) }
    lateinit var effects: AudioEffects
        private set
    /** Headphone correction (AutoEq): first, so the spatializer and the limiter work on the corrected sound. */
    /** Sing: the song's vocals taken out as it plays (first, on the music as it came from the decoder). */
    private val vocals by lazy { com.prism.music.playback.sing.VocalStemProcessor(c.sing) }
    private val headphoneEq = ParametricEqProcessor()
    private val spatial = SpatialAudioProcessor()
    /** The transition's filter (FILTER/BASS_SWAP styles): sits bypassed otherwise, at most a buffer copy. */
    private val transitionFilter = TransitionFilterAudioProcessor()
    private val normalizer = NormalizerProcessor()
    private var songLoudness: Float? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val likeCommand = SessionCommand("prism.LIKE", Bundle.EMPTY)
    private val shuffleCommand = SessionCommand("prism.SHUFFLE", Bundle.EMPTY)
    private val repeatCommand = SessionCommand("prism.REPEAT", Bundle.EMPTY)
    private val lyricsCommand = SessionCommand("prism.CAR_LYRICS", Bundle.EMPTY)
    private val radioCommand = SessionCommand("prism.RADIO", Bundle.EMPTY)
    private val dislikeCommand = SessionCommand("prism.DISLIKE", Bundle.EMPTY)
    // 1.7.4: buttons the listener can put beside play (Settings → Player → Buttons → Notification and car).
    private val back10Command = SessionCommand("prism.BACK_10", Bundle.EMPTY)
    private val ahead30Command = SessionCommand("prism.AHEAD_30", Bundle.EMPTY)
    private val customCommands get() = listOf(likeCommand, shuffleCommand, repeatCommand, lyricsCommand, radioCommand, dislikeCommand, back10Command, ahead30Command)
    /** What the media session (Android Auto, the notification) sees: the player, with lyrics laid over the title and cover. */
    private lateinit var sessionPlayer: LyricsOverlayPlayer
    private lateinit var carLyrics: CarLyrics
    /** Android Auto's connections to Prism. */
    private val carControllers = HashSet<MediaSession.ControllerInfo>()
    private var playStartedAt = 0L
    private var accumulatedMs = 0L
    private var trackedSong: Song? = null
    /** What [trackedSong] is playing as part of, read when it starts (the queue may change before its play is counted). */
    private var trackedSource: String? = null
    /** The tracked play doesn't count towards taste (an excluded song, playlist or artist). */
    private var trackedExcluded = false
    private val retries = HashMap<String, Int>()
    private var saveJob: Job? = null
    private var extrasJob: Job? = null


    override fun onCreate() {
        super.onCreate()
        PlaybackServices.running = this::class.java
        AudioDiagnostics.init(this)
        // Media3's own audio warnings (spurious AudioTrack timestamps, position jumps…) go into the diagnostics too.
        androidx.media3.common.util.Log.setLogger(object : androidx.media3.common.util.Log.Logger {
            private val system = androidx.media3.common.util.Log.Logger.DEFAULT
            override fun d(tag: String, message: String, throwable: Throwable?) = system.d(tag, message, throwable)
            override fun i(tag: String, message: String, throwable: Throwable?) = system.i(tag, message, throwable)
            override fun w(tag: String, message: String, throwable: Throwable?) {
                system.w(tag, message, throwable)
                AudioDiagnostics.log("media3", "W $tag: $message" + (throwable?.let { " ($it)" } ?: ""))
            }
            override fun e(tag: String, message: String, throwable: Throwable?) {
                system.e(tag, message, throwable)
                AudioDiagnostics.log("media3", "E $tag: $message" + (throwable?.let { " ($it)" } ?: ""))
            }
        })
        effects = AudioEffects(this)
        // Lossless mode keeps hi-res files (24-bit FLAC) at full resolution instead of rounding to 16-bit.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput || c.settings.current.lossless)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    // The visualizer's tap last (1.7.4): what you hear; it passes the sound through untouched and
                    // only copies it while a visualizer is on screen.
                    .setAudioProcessors(arrayOf(vocals, headphoneEq, spatial, normalizer, transitionFilter, androidx.media3.exoplayer.audio.TeeAudioProcessor(VisualizerTap)))
                    .build()

            override fun buildAudioRenderers(
                context: Context, extensionRendererMode: Int, mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener, out: ArrayList<androidx.media3.exoplayer.Renderer>,
            ) {
                super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
                FlacFloatGuardRenderer.install(out, context, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener)
            }
        }
        player = ExoPlayer.Builder(this, renderers)
            // Start (and resume after a switch to video) once 1 s is buffered rather than 2.5 s. Ahead of that, the whole
            // song: it used to keep only 30–60 s, so a signal drop longer than that (a tunnel, a lift, a weak spot) stalled
            // a streamed song even though the rest of it is only a few MB. Kept loading in one go (min = max), and the
            // song cache keeps what's loaded. 32 MB at most, so a music video (far bigger per minute) stays a minute or two.
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(WHOLE_SONG_MS, WHOLE_SONG_MS, 1_000, 2_000)
                    .setTargetBufferBytes(32 * 1024 * 1024)
                    .build()
            )
            .setMediaSourceFactory(
                PrismMediaSourceFactory(
                    c.playbackDataSourceFactory(), c.videoDataSourceFactory(),
                    androidx.media3.datasource.DefaultDataSource.Factory(this),
                    localAudio = { item -> losslessFileFor(item) },
                    liveDsf = c.liveDataSourceFactory(),
                )
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        // Matching the display to the video's frame rate also throttled the whole
        // app (lyrics included) to 60 Hz; the UI should stay at the panel's full rate.
        player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        // Shuffled, Previous goes back to the song played last (see HistoryShuffleOrder).
        player.setShuffleOrder(HistoryShuffleOrder(IntArray(0)) { player.currentMediaItemIndex })
        player.addListener(this)
        player.addAnalyticsListener(StatsListener())
        player.addAnalyticsListener(DiagnosticsListener())
        player.skipSilenceEnabled = c.settings.current.skipSilence

        sessionPlayer = LyricsOverlayPlayer(player)
        carLyrics = CarLyrics(this, c, player, sessionPlayer, scope)
        carLyrics.start()
        session = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            // Covers for the notification, lock screen and car come through Prism's image loader, so saved ones show offline (#54).
            .setBitmapLoader(androidx.media3.session.CacheBitmapLoader(CoilBitmapLoader(this, scope)))
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, com.prism.music.PrismActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()
        // Wear OS (a watch's media controls) has its own keys for keeping the skip
        // buttons: without them it puts the first custom buttons (like, shuffle) where
        // previous/next go. Media3 only sets the phone/Auto equivalents. Set after build():
        // only a change of session extras reaches the platform session's extras.
        session.setSessionExtras(Bundle().apply {
            putBoolean("android.support.wearable.media.extra.RESERVE_SLOT_SKIP_TO_PREVIOUS", true)
            putBoolean("android.support.wearable.media.extra.RESERVE_SLOT_SKIP_TO_NEXT", true)
        })
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply {
            setSmallIcon(R.drawable.ic_notification)
        })
        // Keep the media notification (and the system media controls) up for a
        // paused or restored queue, not only while audio is actively playing.
        setShowNotificationForIdlePlayer(MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)

        effects.attach(player.audioSessionId)
        c.onStreamResolved = { id -> scope.launch { applyStreamStats(id) } }
        scope.launch { c.settings.flow.map { it.eq }.distinctUntilChanged().collect { effects.apply(it) } }
        scope.launch { c.settings.flow.map { it.skipSilence }.distinctUntilChanged().collect { player.skipSilenceEnabled = it } }
        scope.launch { c.settings.flow.map { it.audioDiagnostics }.distinctUntilChanged().collect { on -> AudioDiagnostics.setEnabled(on) { AudioDiagnostics.about(c) } } }
        // The buttons beside play follow Settings → Player → Buttons as soon as they're changed.
        scope.launch { c.settings.flow.map { it.sessionButtons }.distinctUntilChanged().drop(1).collect { updateButtons() } }
        // The waveform progress bar picked: the playing song (and what's next) get their outlines now, not at the next song.
        scope.launch {
            c.settings.flow.map { it.scrubberStyle == com.prism.music.data.prefs.ScrubberStyle.WAVE }.distinctUntilChanged().drop(1).collect { on ->
                if (on && ::player.isInitialized) scanAhead(player.currentMediaItem, *upcoming(QUEUE_AHEAD).toTypedArray())
            }
        }
        scope.launch { c.settings.flow.map { it.autoplay }.distinctUntilChanged().drop(1).collect { onAutoplayChanged(it) } }
        // "Keep playing videos" switched off: songs already lined up as their videos go back to songs.
        scope.launch { c.settings.flow.map { it.videoContinues }.distinctUntilChanged().drop(1).collect { if (it) armNextVideo() else disarmVideos() } }
        scope.launch { c.settings.flow.map { it.spatial to it.spatialAmount }.distinctUntilChanged().collect { (on, amount) -> spatial.configure(on, amount) } }
        scope.launch { c.settings.flow.map { it.normalize }.distinctUntilChanged().collect { applyNormalization() } }
        // Prism's own volume (#116), when the player's slider is set to it; full otherwise (the phone's volume rules).
        scope.launch {
            c.settings.flow.map { if (it.volumeControl == com.prism.music.data.prefs.VolumeControl.PRISM) it.appVolume else 1f }.distinctUntilChanged()
                .collect { NormalizerProcessor.appVolume = NormalizerProcessor.volumeFor(it) }
        }
        // The correction for whatever headphones are playing (none for the speaker, the car, or anything not picked for).
        scope.launch {
            c.headphoneEq.active.collect { b ->
                headphoneEq.configure(b?.profile, b?.strength ?: 1f)
            }
        }
        scope.launch { c.library.likedIds.collect { updateButtons() } }
        // Sing: getting the playing and next songs ready, and the key moved for the singer.
        scope.launch {
            kotlinx.coroutines.flow.combine(c.sing.on, c.sing.semitones) { on, st -> on to st }.collect { (on, st) ->
                val pitch = if (on) Math.pow(2.0, st / 12.0).toFloat() else 1f
                if (player.playbackParameters.pitch != pitch) player.playbackParameters = androidx.media3.common.PlaybackParameters(player.playbackParameters.speed, pitch)
                if (on) singAhead()
            }
        }
        // "Get songs ready while Sing is off" switched on: start with the playing song.
        scope.launch { c.settings.flow.map { it.singAlwaysReady }.distinctUntilChanged().collect { if (it) singAhead() } }
        // Smart Shuffle: picks go in when it's turned on, and leave when it's turned off.
        scope.launch { c.queue.smartShuffle.drop(1).collect { on -> if (on) smartAhead() else dropSmartPicks() } }
        // DJ mode: the set is worked out when it's turned on, and again when its energy curve changes.
        scope.launch { c.dj.on.collect { on -> if (on) djAhead() else { djJob?.cancel(); djSoon?.cancel() } } }
        scope.launch { c.dj.rescan.drop(1).collect { if (c.dj.onNow) djAhead() } }
        scope.launch { c.settings.flow.map { it.djFlow }.distinctUntilChanged().drop(1).collect { resequence() } }
        scope.launch {
            c.settings.flow.map { Triple(it.carLyrics, it.carLyricsStyle, it.carLyricsOnPhone) }.distinctUntilChanged().collect {
                carLyrics.refresh(); updateButtons()
            }
        }
        scope.launch {
            var tick = 0
            while (isActive) {
                delay(1000)
                // An episode's place is saved every few seconds while it plays.
                if (++tick % 5 == 0 && player.isPlaying) saveEpisodePosition()
                // Widgets' progress bars move on every 15 s (more often would cost battery for little); with the screen
                // off nobody sees them, so they catch up the moment it's back on instead.
                if (tick % 15 == 0 && player.isPlaying) pushWidgets(progressOnly = true)
                else if (widgetsBehind && screenOn()) pushWidgets()
                // Diagnostics: every 10 s of playing, how far ahead it's loaded and what Prism's own processing cost.
                if (tick % 10 == 0 && player.isPlaying && AudioDiagnostics.enabled) AudioDiagnostics.log(
                    "tick",
                    "at ${player.currentPosition / 1000} s, ${(player.bufferedPosition - player.currentPosition).coerceAtLeast(0) / 1000} s loaded ahead; " +
                        (AudioDiagnostics.summary() ?: "no processing measured"),
                )
                if (player.mediaItemCount > 0) c.nerdStats.value = c.nerdStats.value.copy(
                    bufferMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0),
                    effects = listOfNotNull(
                        effects.activeSummary.takeIf { it.isNotBlank() && it != "None" },
                        headphoneEq.active?.let { "Headphone correction: ${it.name}" },
                    ).joinToString(" · ").ifEmpty { "None" },
                    audioSessionId = player.audioSessionId,
                )
            }
        }
        mix = MixEngine(
            main = player,
            scope = scope,
            tempo = c.mixTempo,
            // DJ mode blends every song into the next, beat-matched, at Crossfade's automatic length.
            // Not while casting: the renderer plays one song at a time (and the phone's own player is muted).
            mixMs = { c.settings.current.let { s -> if ((s.mix || c.dj.onNow) && !c.cast.casting) s.mixSeconds * 1000L else null } },
            auto = { c.settings.current.mixAuto || c.dj.onNow },
            beatMatch = { c.settings.current.mixBeatMatch || c.dj.onNow },
            harmonic = { from, to ->
                if (!c.dj.onNow) null else {
                    val a = c.mixTempo.key(from); val b = c.mixTempo.key(to)
                    if (a?.sure == true && b?.sure == true) MusicKey.clash(a, b) else null
                }
            },
            allowed = ::mayBlend,
            scanFromPhone = { id -> scope.launch { c.mixTempo.measure(id, network = false) } },
            buildTail = ::buildTailPlayer,
            onSettled = ::restoreMusicSpeed,
            transitions = c.mixPreview,
            mainFilter = transitionFilter,
        ).also { it.start(); c.mixControls = it }
        // Prism Connect: the speaker or TV being cast to plays what this player plays, and its volume is the session's.
        sessionPlayer.remote = object : LyricsOverlayPlayer.Remote {
            override val volume: Int? get() = if (c.cast.casting) c.cast.volume else null
            override fun setVolume(v: Int) = c.cast.setVolume(v)
            override fun adjust(delta: Int) = c.cast.adjustVolume(delta)
        }
        c.cast.attach(player, object : com.prism.music.connect.Cast.Hooks {
            override fun beforeCasting() {
                mix.stopBlend()
                if (c.sing.onNow) c.sing.setOn(false)
                // The renderer gets the sound only: a video goes back to the song, and episodes play at 1×.
                videoWanted = false
                if (player.currentMediaItem?.videoModeId != null) scope.launch { setVideoMode(false) }
                if (player.playbackParameters.speed != 1f) player.setPlaybackSpeed(1f)
            }

            override fun afterCasting() {
                val now = player.currentMediaItem?.toSong()
                if (now?.isEpisode == true && episodeSpeed != 1f) player.setPlaybackSpeed(episodeSpeed)
                else if (now != null && !now.isEpisode && !now.isLive && musicSpeed != 1f) player.setPlaybackSpeed(musicSpeed)
            }

            override fun remoteChanged() = sessionPlayer.remoteChanged()
        })
        restoreQueue()
        c.player.attach(this)
    }

    private lateinit var mix: MixEngine

    /**
     * Whether Mix may blend these two: not an album running into its own next track (albums are made to flow on
     * as they are), not live streams or music videos, and not while playing along with others (Listen Together
     * keeps everyone on the same song).
     */
    private fun mayBlend(from: MediaItem, to: MediaItem): Boolean {
        val a = from.toSong() ?: return false
        val b = to.toSong() ?: return false
        if (a.isLive || b.isLive || from.videoModeId != null || to.videoModeId != null) return false
        // Talk doesn't blend.
        if (a.isEpisode || b.isEpisode) return false
        // Singing along: each song plays whole, with its own vocals taken out.
        if (c.sing.onNow) return false
        if (c.cast.casting) return false
        if (c.queue.followingHost || c.together.state.value !is com.prism.music.playback.together.TogetherState.Idle) return false
        // Only while an album plays through (a radio or playlist that puts two of an album's songs side by side blends).
        // An album played as a DJ set is a set: it blends.
        val album = a.album?.id
        return c.dj.onNow || !(album != null && album == b.album?.id && c.queue.source.value.kind == QueueKind.ALBUM)
    }

    /**
     * The second player Mix plays a song's ending on while the next one fades in: the same sources, the same
     * effects (spatial audio, the ending song's normalization, and the equalizer through the shared audio
     * session), and no audio focus of its own.
     */
    private fun buildTailPlayer(shift: ShiftProcessor, tfilter: TransitionFilterAudioProcessor): ExoPlayer {
        val s = c.settings.current
        val tailSpatial = SpatialAudioProcessor().apply { configure(s.spatial, s.spatialAmount) }
        val tailNormalizer = NormalizerProcessor().apply { setGainDb(normalizeGainDb) }
        // The same headphone correction as the main player, so a blend doesn't change the sound.
        val tailEq = ParametricEqProcessor().apply { c.headphoneEq.active.value.let { configure(it?.profile, it?.strength ?: 1f) } }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput || s.lossless)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(shift, tailEq, tailSpatial, tailNormalizer, tfilter))
                    .build()

            override fun buildAudioRenderers(
                context: Context, extensionRendererMode: Int, mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener, out: ArrayList<androidx.media3.exoplayer.Renderer>,
            ) {
                super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
                FlacFloatGuardRenderer.install(out, context, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener)
            }
        }
        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(
                PrismMediaSourceFactory(
                    c.playbackDataSourceFactory(), c.videoDataSourceFactory(),
                    androidx.media3.datasource.DefaultDataSource.Factory(this),
                    localAudio = { item -> losslessFileFor(item) },
                    liveDsf = c.liveDataSourceFactory(),
                )
            )
            // The whole ending (a blend plus its lead-in is under 20 s) is loaded as soon as the tail is made, seconds before
            // it plays along: with only part of it loaded, a slow connection made the tail stop to load mid-blend.
            .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(60_000, 60_000, 1_500, 1_500).build())
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                false,
            )
            .build()
            .also {
                it.skipSilenceEnabled = false
                it.setAudioSessionId(player.audioSessionId)
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveQueue()
        // Settings → Playback → "Stop when swiped away" (#45): closing Prism from Recents ends the music too (some
        // phones, Samsung's among them, otherwise keep it playing with no app on screen).
        if (c.settings.current.stopOnSwipeAway) {
            flushPlayTime()
            saveEpisodePosition()
            pauseAllPlayersAndStopSelf()
            return
        }
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        if (PlaybackServices.running == this::class.java) PlaybackServices.running = null
        flushPlayTime()
        saveEpisodePosition()
        saveQueue()
        player.currentMediaItem?.toSong()?.let { song ->
            com.prism.music.widget.Widgets.update(this, song, false, song.id in c.library.likedIds.value, player.currentPosition, player.duration.takeIf { it > 0 })
        }
        mix.release()
        c.cast.detach(player)
        c.player.detach()
        carLyrics.stop()
        // Released on the effects thread, after anything still queued there: a stalled audio server can't hold this up.
        effects.shutdown()
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ Player events

    /** Id of the song being swapped in place (song ↔ video, audio ↔ live): the same listen goes on. */
    private var swappingInPlace: String? = null

    /** Replaces the item at [idx] with another form of the same song, without counting a new play. */
    private fun swapInPlace(idx: Int, item: MediaItem) {
        swappingInPlace = item.mediaId
        try { player.replaceMediaItem(idx, item) } finally { swappingInPlace = null }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Song ↔ video and the like swap the item but it's still the same listen: the play time
        // keeps adding up, and nothing that starts a new song (history, lyrics, prefetch) runs again.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && mediaItem != null &&
            mediaItem.mediaId == swappingInPlace && mediaItem.mediaId == trackedSong?.id
        ) {
            applyLiveVideo()
            updateButtons()
            scheduleSave()
            return
        }
        flushPlayTime()
        applyLiveVideo()
        val song = mediaItem?.toSong() ?: return
        // Disliked songs are passed over when the queue reaches them on its own (tapping one still plays it), and so are
        // artists the listener doesn't want played when a radio or autoplay brought them (a playlist they picked plays as it is).
        val picked = c.queue.source.value.kind == QueueKind.RADIO || player.currentMediaItemIndex >= c.queue.autoplayStart.value.let { if (it < 0) Int.MAX_VALUE else it }
        val unwanted = c.library.isDisliked(song.id) || (picked && c.exclusions.blocks(song))
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && !c.queue.followingHost && unwanted && player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            return
        }
        // Offline, songs that aren't on the phone are passed over too (#55): they'd play as far as any cached start
        // goes and then cut to the next song.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && !song.isLive && !c.network.online.value && !c.availableOffline(song.id)) {
            // Straight to the next one that is (in play order), not just the next: that one may not be here either.
            val tl = player.currentTimeline
            var i = tl.getNextWindowIndex(player.currentMediaItemIndex, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            while (i != C.INDEX_UNSET) {
                val next = player.getMediaItemAt(i)
                if (next.toSong()?.isLive == true || c.availableOffline(next.mediaId)) { player.seekTo(i, 0); return }
                i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            }
        }
        trackedSong = song
        trackedSource = sourceOf(mediaItem)
        trackedExcluded = c.exclusions.outOfTaste(song, c.queue.source.value.originId)
        // Podcast episodes pick up where they were left, and play at the speed picked for episodes.
        if (song.isEpisode) {
            val resume = c.episodes.positionOf(song.id)
            if (resume > 0 && player.currentPosition < 5_000) player.seekTo(resume)
            // (At 1× while casting: the speaker or TV plays at its own speed.)
            val speed = if (c.cast.casting) 1f else episodeSpeed
            if (player.playbackParameters.speed != speed) player.setPlaybackSpeed(speed)
        } else {
            // Songs at the speed picked with the Speed button (1.7.4; 1× unless picked, always 1× live or casting).
            val speed = if (c.cast.casting || song.isLive) 1f else musicSpeed
            // Not mid-blend: the blend sets the tempo itself, and the picked speed comes back once it settles.
            if (player.playbackParameters.speed != speed && (lastWasEpisode || !mixing())) player.setPlaybackSpeed(speed)
            // Watching a podcast isn't a wish for music videos: music after it starts as the song.
            if (lastWasEpisode) videoWanted = false
        }
        lastWasEpisode = song.isEpisode
        // Already playing: a seamless move on (gapless, or a Mix blend); nothing to time.
        startedLoading = if (player.isPlaying) null else song.id to SystemClock.elapsedRealtime()
        retries.remove(song.id)
        // A queued song that's playing now isn't "next in queue" any more.
        if (song.id in c.queue.userQueued.value) c.queue.userQueued.value = c.queue.userQueued.value - song.id
        if (player.isPlaying) playStartedAt = SystemClock.elapsedRealtime()
        c.nerdStats.value = NerdStats(videoId = song.id, outputDevice = AudioEffects.outputDevice(this))
        c.streamDetails[song.id]?.let { applyStreamStats(song.id) }
        scope.launch(Dispatchers.IO) { c.library.recordPlayed(song) }
        scope.launch { c.bestParts.onSongStarted(song) }
        singAhead()
        djAhead()
        smartAhead()
        loadExtras(song)
        // Audio only (the Song / Video switch is off): nothing plays as its video, whatever lined it up (#48).
        // Posted: swapping the item from inside this callback would report its transition after the swap was over.
        if (!c.settings.current.showVideoSwitch && mediaItem.videoModeId != null && !song.isLive) scope.launch {
            if (!c.settings.current.showVideoSwitch && player.currentMediaItem?.mediaId == song.id) setVideoMode(false)
        }
        // Every new song starts as the song; the track that just finished goes back to audio too,
        // so returning to it doesn't land in its video.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || !c.settings.current.showVideoSwitch) {
            for (i in 0 until player.mediaItemCount) {
                if (i == player.currentMediaItemIndex) continue
                val other = player.getMediaItemAt(i)
                if (other.videoModeId != null) other.toSong()?.let { player.replaceMediaItem(i, it.toMediaItem(null)) }
            }
        }
        continueVideo()
        maybeAutoplay()
        prefetchNext()
        updateButtons()
        carLyrics.refresh()
        scheduleSave()
    }

    /** Podcast episodes' playback speed (the player's ⋯ sets it); music always plays at 1×. */
    var episodeSpeed = 1f
        set(v) {
            field = v
            if (player.currentMediaItem?.toSong()?.isEpisode == true && !c.cast.casting) player.setPlaybackSpeed(v)
        }
    private var lastWasEpisode = false

    /**
     * Songs' playback speed (1.7.4, the Speed button the listener can put under the player); 1× unless they pick another.
     * A crossfade blends at the songs' own speed and settles back to 1×, after which [onPlaybackParametersChanged] puts
     * this speed back.
     */
    var musicSpeed = 1f
        set(v) {
            field = v
            val song = player.currentMediaItem?.toSong() ?: return
            if (!song.isEpisode && !song.isLive && !c.cast.casting) player.setPlaybackSpeed(v)
        }

    private fun mixing() = ::mix.isInitialized && mix.blending

    override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
        if (playbackParameters.speed == 1f) restoreMusicSpeed()
    }

    /**
     * The picked speed back on a song, once nothing else is steering it: after a blend, which ends at 1× either by
     * settling (still marked as blending while it sets 1×) or without changing the speed at all.
     */
    private fun restoreMusicSpeed() {
        if (musicSpeed == 1f || mixing() || player.playbackParameters.speed == musicSpeed) return
        val song = player.currentMediaItem?.toSong() ?: return
        if (!song.isEpisode && !song.isLive && !c.cast.casting) player.setPlaybackSpeed(musicSpeed)
    }

    /** Remembers how far into the playing episode the listener is (see [com.prism.music.data.EpisodeProgress]). */
    private fun saveEpisodePosition() {
        val song = player.currentMediaItem?.toSong()?.takeIf { it.isEpisode } ?: return
        c.episodes.save(song, player.currentPosition, player.duration.takeIf { it > 0 } ?: song.durationSec * 1000L)
    }

    /** The song that just came up and when, until it's heard: how long starting a song takes (logcat tag PrismStream). */
    private var startedLoading: Pair<String, Long>? = null

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (isPlaying) startedLoading?.let { (id, at) ->
            if (id == player.currentMediaItem?.mediaId) android.util.Log.i("PrismStream", "$id playing ${now - at} ms after it came up (${c.streamDetails[id]?.first ?: "?"})")
            startedLoading = null
        }
        if (!isPlaying) saveEpisodePosition()
        pushWidgets()
        if (isPlaying) playStartedAt = now
        else if (playStartedAt > 0) {
            accumulatedMs += now - playStartedAt
            playStartedAt = 0
            scheduleSave()
        }
    }

    /** The song [prefetchNext] last got ready. */
    private var warmedNext: String? = null

    /**
     * The queue changed under the current song (autoplay's suggestions landed, something was queued or moved):
     * if a different song is next now, get that one ready instead. Playing a single song used to leave its first
     * radio song cold, since the suggestions arrive after the song has started.
     */
    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
        val i = player.nextMediaItemIndex
        val next = if (i == C.INDEX_UNSET) null else player.getMediaItemAt(i).mediaId
        if (next != null && next != warmedNext && player.currentMediaItem != null) prefetchNext()
        // Songs added while DJ mode is on (autoplay's, queued ones) join the set; DJ mode's own moves don't count.
        if (c.dj.onNow && !djMoving) {
            djSoon?.cancel()
            djSoon = scope.launch { delay(1_500); djAhead() }
        }
    }

    /** When playback stalled waiting for data (logged with how long, tag PrismStream). */
    private var stalledAt = 0L

    override fun onPlaybackStateChanged(playbackState: Int) {
        val now = SystemClock.elapsedRealtime()
        if (playbackState == Player.STATE_BUFFERING && player.playWhenReady && startedLoading == null) stalledAt = now
        else if (playbackState == Player.STATE_READY && stalledAt > 0) {
            android.util.Log.i("PrismStream", "${player.currentMediaItem?.mediaId} stalled ${now - stalledAt} ms at ${player.currentPosition} ms")
            stalledAt = 0
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        vocals.shuffle = shuffleModeEnabled
        // Shuffle and DJ mode both decide the order: the listener's shuffle wins.
        if (shuffleModeEnabled && c.dj.onNow) c.dj.setOn(false)
        // Shuffle off (from anywhere: the notification, the car) is Smart Shuffle off too.
        if (!shuffleModeEnabled && c.queue.smartShuffle.value) c.queue.smartShuffle.value = false
        updateButtons()
        // Shuffling picks a different next song.
        prefetchNext()
        scheduleSave()
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        vocals.repeatMode = repeatMode
        updateButtons()
        scheduleSave()
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        effects.attach(audioSessionId)
    }

    override fun onPlayerError(error: PlaybackException) {
        val id = player.currentMediaItem?.mediaId ?: return
        // It turned out to be a live stream (a "Listen together" station): play it again as one, from its HLS manifest.
        val live = generateSequence(error as Throwable) { it.cause }.filterIsInstance<com.prism.music.data.stream.LiveStreamException>().firstOrNull()
        if (live != null) {
            val idx = player.currentMediaItemIndex
            val song = player.currentMediaItem?.toSong()
            if (song != null && !song.isLive) {
                swapInPlace(idx, song.copy(isLive = true).toMediaItem())
                applyLiveVideo()
                player.seekToDefaultPosition(idx)
                player.prepare()
                player.play()
                return
            }
        }
        val httpCode = generateSequence(error as Throwable) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        // A music video that won't load gets one more go with its streams asked for afresh (YouTube now and then turns
        // a direct link away, and the next answer comes the other way, through NewPipe); after that it falls back to
        // the song itself — never skip ahead.
        player.currentMediaItem?.videoModeId?.let { videoId ->
            val key = "video:$videoId"
            val tries = retries[key] ?: 0
            val parse = generateSequence(error as Throwable) { it.cause }.any { it is androidx.media3.common.ParserException }
            if (tries < 1 && (httpCode == 403 || httpCode == 410 || parse)) {
                retries[key] = tries + 1
                AudioDiagnostics.log("video", "video $videoId failed (${httpCode ?: "unreadable"}): trying its streams afresh")
                c.streams.invalidate(videoId, refused = true)
                val pos = player.currentPosition
                player.prepare()
                player.seekTo(pos)
                player.play()
                return
            }
            scope.launch { revertVideo(id) }
            return
        }
        val attempts = retries[id] ?: 0
        if (attempts < 2 && (httpCode == 403 || httpCode == 410 || error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        ) {
            retries[id] = attempts + 1
            c.streams.invalidate(id, refused = httpCode == 403)
            val pos = player.currentPosition
            player.prepare()
            player.seekTo(pos)
            player.play()
            return
        }
        // #93: a song YouTube won't play where the listener is, though its music video plays there: the video's sound
        // takes its place (the song stays the song: its lyrics, likes and history are its own).
        val song = player.currentMediaItem?.toSong()
        if (song != null && !song.isVideo && !song.isLocal && !song.isLive && !song.isEpisode && !c.soundFrom.containsKey(id) && unavailableHere(error)) {
            val idx = player.currentMediaItemIndex
            scope.launch {
                val video = videoIdFor(song).id?.takeIf { it != id }
                if (player.currentMediaItemIndex != idx || player.currentMediaItem?.mediaId != id) return@launch
                if (video == null) { failAndSkip(error); return@launch }
                c.soundFrom[id] = video
                AudioDiagnostics.log("song", "$id isn't available here: playing its music video $video's sound")
                c.player.reportError("${song.title} isn't available where you are, so its music video's sound plays")
                player.prepare()
                player.play()
            }
            return
        }
        failAndSkip(error)
    }

    /** Says why a song didn't play, and moves on to the next one. */
    private fun failAndSkip(error: PlaybackException) {
        c.player.reportError((error.cause ?: error).friendlyMessage("Playback failed"))
        if (player.hasNextMediaItem()) {
            scope.launch {
                delay(1500)
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    // ------------------------------------------------------------ Features

    /**
     * Normalization gain for the current song: YouTube's loudness figure says how far the track
     * sits from its reference level, so loud songs come down and quiet ones go up (by up to 6 dB;
     * the limiter in [NormalizerProcessor] keeps those from clipping).
     */
    /** The normalization gain now applied (the ending song's, when a Mix blend's tail takes it over). */
    private var normalizeGainDb = 0f

    /**
     * The playing song is still just starting (loading, or in its first [SETTLE_MS] of sound): a better loudness figure
     * may still set its volume. After that the volume stays put for the rest of the song.
     */
    private fun settling() = player.currentPosition < SETTLE_MS

    private fun applyNormalization() {
        // The song's own measured loudness (integrated LUFS, from [MixTempo]'s scan) is the real figure. YouTube's
        // loudnessDb hardly differs between a loud master and a quiet one, so it only stands in until the song is
        // measured.
        val measured = trackedSong?.id?.let { c.mixTempo.loudness(it) }
        val yt = songLoudness
        val gain = when {
            !c.settings.current.normalize -> 0f
            measured != null -> (TARGET_LUFS - measured).coerceIn(-12f, 6f)
            yt != null -> (-yt).coerceIn(-12f, 6f)
            else -> 0f
        }
        // Once the song is under way, a better figure eases in over two seconds, and a small one isn't worth a change
        // heard mid-song; before its sound starts, the right level is set at once.
        val underWay = player.isPlaying && player.currentPosition > 400
        if (underWay && kotlin.math.abs(gain - normalizeGainDb) < MIN_MIDSONG_CHANGE_DB) return
        normalizer.setGainDb(gain, if (underWay) 2f else 0.1f)
        if (gain != normalizeGainDb) AudioDiagnostics.log("normalize", "gain %.1f dB (%s) at %d s".format(gain, if (measured != null) "measured" else if (yt != null) "YouTube's figure" else "none", player.currentPosition / 1000))
        normalizeGainDb = gain
        c.nerdStats.value = c.nerdStats.value.copy(
            loudnessDb = (measured ?: yt)?.toDouble(), loudnessMeasured = measured != null, normalizationGainDb = gain,
        )
        android.util.Log.i(
            "PrismNormalize",
            (if (measured != null) "measured %.1f LUFS".format(measured) else "YouTube's figure ${yt ?: "unknown"} dB") +
                " -> gain %.1f dB (normalize ${c.settings.current.normalize})".format(gain),
        )
    }

    /**
     * Scans the playing and next songs ([MixTempo]): how their music starts and ends and their beat, for Crossfade, and
     * their loudness, for normalization. From the phone when the song's there (downloaded, cached, a local file),
     * else a small download, on Wi-Fi unless mobile data is allowed. Each song is scanned once.
     *
     * Not until the playing song is under way: a scan fetches a whole song (and asks YouTube for its streams) and
     * decodes it, and run as a song changed it held up that song's own start, which buffered for longer.
     */
    private fun scanAhead(vararg items: MediaItem?) {
        val s = c.settings.current
        // The waveform progress bar is drawn from the scan too (1.7.4).
        val wave = s.scrubberStyle == com.prism.music.data.prefs.ScrubberStyle.WAVE
        if (!s.mix && !s.normalize && !wave) return
        val network = s.mixTempoOnCellular || getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == false
        val songs = items.mapNotNull { it?.toSong() }.filter { !it.isLive && !it.isEpisode }.distinctBy { it.id }
        if (songs.isEmpty()) return
        // Songs scanned before fade-outs and intros were measured are scanned again only for Crossfade's automatic length.
        val edges = s.mix && s.mixAuto
        val playing = player.currentMediaItem?.mediaId
        scanJob?.cancel()
        scanJob = scope.launch {
            if (playing != null) untilUnderWay(playing, UNDER_WAY_MS)
            // YouTube's loudness figure for each queued song not known yet: quick, and enough to start it at the right
            // volume if it comes up before it's been measured (a skip).
            if (s.normalize) for (song in songs) {
                if (song.id == playing || song.isLocal || c.loudness.contains(song.id) || c.mixTempo.loudness(song.id) != null) continue
                withContext(Dispatchers.IO) { runCatching { c.ytm.playerExtras(song.id, c.streams.signatureTimestamp()).loudnessDb } }
                    .getOrNull()?.let { c.loudness.edit().putFloat(song.id, it.toFloat()).apply() }
            }
            // Then measured, the queued songs first (their volume is set as they start); the playing one last (only
            // Crossfade needs it, near its end).
            // With the waveform bar, the playing song goes first: its bar is waiting for it.
            for (song in songs.sortedBy { if (wave) it.id != playing else it.id == playing }) {
                c.mixTempo.measure(song.id, network, edges, wave = wave)
                // Measured while it's just begun: its own figure takes over (later in the song, a jump would be heard).
                if (trackedSong?.id == song.id && settling()) applyNormalization()
            }
        }
    }

    private fun loadExtras(song: Song) {
        extrasJob?.cancel()
        // Remembered loudness applies straight away (and offline); the network refreshes it.
        songLoudness = c.loudness.getFloat(song.id, Float.NaN).takeIf { !it.isNaN() }
        applyNormalization()
        // A file on the phone has nothing on YouTube to fetch or report.
        if (song.isLocal) return
        extrasJob = scope.launch {
            val ex = runCatching { withContext(Dispatchers.IO) { c.ytm.playerExtras(song.id, c.streams.signatureTimestamp()) } }.getOrNull()
            ex?.loudnessDb?.toFloat()?.let { loud ->
                c.loudness.edit().putFloat(song.id, loud).apply()
                // Only while the song is just starting: later, the volume jumping mid-song is worse than a figure
                // that's a little off (it's kept, so the song plays at it from the start next time).
                if (trackedSong?.id == song.id && settling()) { songLoudness = loud; applyNormalization() }
            }
            if (ex != null && c.settings.current.isLoggedIn) {
                withContext(Dispatchers.IO) { runCatching { c.ytm.registerPlayback(ex) } }
            }
        }
    }

    /**
     * With Autoplay on, keeps the queue topped up with YouTube Music radio
     * suggestions as it nears its end: seeded by the song for a single-song
     * play, or by the last track of an album / playlist.
     */
    fun maybeAutoplay() {
        val src = c.queue.source.value
        // A song's radio (Quick picks, "Radio" in a song's menu) keeps going whether or not Autoplay is on: that's what it is.
        val radio = src.isSongRadio
        if ((!c.settings.current.autoplay && !radio) || player.mediaItemCount == 0 || c.queue.followingHost) return
        // No music radio after a podcast episode.
        if (player.currentMediaItem?.toSong()?.isEpisode == true) return
        if (src.kind != QueueKind.SINGLE && !radio && player.repeatMode != Player.REPEAT_MODE_OFF) return
        if (player.mediaItemCount - player.currentMediaItemIndex > 3 || c.queue.autoplayLoading) return
        val seed = src.seedId ?: player.getMediaItemAt(player.mediaItemCount - 1).mediaId.takeIf { it.isNotBlank() } ?: return
        // Where the songs come from (Settings → Playback & sound → Autoplay from): your own music (Prism's radio, made on
        // the phone from your plays, likes, downloads and files), YouTube Music's radio, or both. A song's radio (Quick
        // picks, "Radio") is YouTube's; with no connection, or after a file on the phone, only your own music can follow.
        val offline = !c.network.isOnline()
        val mode = c.settings.current.autoplaySource
        val seedLocal = seed.startsWith(Song.LOCAL_PREFIX)
        val useYours = offline || seedLocal || (!radio && mode != com.prism.music.data.prefs.AutoplaySource.YOUTUBE)
        val useYouTube = !offline && !seedLocal && (radio || mode != com.prism.music.data.prefs.AutoplaySource.YOURS)
        val seeds = (maxOf(0, player.mediaItemCount - 3) until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.filter { it.isNotBlank() }
        c.queue.autoplayLoading = true
        scope.launch {
            val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            val mine = if (!useYours) emptyList() else runCatching {
                withContext(Dispatchers.IO) { c.yourMusic.radio(seeds, existing, 15, offlineOnly = offline) }
            }.onFailure { android.util.Log.w("PrismRadio", "Prism radio failed", it) }.getOrNull().orEmpty().map { it.song }
            // "Your music": YouTube only fills in when your own music has too little to follow with.
            val wantYouTube = useYouTube && (radio || mode != com.prism.music.data.prefs.AutoplaySource.YOURS || mine.size < 5)
            val r = if (!wantYouTube) null else runCatching {
                withContext(Dispatchers.IO) { c.radio(seed, "RDAMVM$seed", continuation = c.queue.autoplayContinuation) }
            }.getOrNull()
            c.queue.autoplayLoading = false
            // Dropped if the queue was replaced, or Autoplay was switched off, while loading.
            if ((r == null && mine.isEmpty()) || c.queue.source.value !== src || (!c.settings.current.autoplay && !radio)) return@launch
            if (r != null) c.queue.autoplayContinuation = r.continuation
            val theirs = r?.songs.orEmpty()
            val songs = when {
                radio || mine.isEmpty() -> theirs
                theirs.isEmpty() -> mine
                // Both: mostly your own music, with two of YouTube's in every five.
                else -> com.prism.music.data.foryou.MadeForYou.interleave(mine, theirs, 20) { it.primaryArtist.lowercase() }
            }
            android.util.Log.i("PrismRadio", "autoplay after $seed: ${mine.size} of yours, ${theirs.size} from YouTube ($mode${if (offline) ", offline" else ""})")
            val add = songs.distinctBy { it.id }.filter { it.id !in existing && !c.library.isDisliked(it.id) && !c.exclusions.blocks(it) }
            if (add.isNotEmpty()) {
                if (c.queue.autoplayStart.value < 0) c.queue.autoplayStart.value = player.mediaItemCount
                player.addMediaItems(add.map { it.toMediaItem() })
            }
        }
    }

    /** Switching Autoplay off takes its suggestions back out of the queue; on, fetches them. */
    private fun onAutoplayChanged(enabled: Boolean) {
        if (enabled) { maybeAutoplay(); return }
        if (c.queue.source.value.isSongRadio) return
        val start = c.queue.autoplayStart.value
        if (start >= 0 && start < player.mediaItemCount) {
            val from = maxOf(start, player.currentMediaItemIndex + 1)
            if (from < player.mediaItemCount) player.removeMediaItems(from, player.mediaItemCount)
        }
        c.queue.autoplayStart.value = -1
        c.queue.autoplayContinuation = null
    }

    /**
     * The song's music video id. Null with [lookupFailed] = true means YouTube
     * couldn't be reached (worth trying again); otherwise there simply isn't one.
     */
    suspend fun videoIdFor(song: Song): VideoLookup {
        // An episode is a YouTube video already: its video is itself.
        if (song.isVideo || song.isEpisode) return VideoLookup(song.id)
        // A file on the phone has no music video, and neither has the listener's own upload (#101: the lookup found the
        // official recording's video for it by title).
        if (song.isLocal || song.isUpload) return VideoLookup(null)
        // A saved video plays offline, so it's known without asking YouTube.
        c.downloads.downloadedVideoId(song.id)?.let { return VideoLookup(it) }
        c.queue.counterparts[song.id]?.let { return VideoLookup(it) }
        if (song.id in c.queue.noVideo) return VideoLookup(null)
        val found = runCatching { withContext(Dispatchers.IO) { c.ytm.findMusicVideo(song) } }
        found.getOrNull()?.let { c.queue.counterparts[song.id] = it; return VideoLookup(it) }
        found.exceptionOrNull()?.let { android.util.Log.w("PrismVideo", "video lookup for ${song.id} failed: $it") }
        if (found.isSuccess) c.queue.noVideo.add(song.id)
        return VideoLookup(null, lookupFailed = found.isFailure)
    }

    /**
     * Gets a song's video ready before it's asked for: the video's streams are
     * resolved (the slow part of switching), and on Wi-Fi the lyric/position
     * offset is measured, so the video button switches straight away.
     */
    fun prefetchVideo(song: Song, videoId: String) {
        if (song.isLocal || videoId in prefetched) return
        prefetched += videoId
        scope.launch(Dispatchers.IO) {
            // Not alongside the song's own start: it was one of the resolves slowing that down.
            c.yieldToPlaying(null)
            runCatching { c.streams.resolve(videoId, withVideo = true) }.onFailure { prefetched -= videoId }
            val metered = getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
            if (metered || videoId == song.id || c.videoSync.cached(song.id, videoId) != null) return@launch
            // Measured once the song is under way: a long video ("Alright" runs three minutes past its song) means
            // minutes of audio to fetch and analyse, which slowed the song's own start when it ran at once.
            withContext(Dispatchers.Main) { untilUnderWay(song.id) }
            runCatching { c.videoSync.lagMs(song.id, videoId) }
        }
    }

    /** Returns once [songId] has played [afterMs] (~10 s; or something else is playing: it was lined up as the next song). */
    private suspend fun untilUnderWay(songId: String, afterMs: Long = 10_000) {
        delay(minOf(3_000, afterMs))
        repeat(60) {
            if (player.currentMediaItem?.mediaId != songId || (player.isPlaying && player.currentPosition > afterMs)) return
            delay(1_000)
        }
    }

    /** The next [n] songs in the order they'll play (shuffled or not). */
    private fun upcoming(n: Int): List<MediaItem> {
        val tl = player.currentTimeline
        val out = ArrayList<MediaItem>(n)
        // An empty queue has no next song (an empty timeline still answers index + 1).
        if (tl.isEmpty) return out
        var i = player.currentMediaItemIndex
        while (out.size < n) {
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (i == C.INDEX_UNSET || i >= tl.windowCount) break
            out += player.getMediaItemAt(i)
        }
        return out
    }

    /** The queued songs' scans and loudness figures, under way (a newer queue replaces it). */
    private var scanJob: kotlinx.coroutines.Job? = null
    /** Whole queued songs being loaded ahead (Settings → Load ahead; a newer queue replaces it). */
    private var preloadJob: kotlinx.coroutines.Job? = null

    /**
     * Resolves the next song's stream while this one plays (the slow part of starting a song), so
     * skipping ahead or moving on starts straight away. Downloaded songs don't need it.
     */
    private fun prefetchNext() {
        val i = player.nextMediaItemIndex
        if (i == C.INDEX_UNSET) return
        songForNextVideo(player.getMediaItemAt(i))
        val next = player.getMediaItemAt(i).mediaId.takeIf { it.isNotBlank() } ?: return
        warmedNext = next
        // The next few queued songs' loudness (for normalization) and edges (for Crossfade), ready well before they
        // play, so each starts at its own volume from its first second. Once this song is under way ([scanAhead]).
        scanAhead(player.currentMediaItem, *upcoming(QUEUE_AHEAD).toTypedArray())
        // Settings → Load ahead: the next song or three loaded whole, once this one is under way.
        val preload = c.settings.current.preload
        preloadJob?.cancel()
        if (preload.songs > 0) {
            val playing = player.currentMediaItem?.mediaId
            val ids = upcoming(preload.songs).map { it.mediaId }.filter { it.isNotBlank() && !c.downloads.isDownloaded(it) }
            preloadJob = scope.launch {
                if (playing != null) untilUnderWay(playing, UNDER_WAY_MS)
                for (id in ids) withContext(Dispatchers.IO) { c.warmUp(id, whole = true, cellular = c.settings.current.preloadCellular) }
            }
        }
        if (next.startsWith(Song.LOCAL_PREFIX)) return
        // Its "Most replayed" curve (for the best part), ready before it plays, and the one after's (a double skip).
        val coming = listOfNotNull(player.getMediaItemAt(i).toSong(), upcoming(2).getOrNull(1)?.toSong())
        scope.launch { coming.forEach { s -> c.bestParts.prefetch(s) } }
        if (c.downloads.isDownloaded(next)) { armNextVideo(); return }
        // The song after it is resolved too (no audio fetched): skipping twice used to land on one that wasn't, which
        // took ~2 s to start. Each song is resolved once either way, only earlier.
        val afterNext = upcoming(2).getOrNull(1)?.mediaId
            ?.takeIf { it.isNotBlank() && !it.startsWith(Song.LOCAL_PREFIX) && !c.downloads.isDownloaded(it) }
        scope.launch(Dispatchers.IO) {
            c.warmUp(next, cellular = c.settings.current.preloadCellular)
            afterNext?.let { c.streams.prefetch(it) }
            // A live stream coming up is lined up as one, so it starts without a failed attempt first.
            if (runCatching { c.streams.isLive(next) }.getOrDefault(false)) withContext(Dispatchers.Main) {
                val at = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == next && it != player.currentMediaItemIndex }
                val song = at?.let { player.getMediaItemAt(it).toSong() }
                if (at != null && song != null && !song.isLive) {
                    player.replaceMediaItem(at, song.copy(isLive = true).toMediaItem(player.getMediaItemAt(at).videoModeId?.let { song.id }))
                }
            }
        }
        armNextVideo()
    }

    /** Music videos whose song is being looked up for [songForNextVideo]. */
    private val findingSongs = HashSet<String>()

    /**
     * "Songs, not videos" everywhere (#80): a music video coming up next (a liked video, an album or playlist's video
     * track) is swapped for its song before it plays, the song's title, album and cover with it; the video stays the
     * Video button's. One lined up as its video on purpose (Keep playing videos) is left alone, and so is one with no
     * song. Offline, a downloaded video keeps its own sound unless its song is on the phone too. (The replaced item
     * changes the timeline, so [prefetchNext] then gets the song ready.)
     */
    private fun songForNextVideo(item: MediaItem) {
        if (!c.player.songsEverywhere() || item.videoModeId != null) return
        val video = item.toSong()?.takeIf { it.isVideo && !it.isLive && !it.isEpisode && !it.isLocal } ?: return
        if (!findingSongs.add(video.id)) return
        scope.launch {
            try {
                val song = c.player.songFor(video) ?: return@launch
                if (!c.network.online.value && !c.availableOffline(song.id)) return@launch
                val at = (0 until player.mediaItemCount).firstOrNull {
                    it != player.currentMediaItemIndex && player.getMediaItemAt(it).let { m -> m.mediaId == video.id && m.videoModeId == null }
                } ?: return@launch
                player.replaceMediaItem(at, song.toMediaItem())
            } finally {
                findingSongs.remove(video.id)
            }
        }
    }

    /**
     * The listener picked the video (and hasn't gone back to the song since). With "Keep playing videos"
     * on, each new song then plays as its video too.
     */
    var videoWanted = false
        set(v) {
            field = v
            if (v) armNextVideo() else disarmVideos()
        }

    private fun keepVideos() = videoWanted && c.settings.current.videoContinues && c.settings.current.showVideoSwitch

    /**
     * A new song started while videos are wanted. The next song is usually lined up as its video
     * already ([armNextVideo]); if it wasn't ready (a quick skip), it switches here. Songs without a
     * video just play as songs, quietly.
     */
    private fun continueVideo() {
        if (!keepVideos()) return
        val item = player.currentMediaItem ?: return
        val song = item.toSong() ?: return
        if (item.videoModeId != null) { watchVideo(song.id); return }
        scope.launch { setVideoMode(true) }
    }

    /** Lines the next song up as its video, so moving on to it is seamless. */
    private fun armNextVideo() {
        if (!keepVideos()) return
        val i = player.nextMediaItemIndex
        if (i == C.INDEX_UNSET) return
        val item = player.getMediaItemAt(i)
        if (item.videoModeId != null) return
        val song = item.toSong() ?: return
        scope.launch {
            val vid = if (song.isLive) song.id else videoIdFor(song).id ?: return@launch
            val at = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return@launch
            val next = player.getMediaItemAt(at)
            if (!keepVideos() || next.mediaId != song.id || next.videoModeId != null) return@launch
            val nextSong = next.toSong() ?: return@launch
            if (failedVideos.remove(vid)) c.streams.invalidate(vid)
            player.replaceMediaItem(at, nextSong.toMediaItem(if (nextSong.isLive) nextSong.id else vid))
            if (!nextSong.isLive) prefetchVideo(nextSong, vid)
        }
    }

    /** Back to songs: anything lined up as a video (other than what's playing) goes back to audio. */
    private fun disarmVideos() {
        for (i in 0 until player.mediaItemCount) {
            if (i == player.currentMediaItemIndex) continue
            val other = player.getMediaItemAt(i)
            if (other.videoModeId != null) other.toSong()?.let { player.replaceMediaItem(i, it.toMediaItem(null)) }
        }
    }

    private val prefetched = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    /** Videos that failed to play; their streams are re-resolved on the next try. */
    private val failedVideos = HashSet<String>()

    /** Swaps the current item between its audio track and its music video. Returns an error message, if any. */
    suspend fun setVideoMode(enabled: Boolean): String? {
        val idx = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return null
        val song = item.toSong() ?: return null
        if (enabled == (item.videoModeId != null)) return null
        if (song.isLive) {
            // The picture is already in the stream: same item, video track on (or off), no seeking about.
            swapInPlace(idx, song.toMediaItem(if (enabled) song.id else null))
            applyLiveVideo()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            videoWatchdog?.cancel()
            if (enabled) watchVideo(song.id)
            return null
        }
        val vid = if (enabled) {
            val found = videoIdFor(song)
            found.id ?: return if (found.lookupFailed) "Couldn't reach YouTube for the music video. Tap to try again." else "No music video for this song"
        } else null
        if (player.currentMediaItemIndex != idx) return null
        if (vid != null && failedVideos.remove(vid)) c.streams.invalidate(vid)
        // Videos often have an intro, so the same moment falls at a different time: carry the
        // listener to the matching spot. Uses the measured offset if it's ready (it's prefetched);
        // otherwise switch now and correct the position as soon as it's known.
        val known = when {
            vid != null -> c.videoSync.cached(song.id, vid)
            else -> item.videoModeId?.let { c.videoSync.cached(song.id, it) }?.let { -it }
        }
        val now = player.currentPosition
        val pos = (now + (known ?: 0L)).coerceAtLeast(0)
        val playWhenReady = player.playWhenReady
        swapInPlace(idx, song.toMediaItem(vid))
        player.seekTo(idx, pos)
        player.playWhenReady = playWhenReady
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        videoWatchdog?.cancel()
        if (vid != null) {
            if (known == null && vid != song.id && now > 1_500) scope.launch {
                val lag = c.videoSync.lagMs(song.id, vid) ?: return@launch
                val current = player.currentMediaItem
                // Only if the listener is still on this video and hasn't moved far since switching.
                if (kotlin.math.abs(lag) >= 1_000 && current?.mediaId == song.id && current.videoModeId == vid &&
                    kotlin.math.abs(player.currentPosition - pos) < 20_000
                ) player.seekTo(player.currentPosition + lag)
            }
            watchVideo(song.id)
        }
        return null
    }

    private var videoWatchdog: Job? = null

    /** No picture after a fair wait: fall back to the song (the button stays usable). */
    private fun watchVideo(songId: String) {
        videoWatchdog?.cancel()
        videoWatchdog = scope.launch {
            delay(20_000)
            val cur = player.currentMediaItem
            if (cur?.mediaId == songId && cur.videoModeId != null && player.videoSize.width <= 0) revertVideo(songId)
        }
    }

    /**
     * A live stream's HLS variants carry video and audio together, so in audio mode its video track is turned off
     * (nothing to decode, and the player can take a lighter variant); other items leave video on for music videos.
     */
    private fun applyLiveVideo() {
        val item = player.currentMediaItem
        val off = item != null && item.toSong()?.isLive == true && item.videoModeId == null
        val params = player.trackSelectionParameters
        if ((C.TRACK_TYPE_VIDEO in params.disabledTrackTypes) != off) {
            player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, off).build()
        }
    }

    /** Switches back to the audio version after a video failure; the video can be tried again. */
    private suspend fun revertVideo(songId: String) {
        videoWatchdog?.cancel()
        val idx = player.currentMediaItemIndex
        val current = player.currentMediaItem ?: return
        val song = current.toSong() ?: return
        if (song.isLive) {
            swapInPlace(idx, song.toMediaItem(null))
            applyLiveVideo()
            if (player.playbackState == Player.STATE_IDLE) { player.prepare(); player.play() }
            c.player.onVideoFailed(songId)
            c.player.reportError("The live video didn't load, so it's playing as audio. Tap the video button to try again.")
            return
        }
        current.videoModeId?.let { failedVideos += it; prefetched -= it }
        android.util.Log.w("PrismVideo", "video ${current.videoModeId} for $songId didn't load (${player.playerError?.errorCodeName ?: "no picture in time"}); back to the song")
        val lag = current.videoModeId?.takeIf { it != song.id }?.let { c.videoSync.cached(song.id, it) } ?: 0L
        val pos = (player.currentPosition - lag).coerceAtLeast(0)
        swapInPlace(idx, song.toMediaItem(null))
        player.seekTo(idx, pos)
        player.prepare()
        player.play()
        c.player.onVideoFailed(songId)
        c.player.reportError("The music video didn't load, so the song is playing. Tap the video button to try again.")
    }

    private fun flushPlayTime() {
        val now = SystemClock.elapsedRealtime()
        if (playStartedAt > 0) accumulatedMs += now - playStartedAt
        playStartedAt = if (::player.isInitialized && player.isPlaying) now else 0
        val song = trackedSong
        val source = trackedSource
        val excluded = trackedExcluded
        val ms = accumulatedMs
        accumulatedMs = 0
        // Replay, smart downloads and search taste are about music: episodes aren't counted.
        if (song != null && ms >= 10_000 && !song.isEpisode) {
            scope.launch(Dispatchers.IO) {
                c.db.plays().insert(PlayEventEntity(songId = song.id, timestamp = System.currentTimeMillis(), playedMs = ms, source = source, excluded = excluded))
            }
        }
    }

    /**
     * Sing on (or set to get songs ready while it's off): the playing song's vocals first, then the next song's, so
     * it's ready when it starts.
     */
    private fun singAhead() {
        if (!c.sing.onNow && !c.settings.current.singAlwaysReady) return
        val songs = listOfNotNull(player.currentMediaItem, *upcoming(1).toTypedArray()).filter { it.videoModeId == null }.mapNotNull { it.toSong() }
        c.sing.prepare(songs)
    }

    /** What a play of [item] is counted as (play_events.source): the kind of queue it's in. */
    private fun sourceOf(item: MediaItem): String = when {
        c.sing.onNow -> "sing"
        c.dj.onNow -> "dj"
        item.mediaId in c.queue.smartPicks.value -> "smart"
        else -> c.queue.source.value.kind.name.lowercase()
    }

    // ------------------------------------------------------------------ Smart Shuffle

    private var smartJob: kotlinx.coroutines.Job? = null
    /** Recommendations ready to slip in (from radios of the queue's own songs). */
    private val smartPool = ArrayDeque<Song>()
    private var smartPoolFor: QueueSource? = null

    /**
     * Smart Shuffle: a recommendation after every three or four of the queue's own songs, in the shuffled order (each
     * goes in at the queue index after one of the songs coming up, which [HistoryShuffleOrder] keeps beside it). Only
     * the next dozen songs are looked at, so it keeps step as the queue plays; Smart Shuffle off takes the ones not
     * played yet back out.
     */
    private fun smartAhead() {
        smartJob?.cancel()
        if (!c.queue.smartShuffle.value || !player.shuffleModeEnabled || !c.queue.smartAllowed()) return
        smartJob = scope.launch {
            val picks = c.queue.smartPicks.value
            val tl = player.currentTimeline
            var i = player.currentMediaItemIndex
            if (i == C.INDEX_UNSET) return@launch
            // Own songs since the last pick, counting the one playing.
            var run = if (player.getMediaItemAt(i).mediaId in picks) 0 else 1
            val anchors = ArrayList<Int>()
            var seen = 0
            while (seen < SMART_LOOK_AHEAD && anchors.size < 2) {
                val next = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
                if (next == C.INDEX_UNSET) break
                seen++
                if (player.getMediaItemAt(next).mediaId in picks) { run = 0; i = next; continue }
                run++
                // Three or four own songs between picks, alternately.
                val gap = 3 + (picks.size + anchors.size) % 2
                val after = tl.getNextWindowIndex(next, Player.REPEAT_MODE_OFF, true)
                if (run >= gap && (after == C.INDEX_UNSET || player.getMediaItemAt(after).mediaId !in picks)) { anchors += next; run = 0 }
                i = next
            }
            if (anchors.isEmpty()) return@launch
            val src = c.queue.source.value
            if (smartPoolFor !== src) { smartPool.clear(); smartPoolFor = src }
            if (smartPool.size < anchors.size) fillSmartPool()
            // Highest index first, so the other anchors' indices stay put.
            for (a in anchors.sortedDescending()) {
                if (!c.queue.smartShuffle.value || c.queue.source.value !== src) return@launch
                val song = smartPool.removeFirstOrNull() ?: break
                val at = a + 1
                if (c.queue.autoplayStart.value >= at) c.queue.autoplayStart.value += 1
                c.queue.smartPicks.value = c.queue.smartPicks.value + song.id
                player.addMediaItem(at, song.toMediaItem())
            }
        }
    }

    /** More recommendations: radios from a few of the queue's own songs, less what's queued, disliked or excluded. */
    private suspend fun fillSmartPool() {
        val inQueue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
        val own = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
            .filter { it.mediaId !in c.queue.smartPicks.value }.mapNotNull { it.toSong() }.filter { !it.isLocal && !it.isEpisode && !it.isLive }
        if (own.isEmpty()) return
        val recent = c.library.recent.value.take(50).map { it.id }.toSet()
        for (seed in own.shuffled().take(2)) {
            val r = runCatching { withContext(Dispatchers.IO) { c.radio(seed.id, "RDAMVM${seed.id}") } }.getOrNull() ?: continue
            r.songs.filter { s ->
                s.id !in inQueue && s.id !in recent && !s.isVideo && !s.isLive && !c.library.isDisliked(s.id) && !c.exclusions.blocks(s) &&
                    smartPool.none { it.id == s.id }
            }.shuffled().take(6).forEach { smartPool += it }
        }
    }

    /** Smart Shuffle off: its picks still to come leave the queue (the one playing stays). */
    private fun dropSmartPicks() {
        smartJob?.cancel()
        val picks = c.queue.smartPicks.value
        if (picks.isEmpty()) return
        val cur = player.currentMediaItemIndex
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (i == cur || player.getMediaItemAt(i).mediaId !in picks) continue
            player.removeMediaItem(i)
            if (c.queue.autoplayStart.value > i) c.queue.autoplayStart.value -= 1
        }
        c.queue.smartPicks.value = player.currentMediaItem?.mediaId?.takeIf { it in picks }?.let { setOf(it) } ?: emptySet()
    }

    // ------------------------------------------------------------------ DJ mode

    private var djJob: kotlinx.coroutines.Job? = null
    private var djSoon: kotlinx.coroutines.Job? = null
    /** DJ mode is moving songs itself (the timeline changes that causes aren't new songs). */
    private var djMoving = false

    /**
     * DJ mode: the songs ahead get a quick scan each (key, tempo, loudness from three stretches of the song,
     * [MixTempo.quickScan]), a few at once, the next ones first, and are put in DJ order ([resequence]) as the scans come
     * in. A song not on the phone is fetched (only the stretches) on Wi-Fi, or on mobile data when "Measure songs on
     * mobile data" is on; the queue says when songs are waiting for Wi-Fi, and offers mobile data.
     */
    private fun djAhead() {
        djJob?.cancel()
        if (!c.dj.onNow) return
        if (player.shuffleModeEnabled) player.shuffleModeEnabled = false
        djJob = scope.launch {
            val s = c.settings.current
            val network = c.dj.dataAllowed || s.mixTempoOnCellular ||
                getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == false
            resequence()
            val cur = player.currentMediaItemIndex
            // The songs after the playing one, in queue order (the next ones matter first); the playing one last.
            val ahead = (cur + 1 until minOf(player.mediaItemCount, cur + 2 + DJ_WINDOW))
                .map { player.getMediaItemAt(it) }.filter { djTrack(it) != null }.map { it.mediaId }
            val ids = (ahead + listOfNotNull(player.currentMediaItem?.takeIf { djTrack(it) != null }?.mediaId)).distinct()
            var looked = 0; var keyed = 0; var needNetwork = 0
            fun publish() = c.dj.setProgress(DjProgress(looked, ids.size, keyed, needNetwork))
            for (id in ids) if (c.mixTempo.keyed(id)) { looked++; keyed++ }
            publish()
            val started = SystemClock.elapsedRealtime()
            kotlinx.coroutines.coroutineScope {
                for (id in ids.filter { !c.mixTempo.keyed(it) }) launch {
                    val r = c.mixTempo.quickScan(id, network)
                    looked++
                    when (r) {
                        MixTempo.QuickResult.KEYED -> keyed++
                        MixTempo.QuickResult.NEEDS_NETWORK -> needNetwork++
                        MixTempo.QuickResult.FAILED -> {}
                    }
                    publish()
                    c.dj.bump()
                    if (r == MixTempo.QuickResult.KEYED) resequenceSoon()
                }
            }
            android.util.Log.i("PrismDj", "looked at ${ids.size} songs in ${SystemClock.elapsedRealtime() - started} ms: $keyed keyed, $needNetwork waiting for Wi-Fi")
        }
    }

    /** A queue item as DJ mode sees it (null for what it leaves where it is: live streams, podcasts, videos). */
    private fun djTrack(item: MediaItem, id: String = item.mediaId): DjTrack? {
        val song = item.toSong() ?: return null
        if (song.isLive || song.isEpisode || item.videoModeId != null) return null
        return DjTrack(id, c.mixTempo.key(song.id), c.mixTempo.djBpm(song.id), c.mixTempo.djLoudness(song.id), song.artists.firstOrNull()?.name)
    }

    /**
     * Puts the songs ahead in DJ order: the playing song and the next stay (the next is already being got ready, and
     * may be blending in), as do songs the listener queued themselves; up to [DJ_WINDOW] after those are ordered to
     * follow on from the next song. Songs not scanned yet keep their places after the scanned ones, and join the
     * order as their scans land. Only moves songs (nothing reloads).
     */
    private fun resequence() {
        if (!c.dj.onNow || player.shuffleModeEnabled) return
        val cur = player.currentMediaItemIndex
        if (cur == C.INDEX_UNSET || cur + 1 >= player.mediaItemCount) return
        val queued = c.queue.userQueued.value
        var base = cur + 2
        while (base < player.mediaItemCount && player.getMediaItemAt(base).mediaId in queued) base++
        // Within the queue's own songs, or within autoplay's, so neither list in the queue gets the other's songs.
        val autoplay = c.queue.autoplayStart.value.takeIf { it > 0 } ?: Int.MAX_VALUE
        val limit = if (base < autoplay) minOf(autoplay, player.mediaItemCount) else player.mediaItemCount
        val end = minOf(limit, base + DJ_WINDOW)
        if (end - base < 2) return
        val anchor = djTrack(player.getMediaItemAt(cur + 1)) ?: return
        val items = (base until end).map { player.getMediaItemAt(it) }
        val tracks = items.mapIndexed { i, item -> if (c.mixTempo.keyed(item.mediaId)) djTrack(item, id = i.toString()) else null }
        val known = tracks.filterNotNull()
        if (known.size < 2) return
        val set = DjSequencer.order(anchor, known, c.settings.current.djFlow)
        android.util.Log.d("PrismDj", "set after ${anchor.id}: " + (listOf(anchor) + set).joinToString(" → ") { t ->
            (t.key?.takeIf { it.sure }?.camelot ?: "?") + "/" + (t.bpm?.toInt() ?: "?")
        } + " (+${items.size - known.size} not scanned)")
        val ordered = set.map { it.id.toInt() }
        val desired = ordered + items.indices.filter { tracks[it] == null }
        if (desired == items.indices.toList()) return
        // One change for the whole new order (Reddit/owner 1.7.2: the queue lagged with DJ mode on): it used to be one
        // move per song, each a separate queue change sent on to the player screen, the session and the car.
        djMoving = true
        try {
            player.replaceMediaItems(base, end, desired.map { items[it] })
        } finally {
            djMoving = false
        }
    }

    private var resequenceJob: kotlinx.coroutines.Job? = null

    /** [resequence] once things settle: scans landing one after another reorder the queue once, not each time. */
    private fun resequenceSoon() {
        if (resequenceJob?.isActive == true) return
        resequenceJob = scope.launch { delay(RESEQUENCE_GAP_MS); resequence() }
    }

    /**
     * Buttons beside play/pause/skip, in the listener's order (Settings → Player → Buttons, 1.7.4; like, shuffle, repeat,
     * lyrics, start radio and "not for me" by default). The phone's media controls show the first two; the watch keeps
     * previous/next beside play (see the session extras); Android Auto shows them all.
     */
    private fun updateButtons() {
        if (!::session.isInitialized) return
        val id = player.currentMediaItem?.mediaId
        val liked = id != null && id in c.library.likedIds.value
        fun button(icon: Int, name: String, command: SessionCommand) =
            CommandButton.Builder(icon).setDisplayName(name).setSessionCommand(command).setSlots(CommandButton.SLOT_OVERFLOW).build()
        val custom = c.settings.current.sessionButtons.map { b ->
            when (b) {
                SessionButton.LIKE -> button(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED, if (liked) "Remove from liked" else "Like", likeCommand)
                SessionButton.SHUFFLE -> button(if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF, if (player.shuffleModeEnabled) "Shuffle off" else "Shuffle", shuffleCommand)
                SessionButton.REPEAT -> when (player.repeatMode) {
                    Player.REPEAT_MODE_ALL -> button(CommandButton.ICON_REPEAT_ALL, "Repeat one", repeatCommand)
                    Player.REPEAT_MODE_ONE -> button(CommandButton.ICON_REPEAT_ONE, "Repeat off", repeatCommand)
                    else -> button(CommandButton.ICON_REPEAT_OFF, "Repeat", repeatCommand)
                }
                SessionButton.CAR_LYRICS -> if (c.settings.current.carLyrics) button(CommandButton.ICON_SUBTITLES, "Hide lyrics", lyricsCommand)
                    else button(CommandButton.ICON_SUBTITLES_OFF, "Show lyrics", lyricsCommand)
                SessionButton.RADIO -> button(CommandButton.ICON_RADIO, "Start radio", radioCommand)
                SessionButton.DISLIKE -> button(CommandButton.ICON_THUMB_DOWN_UNFILLED, "Not for me", dislikeCommand)
                SessionButton.BACK_10 -> button(CommandButton.ICON_SKIP_BACK_10, "Back 10 seconds", back10Command)
                SessionButton.AHEAD_30 -> button(CommandButton.ICON_SKIP_FORWARD_30, "Ahead 30 seconds", ahead30Command)
            }
        }
        // Previous/next are named for their slots outright: with only custom buttons, a controller that lays out by
        // slots (the Pixel Watch's media controls) filled the back/forward places with like and shuffle.
        val buttons = listOf(
            CommandButton.Builder(CommandButton.ICON_PREVIOUS).setDisplayName("Previous")
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS).setSlots(CommandButton.SLOT_BACK).build(),
            CommandButton.Builder(CommandButton.ICON_NEXT).setDisplayName("Next")
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT).setSlots(CommandButton.SLOT_FORWARD).build(),
        ) + custom
        session.setMediaButtonPreferences(buttons)
        pushWidgets()
    }

    /** Home screen widgets follow what's playing (nothing happens when none is placed). */
    /** A progress-only widget refresh was skipped with the screen off (see the service's tick). */
    private var widgetsBehind = false

    private fun screenOn(): Boolean = (getSystemService(POWER_SERVICE) as? android.os.PowerManager)?.isInteractive != false

    /**
     * The widgets (and Discord) brought up to date. [progressOnly]: only the playhead has moved since the last time,
     * which waits while the screen is off (each refresh sends the launcher the cover again).
     */
    private fun pushWidgets(progressOnly: Boolean = false) {
        if (!::player.isInitialized) return
        val song = player.currentMediaItem?.toSong()
        if (progressOnly && !screenOn()) widgetsBehind = true
        else {
            widgetsBehind = false
            com.prism.music.widget.Widgets.update(
                this, song, player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE,
                song != null && song.id in c.library.likedIds.value,
                player.currentPosition, player.duration.takeIf { it > 0 },
            )
        }
        // Discord shows what's playing too, when that's on (it only sends what changed).
        c.discord.show(song, player.isPlaying, player.currentPosition, player.duration.takeIf { it > 0 } ?: 0)
    }

    /** A fresh shuffle order each time shuffle goes on, current song first. */
    private fun reshuffle() {
        val n = player.mediaItemCount
        if (n < 2) return
        val cur = player.currentMediaItemIndex.coerceIn(0, n - 1)
        player.setShuffleOrder(HistoryShuffleOrder.shuffled(n, cur) { player.currentMediaItemIndex })
    }

    /** Swaps everything after the current song for a radio station seeded by it. */
    private fun startRadio() {
        val song = player.currentMediaItem?.toSong() ?: return
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { c.radio(song.id, "RDAMVM${song.id}") } }.getOrNull() ?: return@launch
            if (player.currentMediaItem?.mediaId != song.id) return@launch
            val add = r.songs.filter { it.id != song.id && !c.library.isDisliked(it.id) && !c.exclusions.blocks(it) }
            if (add.isEmpty()) return@launch
            val idx = player.currentMediaItemIndex
            if (idx + 1 < player.mediaItemCount) player.removeMediaItems(idx + 1, player.mediaItemCount)
            if (idx > 0) player.removeMediaItems(0, idx)
            player.shuffleModeEnabled = false
            player.addMediaItems(add.map { it.toMediaItem() })
            c.queue.source.value = QueueSource(QueueKind.RADIO, "${song.title} radio", seedId = song.id)
            c.queue.autoplayStart.value = -1
            c.queue.autoplayContinuation = r.continuation
        }
    }

    private fun isCar(session: MediaSession, controller: MediaSession.ControllerInfo) =
        controller.packageName == "com.google.android.projection.gearhead" ||
            runCatching { session.isAutoCompanionController(controller) || session.isAutomotiveController(controller) }.getOrDefault(false)

    fun applyStreamStats(id: String) {
        val (source, s) = c.streamDetails[id] ?: return
        if (player.currentMediaItem?.mediaId != id && player.currentMediaItem?.videoModeId != id) return
        // Downloaded and cached songs have no stream info this session: work the bitrate out
        // from the saved file's size over the song's length.
        val savedBytes = if (s == null) savedLength(id) else 0L
        val durationMs = player.duration.takeIf { it > 0 && it != C.TIME_UNSET }
            ?: player.currentMediaItem?.toSong()?.durationSec?.takeIf { it > 0 }?.times(1000L) ?: 0L
        val savedKbps = if (savedBytes > 0 && durationMs > 0) (savedBytes * 8 / durationMs).toInt() else 0
        c.nerdStats.value = c.nerdStats.value.copy(
            source = source,
            itag = s?.itag ?: 0,
            container = s?.mimeType ?: c.nerdStats.value.container,
            codec = s?.codec ?: c.nerdStats.value.codec,
            streamBitrateKbps = s?.bitrate ?: savedKbps.takeIf { it > 0 } ?: c.nerdStats.value.streamBitrateKbps,
            contentLength = s?.contentLength ?: savedBytes,
            client = s?.client ?: "",
        )
    }

    /** The lossless add-on: a matching FLAC / WAV / AIFF on the phone, when lossless mode and the add-on are on. */
    private fun losslessFileFor(item: MediaItem): android.net.Uri? {
        // Local music always plays from its file.
        if (item.mediaId.startsWith(Song.LOCAL_PREFIX)) {
            val uri = c.localMusic.uriFor(item.mediaId) ?: return null
            val format = c.localMusic.track(item.mediaId)?.mime?.let(::fileFormatName)
            c.streamDetails[item.mediaId] = "On this phone" + (format?.let { " · $it" } ?: "") to null
            scope.launch { applyStreamStats(item.mediaId) }
            return uri
        }
        val s = c.settings.current
        if (!s.lossless || !s.losslessLocal) return null
        val song = item.toSong() ?: return null
        val track = c.localLossless.match(song) ?: return null
        c.streamDetails[song.id] = "Lossless · ${track.format} on this phone" to null
        scope.launch { applyStreamStats(song.id) }
        return track.uri
    }

    private fun savedLength(id: String): Long = c.localLossless.matched[id]?.size ?: runCatching {
        listOf(c.downloadCache, c.playerCache).firstNotNullOfOrNull { cache ->
            androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(id)).takeIf { it > 0 }
        } ?: 0L
    }.getOrDefault(0L)

    // ------------------------------------------------------------ Queue persistence

    private val queueFile get() = File(filesDir, "queue.json")

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch { delay(2000); saveQueue() }
    }

    private fun saveQueue() {
        if (!::player.isInitialized) return
        val songs = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).toSong() }
        // The play order while shuffled, walked from its first song (only when every item made it into the list).
        val order = if (player.shuffleModeEnabled && songs.size == player.mediaItemCount) player.shuffleOrder.let { so ->
            generateSequence(so.firstIndex.takeIf { it != androidx.media3.common.C.INDEX_UNSET }) { i -> so.getNextIndex(i).takeIf { it != androidx.media3.common.C.INDEX_UNSET } }
                .take(songs.size).toList()
        } else emptyList()
        val q = SavedQueue(
            songs, player.currentMediaItemIndex, player.currentPosition, c.queue.source.value.title,
            shuffle = player.shuffleModeEnabled, shuffleOrder = order, repeat = player.repeatMode,
        )
        val text = InnerTube.json.encodeToString(SavedQueue.serializer(), q)
        scope.launch(Dispatchers.IO) { runCatching { queueFile.writeText(text) } }
    }

    private fun restoreQueue() {
        val q = runCatching { InnerTube.json.decodeFromString(SavedQueue.serializer(), queueFile.readText()) }.getOrNull() ?: return
        if (q.songs.isEmpty()) return
        c.queue.source.value = QueueSource(QueueKind.OTHER, q.title)
        player.setMediaItems(q.songs.map { it.toMediaItem() }, q.index.coerceIn(0, q.songs.lastIndex), q.positionMs)
        // Shuffle and repeat as they were, in the same order.
        if (q.shuffle) {
            if (q.shuffleOrder.size == q.songs.size && q.shuffleOrder.sorted() == q.songs.indices.toList()) {
                player.setShuffleOrder(HistoryShuffleOrder(q.shuffleOrder.toIntArray()) { player.currentMediaItemIndex })
            } else reshuffle()
            player.shuffleModeEnabled = true
        }
        if (q.repeat != Player.REPEAT_MODE_OFF) player.repeatMode = q.repeat
        // The mini player shows this song: get it ready, so Play starts it straight away.
        player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }?.let { id -> scope.launch(Dispatchers.IO) { c.warmUp(id) } }
        // And the one after it (otherwise only a song coming up warms its successor).
        prefetchNext()
    }

    // ------------------------------------------------------------ Session

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (isCar(session, controller)) {
                carControllers += controller
                carLyrics.carConnected = true
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().apply { customCommands.forEach { add(it) } }.build()
                )
                .build()
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (carControllers.remove(controller)) carLyrics.carConnected = carControllers.isNotEmpty()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                likeCommand.customAction -> player.currentMediaItem?.toSong()?.let { c.library.toggleLike(it) }
                shuffleCommand.customAction -> {
                    if (!player.shuffleModeEnabled) reshuffle()
                    player.shuffleModeEnabled = !player.shuffleModeEnabled
                }
                repeatCommand.customAction -> player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                lyricsCommand.customAction -> c.settings.setCarLyrics(!c.settings.current.carLyrics)
                radioCommand.customAction -> startRadio()
                dislikeCommand.customAction -> player.currentMediaItem?.toSong()?.let { song ->
                    c.library.setDisliked(song, true)
                    if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                }
                back10Command.customAction -> player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                ahead30Command.customAction -> {
                    val end = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                    player.seekTo((player.currentPosition + 30_000).coerceAtMost((end - 1_000).coerceAtLeast(0)))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // ---- Browsing (Android Auto, Assistant, other media browsers)

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            // Android Auto / the system ask for a "recent" root to offer a resume card. The root's hints say how the car shows
            // folders (cover tiles with the grid on) unless a folder says otherwise.
            Futures.immediateFuture(LibraryResult.ofItem(
                if (params?.isRecent == true) tree.recentRoot() else tree.root(),
                if (params?.isRecent == true) params else tree.rootExtras()?.let { hints ->
                    LibraryParams.Builder().setExtras(android.os.Bundle(params?.extras ?: android.os.Bundle.EMPTY).apply { putAll(hints) })
                        .setOffline(params?.isOffline == true).setSuggested(params?.isSuggested == true).build()
                } ?: params,
            ))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = tree.children(parentId)
            val from = (page * pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(all.subList(from, (from + pageSize).coerceAtMost(all.size)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val count = tree.search(query)
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = tree.searchResults()
            val from = (page * pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(all.subList(from, (from + pageSize).coerceAtMost(all.size)), params)
        }

        // ---- Playing what a controller picked

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future {
            mediaItems.flatMap { item ->
                if (item.mediaId.startsWith(MediaTree.SHUFFLE)) tree.shuffleSongs(item.mediaId)?.second.orEmpty().shuffled().map { it.toMediaItem() }
                else if (item.mediaId.startsWith(MediaTree.DJ)) tree.shuffleSongs(item.mediaId)?.second.orEmpty().map { it.toMediaItem() }
                else listOfNotNull((item.toSong() ?: tree.song(item.mediaId, item))?.toMediaItem())
            }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val single = mediaItems.singleOrNull()
            // "Hey Google, play … on Prism": a search query instead of an id.
            val query = single?.requestMetadata?.searchQuery
            if (single != null && single.mediaId.isBlank() && !query.isNullOrBlank()) {
                val top = tree.searchTop(query)
                if (top != null) {
                    c.queue.source.value = QueueSource(QueueKind.SINGLE, "Autoplay", seedId = top.id)
                    c.queue.autoplayStart.value = -1
                    c.queue.autoplayContinuation = null
                    return@future MediaSession.MediaItemsWithStartPosition(listOf(top.toMediaItem()), 0, C.TIME_UNSET)
                }
            }
            // "Hey Google, play music on Prism": no query at all, so carry on with the last queue.
            if (single != null && single.mediaId.isBlank() && query.isNullOrBlank() && single.toSong() == null) {
                return@future onPlaybackResumption(mediaSession, controller, true).await()
            }
            // "Shuffle play" at the top of a list.
            if (single != null && single.mediaId.startsWith(MediaTree.SHUFFLE)) {
                tree.shuffleSongs(single.mediaId)?.takeIf { it.second.isNotEmpty() }?.let { (title, list) ->
                    c.queue.source.value = QueueSource(QueueKind.PLAYLIST, title)
                    c.queue.autoplayStart.value = -1
                    player.shuffleModeEnabled = true
                    // Shuffled here too, so each "Shuffle play" is a new order rather than the player's last one.
                    return@future MediaSession.MediaItemsWithStartPosition(list.shuffled().map { it.toMediaItem() }, 0, C.TIME_UNSET)
                }
            }
            // "Play as a DJ set" at the top of a list: from the first song, then in the order that blends best (DJ mode,
            // switched on once the songs are in, as the phone's "Play as a DJ set" does).
            if (single != null && single.mediaId.startsWith(MediaTree.DJ)) {
                tree.shuffleSongs(single.mediaId)?.takeIf { it.second.size > 1 }?.let { (title, list) ->
                    c.queue.source.value = QueueSource(QueueKind.PLAYLIST, title)
                    c.queue.autoplayStart.value = -1
                    c.dj.setOn(false)
                    player.shuffleModeEnabled = false
                    val first = list.first().id
                    scope.launch {
                        kotlinx.coroutines.withTimeoutOrNull(5_000) {
                            while (player.currentMediaItem?.mediaId != first) kotlinx.coroutines.delay(100)
                        } ?: return@launch
                        c.dj.setOn(true)
                    }
                    return@future MediaSession.MediaItemsWithStartPosition(list.map { it.toMediaItem() }, 0, C.TIME_UNSET)
                }
            }
            // One song tapped in a list: play the list from that song.
            if (single != null && single.toSong() == null) {
                tree.folderOf(single.mediaId)?.let { (title, list) ->
                    val index = list.indexOfFirst { it.id == single.mediaId }.coerceAtLeast(0)
                    c.queue.source.value = QueueSource(QueueKind.PLAYLIST, title)
                    c.queue.autoplayStart.value = -1
                    return@future MediaSession.MediaItemsWithStartPosition(list.map { it.toMediaItem() }, index, startPositionMs)
                }
                tree.song(single.mediaId, single)?.let { song ->
                    c.queue.source.value = QueueSource(QueueKind.SINGLE, "Autoplay", seedId = song.id)
                    c.queue.autoplayStart.value = -1
                    c.queue.autoplayContinuation = null
                    return@future MediaSession.MediaItemsWithStartPosition(listOf(song.toMediaItem()), 0, startPositionMs)
                }
            }
            val resolved = mediaItems.mapNotNull { item -> (item.toSong() ?: tree.song(item.mediaId, item))?.toMediaItem() }
            MediaSession.MediaItemsWithStartPosition(resolved, startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)), startPositionMs)
        }

        /** Lets the system media controls (and the car) resume the last queue after Prism was closed. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val q = withContext(Dispatchers.IO) {
                runCatching { InnerTube.json.decodeFromString(SavedQueue.serializer(), queueFile.readText()) }.getOrNull()
            }
            if (q == null || q.songs.isEmpty()) {
                MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
            } else {
                c.queue.source.value = QueueSource(QueueKind.OTHER, q.title)
                MediaSession.MediaItemsWithStartPosition(q.songs.map { it.toMediaItem() }, q.index.coerceIn(0, q.songs.lastIndex), q.positionMs)
            }
        }
    }
    // ------------------------------------------------------------ Stats for nerds

    private inner class StatsListener : AnalyticsListener {
        private fun update(block: NerdStats.() -> NerdStats) {
            c.nerdStats.value = c.nerdStats.value.block()
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) = update {
            copy(
                codec = format.codecs ?: format.sampleMimeType ?: codec,
                sampleRate = format.sampleRate.takeIf { it > 0 } ?: sampleRate,
                bitDepth = when (format.pcmEncoding) {
                    C.ENCODING_PCM_8BIT -> 8
                    C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
                    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
                    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
                    else -> 0
                },
                channels = format.channelCount.takeIf { it > 0 } ?: channels,
                container = format.containerMimeType ?: container,
                streamBitrateKbps = streamBitrateKbps.takeIf { it > 0 }
                    ?: (format.averageBitrate.takeIf { it > 0 } ?: format.bitrate.takeIf { it > 0 })?.let { it / 1000 } ?: 0,
            )
        }

        // The length isn't always known when a downloaded song starts; fill its bitrate in once it is.
        override fun onTimelineChanged(eventTime: AnalyticsListener.EventTime, reason: Int) {
            val id = player.currentMediaItem?.mediaId ?: return
            if (c.nerdStats.value.streamBitrateKbps <= 0) applyStreamStats(id)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) = update { copy(decoder = decoderName) }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) =
            update {
                copy(
                    outputEncoding = AudioEffects.encodingName(audioTrackConfig.encoding),
                    outputSampleRate = audioTrackConfig.sampleRate,
                    offload = audioTrackConfig.offload,
                    spatializer = c.settings.current.let { s ->
                        if (s.spatial) "Prism Spatial · ${(s.spatialAmount * 100).roundToInt()}%" else "Off"
                    },
                )
            }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) = update { copy(bandwidthKbps = bitrateEstimate / 1000) }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) = update {
            copy(
                videoResolution = "${format.width}×${format.height}" + if (format.frameRate > 0) " @ ${format.frameRate.toInt()}fps" else "",
                videoCodec = format.codecs ?: format.sampleMimeType ?: "",
            )
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) =
            update { copy(droppedFrames = this.droppedFrames + droppedFrames) }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ): Unit {
            // Background scans back off while the sound is running dry (MixTempo.ease).
            MixTempo.lastUnderrunAt = SystemClock.elapsedRealtime()
            update { copy(underruns = underruns + 1) }
        }
    }

    // ------------------------------------------------------------ Playback diagnostics

    /** What the audio does, for [AudioDiagnostics] (only written while it's on). */
    private inner class DiagnosticsListener : AnalyticsListener {
        private fun at(t: AnalyticsListener.EventTime) = "at ${formatMs(t.currentPlaybackPositionMs)}"
        private fun formatMs(ms: Long) = if (ms < 0) "?" else "%d:%02d.%d".format(ms / 60_000, ms / 1000 % 60, ms / 100 % 10)
        private fun track(cfg: AudioSink.AudioTrackConfig) =
            "${AudioEffects.encodingName(cfg.encoding)} ${cfg.sampleRate} Hz, channel mask ${cfg.channelConfig}, buffer ${cfg.bufferSize} bytes" +
                (if (cfg.offload) ", offload" else "") + (if (cfg.tunneling) ", tunneling" else "")

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) =
            AudioDiagnostics.log("track", "made: ${track(audioTrackConfig)} ${at(eventTime)}; outputs ${AudioDiagnostics.devices(this@PlaybackService)}")

        override fun onAudioTrackReleased(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) =
            AudioDiagnostics.log("track", "released: ${track(audioTrackConfig)} ${at(eventTime)}")

        override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) =
            AudioDiagnostics.log("underrun", "the output ran dry ${at(eventTime)}: buffer ${bufferSizeMs} ms, last fed ${elapsedSinceLastFeedMs} ms before")

        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) =
            AudioDiagnostics.log("sink-error", "${audioSinkError.javaClass.simpleName}: ${audioSinkError.message} ${at(eventTime)}")

        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) =
            AudioDiagnostics.log("codec-error", "${audioCodecError.javaClass.simpleName}: ${audioCodecError.message} ${at(eventTime)}")

        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) =
            AudioDiagnostics.log("playing", "audio started coming out ${at(eventTime)}")

        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) =
            AudioDiagnostics.log("format", "${format.codecs ?: format.sampleMimeType} ${format.sampleRate} Hz ${format.channelCount} ch, pcm ${format.pcmEncoding}")

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) =
            AudioDiagnostics.log("decoder", "$decoderName (${initializationDurationMs} ms to start)")

        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) = AudioDiagnostics.log(
            "state",
            when (state) { Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready"; Player.STATE_ENDED -> "ended"; else -> "idle" } +
                " ${at(eventTime)}, ${(player.bufferedPosition - player.currentPosition).coerceAtLeast(0) / 1000} s loaded ahead",
        )

        override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) =
            AudioDiagnostics.log("state", (if (isPlaying) "playing" else "not playing (${player.playbackSuppressionReason.let { if (it == 0) "paused or waiting" else "suppressed: $it" }})") + " ${at(eventTime)}")

        override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int) =
            AudioDiagnostics.log(
                "song",
                if (mediaItem == null) "none (the queue is empty), reason $reason"
                else "${mediaItem.mediaMetadata.title} – ${mediaItem.mediaMetadata.artist} (${mediaItem.mediaId}), reason $reason",
            )

        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) =
            AudioDiagnostics.log("error", "${error.errorCodeName}: ${error.message} ${at(eventTime)}")
    }
}

/** Normalization's level: songs are brought to this loudness (integrated LUFS, as Spotify and YouTube use). */
/** How YouTube says a song can't be played where the listener is (#93). */
private val UNAVAILABLE = listOf("not available", "unavailable", "isn't available", "in your country")

/**
 * Whether [error] (or what caused it) is YouTube saying the song isn't available, in the listener's country mostly
 * (#93). Not age-restricted or Premium-only songs: those need an account, not another recording.
 */
internal fun unavailableHere(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { e ->
        if (e is org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException ||
            e is org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException) return@any false
        e is org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException ||
            e.message?.let { m -> UNAVAILABLE.any { m.contains(it, ignoreCase = true) } } == true
    }

private const val TARGET_LUFS = -14f

/** A song's volume (normalization) can still be set this far into it; after that it stays put for the song. */
private const val SETTLE_MS = 2_000L
/** A song already playing only has its volume changed for a better loudness figure this much apart (dB). */
private const val MIN_MIDSONG_CHANGE_DB = 1.5f
/** Queued songs are got ready once the playing one has played this long (not as it starts: that slowed its loading). */
private const val UNDER_WAY_MS = 5_000L
/** How many songs ahead DJ mode scans and orders (a quick scan each: three stretches of the song, a few at once). */
private const val DJ_WINDOW = 40
/** How long DJ mode waits for more scans before putting the queue in order again. */
private const val RESEQUENCE_GAP_MS = 1_500L
/** How many songs ahead (in play order) Smart Shuffle places its picks among. */
private const val SMART_LOOK_AHEAD = 12
/** How many queued songs ahead are got ready (their loudness and edges). */
private const val QUEUE_AHEAD = 3
/** How far ahead the player loads: a whole song (and the start of the next, near its end). */
private const val WHOLE_SONG_MS = 300_000
