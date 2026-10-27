package com.prism.music.playback;

import android.media.AudioPlaybackCaptureConfiguration;
import android.media.projection.MediaProjection;
import android.os.Process;
import androidx.annotation.RequiresApi;

/**
 * Builds our own-playback capture config. Java on purpose: Kotlin's stub types
 * the Builder's projection as non-null, but null is legal at runtime here —
 * matching by UID needs no MediaProjection (the Java samples rely on that).
 */
@RequiresApi(29)
public final class PlaybackCapture {
    private PlaybackCapture() {}

    public static AudioPlaybackCaptureConfiguration ownUid() {
        MediaProjection projection = null;
        return new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUid(Process.myUid())
                .build();
    }
}
