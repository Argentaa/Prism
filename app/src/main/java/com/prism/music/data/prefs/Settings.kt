package com.prism.music.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("prism_settings")

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }
enum class ColorSource(val label: String) { ARTWORK("Now playing"), DYNAMIC("Wallpaper"), CUSTOM("Custom") }
enum class GlassKind(val label: String) { REGULAR("Regular"), CLEAR("Clear") }
/** Appearance → Color → Spectrum: the split light through the app, or white light (neutral greys and ink, no accent). */
enum class LightStyle(val label: String) { SPECTRUM("Spectrum"), WHITE("White light") }
/** Appearance → Color → Background (1.7.2): how much color the page itself carries. */
enum class SurfaceTint(val label: String) {
    /** As Prism always did: a hint of the accent in Spectrum, neutral in White light. */
    AUTO("Automatic"), NEUTRAL("Neutral"), ACCENT("Accent"), CUSTOM("Your color")
}
/** Appearance → Color → Contrast (1.7.2): how firm secondary text, lines and the accent are. */
enum class ContrastLevel(val label: String, val minRatio: Float) { STANDARD("Standard", 4.5f), MEDIUM("Medium", 6f), HIGH("High", 7.5f) }
/** Which of the cover's colors "Now playing" takes. */
enum class ArtworkSwatch(val label: String) { VIBRANT("Vibrant"), MUTED("Muted"), DOMINANT("Main color") }
/** How the "Now playing" color moves between songs. */
enum class ArtworkChange(val label: String, val ms: Int) { INSTANT("At once", 0), FADE("Fade", 900), SLOW("Slow fade", 2400), ALBUM("New albums only", 900) }
enum class AudioQuality(val label: String) { HIGH("High"), NORMAL("Normal"), LOW("Low") }
/** How lyrics show on Android Auto's player. */
enum class CarLyricsStyle(val label: String, val detail: String) {
    KARAOKE("Karaoke", "The line on the cover art, lighting up word by word, and as the title (unless the title's kept)"),
    LINES("Line by line", "The current and next line on the cover art, and as the title (unless the title's kept)"),
    TEXT("Title only", "The current line in place of the song title; the cover stays as it is"),
}

/** Where lyrics come from; [detail] says what each is good for, in Settings → Lyrics → Sources (#51). */
enum class LyricsSource(val label: String, val detail: String) {
    APPLE("Better Lyrics", "Apple Music's lyrics · word by word"),
    LYRICSPLUS("LyricsPlus", "Apple Music, Musixmatch and Spotify's · word by word"),
    BINI("Bini", "Apple Music's lyrics · word by word"),
    QQ("QQ · Portato", "QQ Music · word by word, the most Chinese songs"),
    LRCLIB("LRCLIB", "Open lyrics database · line by line"),
    NETEASE("NetEase", "NetEase Cloud Music · word by word"),
    KUGOU("KuGou", "KuGou · word by word, many Asian songs"),
    UNISON("Unison", "Lyrics listeners submit · few songs so far"),
    YTMUSIC("Prism Music", "LyricFind · lines or plain"),
    GENIUS("Genius", "Plain text · also fills in starred-out words"),
}

/** Tiles in Home's greeting section. */
enum class HomeShortcut(val label: String) {
    LIKED("Liked songs"), DOWNLOADS("Downloads"), REPLAY("Replay"), LIBRARY("Library"),
    MOODS("Moods & genres"), SEARCH("Search"), EQUALIZER("Equalizer"), TOGETHER("Listen together"),
}

/** A playlist pinned to Home's greeting tiles. */
@Serializable
data class PinnedPlaylist(val id: String, val title: String, val thumbnail: String? = null)

/** Where Autoplay finds what to play next. */
enum class AutoplaySource(val label: String) { MIXED("Both"), YOURS("Your music"), YOUTUBE("Prism Music") }

enum class SectionStyle(val label: String) { CAROUSEL("Carousel"), GRID("Grid"), LIST("List"), HERO("Spotlight") }

// ---------------------------------------------------------------- Look & layout

/**
 * The app's typeface. [PRISM] is Outfit, bundled with Prism (res/font, a variable font: every weight from one file).
 * The device fonts are used where the phone has them, with a fallback otherwise.
 */
/**
 * The app's typeface. Every one but System and Your font ships with Prism (res/font, SIL Open Font License), so it
 * looks the same on every phone; [face] is the typeface's own name. SERIF and MONO keep their stored names.
 */
enum class FontChoice(val label: String, val face: String? = null) {
    PRISM("Prism", "Outfit"), CLEAN("Clean", "Manrope"), GEOMETRIC("Geometric", "Plus Jakarta Sans"), AIRY("Airy", "Urbanist"),
    ROUNDED("Rounded", "Nunito"), SERIF("Editorial", "Fraunces"), MONO("Mono", "Geist Mono"), SYSTEM("System"),
    /** A .ttf / .otf the listener imported (#12; `AppFonts.FontSlot`). */
    CUSTOM("Your font");

    companion object {
        /**
         * A stored font name as it reads now: 1.7 dropped Google Sans and Condensed (both needed fonts only some phones
         * have), and picks of either move to Clean.
         */
        fun upgrade(name: String): String = when (name) { "GOOGLE_SANS", "CONDENSED" -> CLEAN.name; else -> name }
    }
}

/** How round cards, tiles and artwork are. */
enum class Corners(val label: String, val scale: Float) { SHARP("Sharp", 0.2f), SUBTLE("Subtle", 0.6f), ROUND("Round", 1f), EXTRA("Extra", 1.5f) }

/** How much room rows and sections get. */
enum class UiDensity(val label: String, val scale: Float) { COMPACT("Compact", 0.7f), COMFORTABLE("Comfortable", 1f), ROOMY("Roomy", 1.3f) }

/** The big page titles (Library, Replay, Settings…). */
enum class TitleSize(val label: String) { LARGE("Large"), MEDIUM("Medium"), SMALL("Small") }

/** Width of album / playlist cards in carousels. */
enum class CardSize(val label: String, val widthDp: Int) { SMALL("Small", 124), MEDIUM("Medium", 152), LARGE("Large", 184) }

enum class NavBarStyle(val label: String) { FLOATING("Floating"), DOCKED("Docked"), MINIMAL("Minimal") }
enum class NavLabels(val label: String) { ALWAYS("Always"), SELECTED("Selected"), NEVER("Never"), WORDS("Words only") }

/** A tab's own name as kept: one line, no separators, short enough for the bar. */
fun cleanTabName(name: String) = name.replace(Regex("[|=\\n\\r]"), " ").trim().take(18)

/** "HOME=Start|LIBRARY=Mine": tabs given their own names (a shared Look's are cleaned the same way). */
fun parseTabNames(v: String): Map<NavTab, String> = v.split('|').mapNotNull { part ->
    val tab = NavTab.entries.firstOrNull { it.name == part.substringBefore('=') } ?: return@mapNotNull null
    cleanTabName(part.substringAfter('=', "")).takeIf { it.isNotEmpty() }?.let { tab to it }
}.toMap()
/** The tab bar's height (1.7.6): its floating, docked and minimal heights. */
enum class NavBarSize(val label: String, val floating: Int, val docked: Int, val minimal: Int) {
    COMPACT("Compact", 56, 54, 48), STANDARD("Standard", 64, 60, 54), TALL("Tall", 72, 68, 60),
}
/** What's behind the tabs (1.7.6): the glass as it is, lighter so the page shows through, or solid. */
enum class NavBarFill(val label: String) { GLASS("As the glass"), LIGHT("See-through"), SOLID("Solid") }

/** Tabs the bottom bar can hold. */
/** The sections along the top of Local files, in the order the listener picked (#29). */
enum class LocalSection(val label: String) {
    SONGS("Songs"), FAVOURITES("Favorites"), ARTISTS("Artists"), ALBUMS("Albums"),
    ADDED_SONGS("Recently added"), ADDED_ALBUMS("Added albums"), DOWNLOADS("Downloads"),
}

enum class NavTab(val label: String) {
    HOME("Home"), SEARCH("Search"), LIBRARY("Library"), REPLAY("Replay"), SETTINGS("Settings"), LOCAL("Local files"),
    // 1.7.6: any of these pages as a tab too.
    DOWNLOADS("Downloads"), LIKED("Liked songs"), PODCASTS("Podcasts");

    companion object {
        /** The most tabs the bar holds (labels still fit on a phone). */
        const val MAX = 6
    }
}

enum class MiniPlayerStyle(val label: String) { CARD("Card"), SLIM("Slim"), PILL("Pill") }
enum class MiniProgress(val label: String) { LINE("Line"), FILL("Fill"), NONE("None") }

/** What's behind the full player. */
enum class PlayerBackground(val label: String) {
    MESH("Color mesh"), BLUR("Blurred cover"),
    /** The background picked for Home in Appearance → Backgrounds, in its dark version. */
    APPEARANCE("Your background"),
    THEME("Theme"), BLACK("Black"),
}
enum class TransportStyle(val label: String) { GLYPHS("Classic"), BUTTON("Play button"), OUTLINE("Rings") }
/** How wide the player's cover is: [extra] is how far it reaches into each side margin. */
enum class CoverSize(val label: String, val extra: Float) { STANDARD("Standard", 0f), LARGE("Large", 18f), FULL("Full width", 30f) }
/** Immersive mode's cover: [zoom] 1 fills the screen (its sides cut off), 0 shows all of it across the width. */
/** How much of the queue is loaded ahead while a song plays: the next song's first seconds, or whole songs. */
enum class Preload(val label: String, val songs: Int) { START("Next song's start", 0), NEXT("Next song", 1), NEXT3("Next 3 songs", 3) }

/** Where immersive mode's lyrics go: a line at a time high, in the middle or low on the screen, or all of them over it. */
enum class ImmersiveLyrics(val label: String) { HIGH("High"), MIDDLE("Middle"), LOW("Low"), FULL("Full screen") }
enum class ImmersiveArt(val label: String, val zoom: Float) { FILL("Fill the screen", 1f), ZOOMED("Zoomed out", 0.4f), WHOLE("Whole cover", 0f) }
/** [WAVE] (1.7.4): the song's own loudness, drawn from its scan, as the bar (a plain line until the song's been scanned). */
enum class ScrubberStyle(val label: String) { HAIRLINE("Hairline"), BOLD("Bold"), THUMB("Thumb"), WAVE("Waveform") }

// ---------------------------------------------------------------------------- The player, your way (1.7.4)

/** How the player is laid out: presets that reuse the same pieces (cover, deck, lyrics), each still tweakable. */
enum class PlayerLayout(val label: String, val note: String) {
    STANDARD("Standard", "The cover, then everything under it"),
    COVER("Cover", "A big cover; tap it for the controls"),
    LYRICS("Lyrics", "Opens on the lyrics, a small cover beside the title"),
    MINIMAL("Minimal", "The title and play; gestures do the rest"),
    VINYL("Vinyl", "The cover as a record's label, turning as it plays"),
    CASSETTE("Cassette", "The cover on a tape whose reels turn as it plays"),
}

/** The knob at the progress bar's playhead ([AUTO]: a dot with the Thumb style, none with the others). */
enum class ScrubberThumb(val label: String) { AUTO("With the style"), NONE("None"), DOT("Dot"), LINE("Line"), RING("Ring") }

/** How the visualizer moves with the music (1.7.4). */
enum class VisualizerStyle(val label: String, val note: String) {
    OFF("Off", "Nothing moves with the music"),
    BARS("Bars", "Along the bottom of the player"),
    WAVE("Wave", "A soft wave behind the title"),
    RING("Ring", "Around the cover"),
    PULSE("Pulse", "The progress bar pulses with the beat"),
    GLOW("Glow", "The background breathes with the music"),
}

/** The parts under the player's title, in the order the listener arranged them. */
enum class DeckPart(val label: String) { STRIP("Current lyric"), SCRUBBER("Progress bar"), TRANSPORT("Controls"), VOLUME("Volume"), BUTTONS("Buttons") }

/** What a button in the player's bottom row does: the listener picks three to six, in order. */
enum class PlayerButton(val label: String) {
    LYRICS("Lyrics"), VIDEO("Video"), QUEUE("Up next"), OUTPUT("Play on"), SLEEP("Sleep timer"), LIKE("Like"),
    DISLIKE("Not for me"), ADD_TO_PLAYLIST("Add to playlist"), SHARE("Share"), SING("Sing"), DJ("DJ mode"),
    EQUALIZER("Equalizer"), DOWNLOAD("Download"), RADIO("Start radio"), SPEED("Speed");

    companion object {
        /** As 1.7 had it: lyrics, the music video and the queue. */
        val DEFAULT = listOf(LYRICS, VIDEO, QUEUE)
        const val MIN = 3
        const val MAX = 6
    }
}

/**
 * The buttons beside previous / play / next in the media session, in order: the phone's notification and lock screen
 * show the first two, Android Auto and other controllers show them all.
 */
enum class SessionButton(val label: String) {
    LIKE("Like"), SHUFFLE("Shuffle"), REPEAT("Repeat"), CAR_LYRICS("Lyrics in the car"), RADIO("Start radio"),
    DISLIKE("Not for me"), BACK_10("Back 10 s"), AHEAD_30("Ahead 30 s");

    companion object {
        /** As 1.7 had them (like and shuffle beside play on the phone). */
        val DEFAULT = listOf(LIKE, SHUFFLE, REPEAT, CAR_LYRICS, RADIO, DISLIKE)
    }
}

/** The progress bar's color. */
enum class ScrubberColor(val label: String) { SPECTRUM("Your light"), ACCENT("Accent"), WHITE("White"), COVER("From the cover") }

// ---------------------------------------------------------------------------- Lyrics, your way (1.7.5)

/** How the line being sung is drawn. [PRISM] is the lyrics as 1.7 drew them. */
enum class LyricsStyle(val label: String, val note: String) {
    PRISM("Prism", "Each word grows bolder as it's sung"),
    KARAOKE("Classic karaoke", "Color fills the words as they're sung"),
    CARD("Bold card", "Big bold lines on the cover's color"),
    GLOW("Glow", "The sung words glow"),
    TYPEWRITER("Typewriter", "Words appear as they're sung"),
    CALM("Calm", "No animation: the line simply brightens"),
}

/** What the player's volume slider changes (#116): the phone's media volume (as before), or Prism's own. */
enum class VolumeControl(val label: String) { PHONE("The phone's volume"), PRISM("Prism's own volume") }

/** A color for lyrics: white, your light, the accent, the cover's, or one of your own. */
enum class LyricsColor(val label: String) { WHITE("White"), SPECTRUM("Your light"), ACCENT("Accent"), COVER("From the cover"), CUSTOM("Your color") }

/** How heavy the lyrics are; the sung word grows two steps bolder (Prism and Bold card). [LIGHT] as 1.7. */
enum class LyricsWeight(val label: String, val weight: Int) { THIN("Thin", 200), LIGHT("Light", 300), REGULAR("Regular", 400), MEDIUM("Medium", 500), BOLD("Bold", 700) }

/** Where the line being sung settles on the lyrics page ([fraction] of the way down). [TOP] as 1.7. */
enum class LyricsPosition(val label: String, val note: String, val fraction: Float) {
    TOP("Near the top", "The most lines ahead show", 0f),
    THIRD("A third down", "A few sung lines stay in view", 0.28f),
    MIDDLE("In the middle", "Centered, as on a lyric sheet", 0.42f),
}

/** How many of the lines to come show on the lyrics page. [ALL] as 1.7. */
enum class LyricsAhead(val label: String, val lines: Int) { ALL("All", Int.MAX_VALUE), THREE("Three", 3), ONE("One", 1), NONE("None", 0) }

/** Where a translation or romanized line goes. */
enum class LyricsExtraPlace(val label: String) { BELOW("Under the line"), ABOVE("Over the line") }

/**
 * What's behind the lyrics: on the lyrics page [PLAYER] is the player's own background (as 1.7), in immersive mode it's
 * the cover filling the screen (as 1.7).
 */
enum class LyricsBackground(val label: String, val note: String) {
    PLAYER("As now", "The player's background, or the cover in immersive mode"),
    DARK("Plain dark", "Nothing but the words"),
    BLUR("Blurred cover", "The cover, soft and dimmed"),
    CANVAS("Animated cover", "The song's moving cover when it has one"),
    COVER_COLOR("The cover's color", "One color taken from the cover"),
    COLOR("Your colors", "A color, or two as a gradient"),
}
enum class PlayerTitleAlign(val label: String) { START("Left"), CENTER("Centered") }
enum class PlayerTitleSize(val label: String, val scale: Float) { SMALL("Small", 0.82f), MEDIUM("Medium", 1f), LARGE("Large", 1.2f) }
/** The times under the progress bar. */
enum class PlayerTimes(val label: String) { BOTH("Both"), ELAPSED("Elapsed"), LEFT("Time left"), NONE("None") }

/** What a gesture on the player's cover (or the mini player) does. */
enum class PlayerGesture(val label: String) {
    NEXT("Next song"), PREVIOUS("Previous song"), SEEK_BACK("Back 10 s"), SEEK_AHEAD("Ahead 10 s"),
    /** Double tap only: the cover's left half goes back, its right half ahead (#79). */
    SEEK_SIDES("Seek by side"),
    PLAY_PAUSE("Play / pause"), LIKE("Like"), QUEUE("Up next"), LYRICS("Lyrics"), SLEEP("Sleep timer"), VIDEO("Video"),
    SING("Sing"), MENU("Song menu"), STOP("Stop"), NONE("Nothing");

    companion object {
        /** The choices offered for a gesture ([double]: double tap, which also has [SEEK_SIDES]; [mini]: the mini player). */
        fun choices(double: Boolean = false, mini: Boolean = false): List<PlayerGesture> = entries.filter {
            when (it) {
                SEEK_SIDES -> double
                STOP -> mini
                LYRICS, VIDEO, SING -> !mini
                else -> true
            }
        }
    }
}
enum class LyricsAlign(val label: String) { START("Left"), CENTER("Center") }
/** Lyrics in a script the listener can't read, in Latin letters too: under each line, or in its place. */
enum class RomanizeMode(val label: String) { OFF("Off"), UNDER("Under each line"), INSTEAD("Instead") }
enum class LibraryView(val label: String) { GRID("Grid"), LIST("List") }
/** The top of Home (1.7.6): the greeting as before, only its buttons, or the greeting over a picture. */
enum class HomeHeader(val label: String, val note: String) {
    GREETING("Greeting", "The date and a hello, as now"),
    NONE("Just the buttons", "No greeting: Home starts with its sections"),
    PICTURE("A picture", "Your photo or a design behind the greeting"),
    NOW_PLAYING("Now playing", "The playing cover, frosted, behind it"),
}
enum class HomeHeaderHeight(val label: String, val dp: Int) { SHORT("Short", 200), MEDIUM("Medium", 260), TALL("Tall", 340) }

/** What Search shows before you type (1.7.6), in the listener's order; the ones left out are hidden. */
enum class SearchSection(val label: String) {
    // The shortcut tiles (Recognize, Find by lyrics, Podcasts, Your files) went before release: the search box has
    // Recognize, a line of lyrics finds its song, and podcasts and your files are searched with everything else.
    RECENT("Recent"), ARTISTS("Your artists"), MOODS("Moods & genres");

    companion object {
        fun parse(v: String?): List<SearchSection> =
            v?.split(',')?.mapNotNull { n -> entries.firstOrNull { it.name == n } }?.distinct()?.takeIf { it.isNotEmpty() } ?: entries.toList()
    }
}

/** Covers across Library's grid: Auto fits as many as the width allows (as before 1.7.6). */
enum class LibraryColumns(val label: String, val count: Int) { AUTO("Auto", 0), TWO("2", 2), THREE("3", 3), FOUR("4", 4) }

/** "PLAYLISTS=GRID|ALBUMS=LIST": Library tabs shown their own way. */
fun parseLibraryViews(v: String): Map<String, LibraryView> = v.split('|').mapNotNull { part ->
    val tab = part.substringBefore('=').takeIf { it in setOf("PLAYLISTS", "ALBUMS", "ARTISTS") } ?: return@mapNotNull null
    LibraryView.entries.firstOrNull { it.name == part.substringAfter('=', "") }?.let { tab to it }
}.toMap()
/** The tiles at the top of Library's Playlists tab (Reddit 1.7: "let me reorder or hide them"); shown in the order kept. */
enum class LibraryTile(val label: String) {
    LIKED("Liked songs"), DOWNLOADS("Downloads"), LOCAL("Local files"), REPLAY("Replay"), PODCASTS("Podcasts"),
    TOGETHER("Listen together"), BLEND("Blend"), RECOGNIZED("Recognized"), NEW_PLAYLIST("New playlist"), IMPORT("Import playlists"),
}

@Serializable
data class HomeSectionConfig(
    val key: String,
    val title: String,
    val visible: Boolean = true,
    val style: SectionStyle = SectionStyle.CAROUSEL,
    /** The listener's own name for it (1.7.6); null keeps its own. */
    val name: String? = null,
    /** A pinned album's, playlist's or artist's picture (1.7.6), for Customize Home's list. */
    val art: String? = null,
) {
    /** What Home calls it: the listener's name for it, else [default] (or its own title). */
    fun titled(default: String = title) = name ?: default
}

@Serializable
data class EqSettings(
    val enabled: Boolean = false,
    val preset: String = "Flat",
    /** Gains in dB for the 10 ISO bands (31Hz .. 16kHz). */
    val bands: List<Float> = List(10) { 0f },
    val bassBoost: Int = 0,      // 0..1000
    val virtualizer: Int = 0,    // 0..1000
    val loudnessGainMb: Int = 0, // millibels
    val preampDb: Float = 0f,
)

object HomeSections {
    const val GREETING = "local:greeting"
    const val QUICK_PICKS = "local:quick_picks"
    const val RECENT = "local:recent"
    const val LIKED = "local:liked"
    const val DOWNLOADS = "local:downloads"
    const val REPLAY = "local:replay"
    const val GENRES = "local:genres"
    /** YouTube Music's Speed dial (#53): your go-to songs, albums, playlists and artists as big tiles, nine to a page. */
    const val SPEED_DIAL = "local:speed_dial"
    const val MOODS = "yt:moods"
    /** Prism's own mixes: the time-of-day mix, Daily Mixes, Release Radar. */
    const val MADE_FOR_YOU = "local:made_for_you"
    /** Release Radar's new albums, singles and EPs from the listener's artists. */
    const val NEW_RELEASES = "local:new_releases"
    /** Made on the phone from your own plays: your month's most played, what you used to play, likes gone quiet, mixes. */
    const val ON_REPEAT = "local:on_repeat"
    const val REPEAT_REWIND = "local:repeat_rewind"
    const val FORGOTTEN = "local:forgotten"
    /** Was its own section ("Mixes from your music"); its mixes are in Made for you now ([mergeYourMixes]). */
    const val YOUR_MIXES = "local:your_mixes"
    const val YT_PREFIX = "yt:shelf:"
    /** GREETING's key predates the rename: the section is only the shortcut tiles. */
    const val TILES_TITLE = "Shortcut tiles"
    /** Release Radar's section: not "New releases", which is YouTube's own shelf on Home too. */
    const val NEW_RELEASES_TITLE = "New from your artists"

    /** An album, playlist, artist or mix pinned to Home as a section of its own (1.7.6): "pin:album:<id>". */
    const val PIN_PREFIX = "pin:"
    fun pinKey(kind: String, id: String) = "$PIN_PREFIX$kind:$id"

    /** Sections made from rules over your own listening (1.7.6). */
    const val LIKED_MONTH = "rule:liked_month"
    const val UNPLAYED_DOWNLOADS = "rule:unplayed_downloads"
    const val YEAR_PREFIX = "rule:year:"
    fun yearKey(year: Int) = "$YEAR_PREFIX$year"

    /** Kinds of YouTube Music's own sections that can be left off Home (1.7.6). */
    const val KIND_VIDEOS = "VIDEOS"
    const val KIND_PODCASTS = "PODCASTS"

    val defaults = listOf(
        HomeSectionConfig(REPLAY, "Replay teaser", style = SectionStyle.HERO),
        HomeSectionConfig(GREETING, TILES_TITLE, style = SectionStyle.HERO),
        HomeSectionConfig(QUICK_PICKS, "Quick picks", style = SectionStyle.LIST),
        HomeSectionConfig(MADE_FOR_YOU, "Made for you"),
        HomeSectionConfig(NEW_RELEASES, NEW_RELEASES_TITLE),
        HomeSectionConfig(RECENT, "Recently played"),
        HomeSectionConfig(GENRES, "Your top genres", style = SectionStyle.GRID),
        HomeSectionConfig(MOODS, "Moods & genres", style = SectionStyle.GRID),
        HomeSectionConfig(LIKED, "Liked songs"),
        HomeSectionConfig(DOWNLOADS, "Downloads", visible = false),
        HomeSectionConfig(SPEED_DIAL, "Speed dial", style = SectionStyle.GRID),
        HomeSectionConfig(ON_REPEAT, "On repeat"),
        HomeSectionConfig(REPEAT_REWIND, "Repeat rewind"),
        HomeSectionConfig(FORGOTTEN, "Forgotten favorites"),
    )

    /** What a fresh install's Home shows (and what Reset goes back to). */
    val initial = listOf(
        HomeSectionConfig(REPLAY, "Replay teaser", style = SectionStyle.HERO),
        HomeSectionConfig(GREETING, TILES_TITLE, style = SectionStyle.HERO),
        HomeSectionConfig(QUICK_PICKS, "Quick picks", style = SectionStyle.LIST),
        HomeSectionConfig(MADE_FOR_YOU, "Made for you"),
        HomeSectionConfig(NEW_RELEASES, NEW_RELEASES_TITLE),
        HomeSectionConfig(ON_REPEAT, "On repeat"),
        HomeSectionConfig("${YT_PREFIX}mixed for you", "Mixed for you"),
    )

    /**
     * Sections added in a later Prism, put into an existing layout once (after Quick picks, or at the top), so people
     * who customized Home before still get them; removing them afterwards sticks.
     */
    fun withNewSections(
        layout: List<HomeSectionConfig>,
        sections: List<HomeSectionConfig> = listOf(HomeSectionConfig(MADE_FOR_YOU, "Made for you"), HomeSectionConfig(NEW_RELEASES, NEW_RELEASES_TITLE)),
        after: String = QUICK_PICKS,
    ): List<HomeSectionConfig> {
        val add = sections.filter { n -> layout.none { it.key == n.key } }
        if (add.isEmpty()) return layout
        val at = layout.indexOfFirst { it.key == after }.let { if (it < 0) layout.indexOfFirst { it.key == QUICK_PICKS } else it }
            .let { if (it < 0) minOf(1, layout.size) else it + 1 }
        return layout.toMutableList().apply { addAll(at, add) }
    }

    /**
     * "Mixes from your music" is part of Made for you now: a layout with both keeps Made for you where it was; one with
     * only the old section gets Made for you in its place (shown or hidden, in its style, as it was).
     */
    fun mergeYourMixes(layout: List<HomeSectionConfig>): List<HomeSectionConfig> {
        val old = layout.indexOfFirst { it.key == YOUR_MIXES }
        if (old < 0) return layout
        if (layout.any { it.key == MADE_FOR_YOU }) return layout.filterNot { it.key == YOUR_MIXES }
        return layout.toMutableList().apply { this[old] = this[old].copy(key = MADE_FOR_YOU, title = "Made for you") }
    }

    /**
     * The Replay card heads Home (1.7), as on a fresh install: an older layout's Replay teaser moves to the top as the
     * card (Spotlight), shown or hidden as it was; a layout without one gets it.
     */
    fun replayOnTop(layout: List<HomeSectionConfig>): List<HomeSectionConfig> {
        val replay = layout.firstOrNull { it.key == REPLAY } ?: HomeSectionConfig(REPLAY, "Replay teaser")
        return listOf(replay.copy(style = SectionStyle.HERO)) + layout.filterNot { it.key == REPLAY }
    }
}

/** The order lyric sources are tried in on a fresh install. */
val defaultLyricsOrder = listOf(
    LyricsSource.KUGOU, LyricsSource.QQ, LyricsSource.APPLE, LyricsSource.LYRICSPLUS, LyricsSource.BINI, LyricsSource.YTMUSIC,
    LyricsSource.NETEASE, LyricsSource.LRCLIB, LyricsSource.UNISON, LyricsSource.GENIUS,
)

data class AppSettings(
    val onboarded: Boolean = false,
    // Account
    val cookie: String = "",
    val visitorData: String = "",
    val dataSyncId: String = "",
    /** Which of the Google accounts signed in on this phone's session requests go out as (X-Goog-AuthUser). */
    val authUser: Int = 0,
    /** Discord shows what's playing (Settings → Discord). Off until the listener signs in to Discord there. */
    val discordPresence: Boolean = false,
    val accountName: String = "",
    val accountEmail: String = "",
    val accountAvatar: String = "",
    // Appearance
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val pureBlack: Boolean = false,
    val colorSource: ColorSource = ColorSource.CUSTOM,
    val accent: Int = 0xFF3A86FF.toInt(),
    val lightStyle: LightStyle = LightStyle.SPECTRUM,
    /** The accent as picked (true), or tuned by Prism for dark and light pages (false, as before 1.7.2). */
    val accentExact: Boolean = false,
    /** A different accent on light pages; 0 = the same as [accent]. */
    val accentLight: Int = 0,
    /** The split light's colors: a [com.prism.music.ui.theme.SpectrumPresets] key, or "CUSTOM" for [spectrumStops]. */
    val spectrumPreset: String = "PRISM",
    val spectrumStops: List<Int> = emptyList(),
    /** The split light in hard bands (as the logo's bars) instead of a blend. */
    val spectrumBands: Boolean = false,
    val surfaceTint: SurfaceTint = SurfaceTint.AUTO,
    /** 0 none … 1 Prism's usual hint … 3 strong. */
    val surfaceTintStrength: Float = 1f,
    val surfaceColor: Int = 0xFF3A86FF.toInt(),
    val contrast: ContrastLevel = ContrastLevel.STANDARD,
    val artworkSwatch: ArtworkSwatch = ArtworkSwatch.VIBRANT,
    /** Keep the cover's color lively (a floor on its saturation). */
    val artworkVivid: Boolean = false,
    val artworkChange: ArtworkChange = ArtworkChange.FADE,
    /**
     * Artist, album and playlist pages (and menus) take their accent from their picture instead of the listener's.
     * Off by default (owner, 2026-10-22): the accent picked on the Color page shows on every page.
     */
    val pageColors: Boolean = false,
    val liquidGlass: Boolean = false,
    val glassKind: GlassKind = GlassKind.REGULAR,
    /** Blurred, non-refractive glass. Mutually exclusive with Liquid Glass. */
    // Aura × Spectrum (1.7): floating glass (tab bar, mini player) blurs what's behind it.
    val frostedGlass: Boolean = true,
    /** Taps and swipes on player controls give a little buzz. */
    val haptics: Boolean = true,
    /** Artwork runs edge to edge at the top of the player, dissolving into the backdrop. */
    // Aura × Spectrum: the cover framed, with a glow of its own color.
    val fullBleedArtwork: Boolean = false,
    /** A music video fills the top of the player edge to edge, as YouTube Music's does (#120), instead of a box. */
    val videoFullBleed: Boolean = false,
    /** Immersive mode (the player's ⋯ menu): the cover fills the screen, lyrics play over it, everything else hides. */
    val immersivePlayer: Boolean = false,
    val immersiveArt: ImmersiveArt = ImmersiveArt.ZOOMED,
    val immersiveLyrics: ImmersiveLyrics = ImmersiveLyrics.LOW,
    /** Immersive mode keeps previous / play / next on screen instead of only after a tap. */
    val immersiveControls: Boolean = false,
    /** Shuffle and repeat buttons either side of the player's transport, always (not only with the queue up). */
    val playerShuffleRepeat: Boolean = false,
    /** How much of what's queued is loaded ahead while a song plays ([Preload]). */
    val preload: Preload = Preload.START,
    /** Songs are loaded ahead ([preload]: a start or whole songs) on mobile data too (else only on Wi-Fi). */
    val preloadCellular: Boolean = false,
    /** Animated cover art (motion artwork / video covers) over the sleeve. */
    val canvasEnabled: Boolean = true,
    val canvasOnCellular: Boolean = true,
    val hideVolumeBar: Boolean = true,
    /** "Playing from …" above the artwork in the player. */
    val showPlayingFrom: Boolean = true,
    val lyricsBlur: Boolean = true,
    /** The thin line of split light under the line being sung (lyrics page and immersive). */
    val lyricsProgressLine: Boolean = true,
    /** Each screen's background (screen name -> "STYLE" or "PHOTO|path"); missing means the default. */
    val backdrops: Map<String, String> = emptyMap(),
    /** How much the theme color washes over custom backgrounds (0 = none). */
    val backdropDim: Float = 0.35f,
    val backdropMotion: Boolean = true,
    // Lyrics
    val lyricsOnPlayer: Boolean = true,
    val lyricsOrder: List<LyricsSource> = defaultLyricsOrder,
    /** Lyric sources the listener turned off: never asked (#51). */
    val lyricsOff: Set<LyricsSource> = emptySet(),
    val preferSynced: Boolean = true,
    /** Pass over sources whose lyrics star out swear words when another has them in full. */
    val skipCensored: Boolean = true,
    /** A translation under each lyric line (machine translation, asked of Google Translate), when the song isn't in it. */
    val lyricsTranslate: Boolean = false,
    /** Language code to translate lyrics into; blank means the phone's language. */
    val lyricsTranslateTo: String = "",
    /** Romanized lyrics (Lyrics → Display). */
    val romanize: RomanizeMode = RomanizeMode.UNDER,
    /** Also scripts of the phone's own language (off: a Korean phone isn't shown Korean in Latin letters). */
    val romanizeAll: Boolean = false,
    /** Sing gets songs ready (downloads them whole) on mobile data too. */
    val singOnCellular: Boolean = false,
    /** Settings → Lyrics → Sing: the playing and next songs are got ready even while Sing is off, so it starts at once. */
    val singAlwaysReady: Boolean = false,
    /** How much room Sing's separated vocals may take, in MB. */
    val singCacheMb: Int = 1024,
    /** DJ mode's energy curve (picked in the queue, where DJ mode is switched on for the queue playing). */
    val djFlow: com.prism.music.playback.DjFlow = com.prism.music.playback.DjFlow.STEADY,
    /** A notification when an artist the listener starred puts something out (Release Radar). */
    val releaseNotifications: Boolean = true,
    /** A notification on 1 December when the year's Rewind is ready. */
    val rewindNotification: Boolean = true,
    /** Prism Connect: control Prism from a browser on the same Wi-Fi (off until switched on; paired browsers only). */
    val webRemote: Boolean = false,
    /** Prism Connect: other Prism devices on the Wi-Fi can find this one and move what they're playing here. */
    val handoffAvailable: Boolean = false,
    val lyricsScale: Float = 1f,
    /** Lyrics on Android Auto's player (the car's Lyrics button flips this too). */
    val carLyrics: Boolean = true,
    val carLyricsStyle: CarLyricsStyle = CarLyricsStyle.TEXT,
    /** Also on the phone's own media controls (notification, lock screen) when not in the car. */
    val carLyricsOnPhone: Boolean = false,
    /**
     * The song's title and artist stay as they are and the lyrics show only on the cover (Karaoke and Line by line):
     * the car's small media card reads as usual, while its full player shows the lyric card big.
     */
    val carLyricsKeepTitle: Boolean = false,
    /** Android Auto shows Home and Library as cover tiles under section headings (like Spotify's), not plain lists. */
    val autoGrid: Boolean = true,
    // Playback
    val showNerdStats: Boolean = false,
    /** Downloaded songs save their animated cover too. */
    val canvasWithDownloads: Boolean = true,
    /** ...on mobile data as well (off: they're saved once on Wi-Fi). */
    val canvasWithDownloadsCellular: Boolean = false,
    /** The stream's quality (codec and bitrate, or "Downloaded") under the player's scrubber. */
    val showQuality: Boolean = true,
    /** Videos played as songs get a square cover: the thumbnail's black bars trimmed and its middle cut square (#92). */
    val squareVideoCovers: Boolean = true,
    /** Lyrics are looked up on mobile data as each song starts; off, only once the lyrics are opened (#95). */
    val lyricsOnMobileData: Boolean = true,
    /** A "Best part" button by the scrubber in a song's first seconds, to skip to its most replayed stretch. */
    val bestPartButton: Boolean = true,
    /** The Song / Video switch on the player. Off, the player stays audio only. */
    val showVideoSwitch: Boolean = true,
    /** Holding previous / next seeks back / forward this many seconds, again every moment it's held (0 = off). */
    val holdSeekSeconds: Int = 10,
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    val cellularQuality: AudioQuality = AudioQuality.HIGH,
    /** Ask for songs as the signed-in account so Music Premium's 256 kbps streams come (StreamResolver.premiumFormats). */
    val premiumStream: Boolean = false,
    val autoplay: Boolean = true,
    /** Where Autoplay's songs come from: your own music, YouTube Music's radio, or both (offline it's always yours). */
    val autoplaySource: AutoplaySource = AutoplaySource.MIXED,
    /** Home's Replay card look ([com.prism.music.ui.screens.ReplayCardStyle] name). */
    val replayCard: String = "WASH",
    /** Autoplay and radios play songs: music videos swap for their song, and videos with no song version are left out (#39). */
    val radioSongsOnly: Boolean = true,
    /**
     * "Songs, not videos" everywhere (#80): with [radioSongsOnly], a music video in any queue (search, albums, playlists,
     * Liked songs, downloads) plays as its song, the video kept for the Video button; without a song it plays as it is.
     */
    val songsEverywhere: Boolean = false,
    /** The player's right-hand time shows the song's length instead of the time left (#82; tapping it switches). */
    val showTrackLength: Boolean = false,
    /** Playback diagnostics (Settings → About): a log of what the audio did, for chasing stutters ([AudioDiagnostics]). */
    val audioDiagnostics: Boolean = false,
    /** Search opens with the keyboard up (#71). */
    val searchKeyboard: Boolean = false,
    /** Double-tapping the left or right of the player's cover seeks back / ahead this many seconds (#79); 0 = off. */
    val doubleTapSeekSeconds: Int = 10,
    /** Swiping Prism away from Recents stops the music (#45); off, it keeps playing in the background. */
    val stopOnSwipeAway: Boolean = false,
    /**
     * Data saver, on mobile data (#40): smaller pictures, no artist header photos or animated covers, and covers
     * and lyrics only fetched for what's on screen. Wi-Fi is unaffected.
     */
    val dataSaver: Boolean = false,
    /** With a music video showing, the next song plays as its video too (until switched back to the song). */
    val videoContinues: Boolean = true,
    /** Lossless playback: always the best stream, hi-res output, and the lossless add-on when it's on. */
    val lossless: Boolean = true,
    /** The lossless add-on: play matching FLAC/WAV/AIFF files on the phone instead of the stream. */
    val losslessLocal: Boolean = false,
    val normalize: Boolean = true,
    val skipSilence: Boolean = false,
    /** Crossfade: songs fade into each other when one runs into the next (after Spotify's), instead of stopping and starting. */
    val mix: Boolean = false,
    /** How long a crossfade lasts, in seconds, when it isn't [mixAuto]. */
    val mixSeconds: Int = 6,
    /** Crossfade works out the best length for each pair of songs, from how one ends and the next begins. */
    val mixAuto: Boolean = true,
    /** DJ-style: lines up the songs' beats and plays the incoming song at the outgoing one's tempo during the crossfade. */
    val mixBeatMatch: Boolean = false,
    /** The card's jump button goes this far before the planned drop (seconds). */
    val dropLeadSeconds: Int = 10,
    /** Tempo analysis (a 1–4 MB download per song) may use mobile data, not only Wi-Fi. */
    val mixTempoOnCellular: Boolean = false,
    val videoMaxHeight: Int = 1080,
    /**
     * The country YouTube Music is asked as (ISO code, e.g. "US"); blank follows the phone. Where YouTube Music
     * isn't offered, asking as a country where it is brings Home, charts and search back.
     */
    val contentCountry: String = "",
    // Storage & downloads
    val cacheSizeMb: Int = 4096,
    val downloadWifiOnly: Boolean = true,
    val downloadLikes: Boolean = true,
    /** Smart Downloads' picks: the songs you're most likely to play are downloaded ahead. */
    val smartDownloads: Boolean = true,
    /** Space smart downloads may fill, in GB (before 1.6.3; now part of [offlineGb]). */
    val smartDownloadGb: Float = 1f,
    /**
     * Smart Downloads' space, in GB: its picks plus the songs you've streamed (the song cache), which used to be two
     * settings. Phones that had them start with their sum.
     */
    val offlineGb: Float = 5f,
    /** Smart Downloads may download its picks on mobile data too. */
    val smartDownloadsCellular: Boolean = false,
    // Audio effects
    val eq: EqSettings = EqSettings(bassBoost = 32, virtualizer = 6, loudnessGainMb = 39),
    /** Prism Spatial, the software spatializer. */
    val spatial: Boolean = false,
    val spatialAmount: Float = 0.75f,
    // Home
    val homeLayout: List<HomeSectionConfig> = HomeSections.initial,
    val autoAddSections: Boolean = true,
    /** Search's page before you type (1.7.6): its sections, in order. */
    val searchSections: List<SearchSection> = SearchSection.entries.toList(),
    val homeHeader: HomeHeader = HomeHeader.GREETING,
    val homeHeaderHeight: HomeHeaderHeight = HomeHeaderHeight.MEDIUM,
    /** The picture behind a PICTURE header: a background design's name, or "PHOTO|<file>" (as backgrounds keep theirs). */
    val homeHeaderPicture: String = "AURORA",
    /** Kinds of YouTube Music's sections left off Home ([HomeSections.KIND_VIDEOS], [HomeSections.KIND_PODCASTS]; 1.7.6). */
    val homeHideKinds: Set<String> = emptySet(),
    /** Replay shows listening time as hours and minutes (20h 4m) instead of minutes (1,204 min). */
    val replayHours: Boolean = true,
    /** Home's headline; blank means good morning / afternoon / evening. */
    val greetingText: String = "",
    val greetingShowName: Boolean = true,
    val homeShortcuts: List<HomeShortcut> = listOf(HomeShortcut.LIKED, HomeShortcut.DOWNLOADS, HomeShortcut.REPLAY),
    /** Recently played albums added after the shortcuts. */
    val homeRecentTiles: Int = 3,
    /** Playlists shown as tiles after the shortcuts. */
    val homePlaylists: List<PinnedPlaylist> = emptyList(),
    val homeCustomizeButton: Boolean = true,
    /** Settings, Home screen, Continue line: the Continue line under Home's greeting. */
    val homeContinue: Boolean = true,
    /** Tapping a song in Quick picks starts a radio from it (as on YouTube Music), instead of playing Quick picks in order (#38). */
    val quickPicksRadio: Boolean = true,
    /** Home's Speed dial shows only YouTube's own tiles, without the albums and songs Prism adds from your plays (#131). */
    val speedDialYouTubeOnly: Boolean = false,
    /** The round Customize button at the top of Home (beside Podcasts); separate from the one at the bottom. */
    val homeCustomizeTopButton: Boolean = true,
    /** The Podcasts button at the top of Home. Off, Podcasts opens from a Library tile instead (#28). */
    val homePodcastsButton: Boolean = true,
    // Interface
    val fontChoice: FontChoice = FontChoice.PRISM,
    /** The imported app font's name, and when it (or the lyrics font) was last imported, so the UI picks it up. */
    val appFontName: String = "",
    val fontStamp: Long = 0,
    /** The lyrics' font: "" = the app's, else a [FontChoice] name (CUSTOM = its own imported file, [lyricsFontName]). */
    val lyricsFont: String = "",
    val lyricsFontName: String = "",
    val textScale: Float = 1f,
    val corners: Corners = Corners.ROUND,
    val uiDensity: UiDensity = UiDensity.COMFORTABLE,
    val titleSize: TitleSize = TitleSize.LARGE,
    val cardSize: CardSize = CardSize.MEDIUM,
    val reduceMotion: Boolean = false,
    // Navigation
    // Aura × Spectrum: a floating glass capsule.
    val navStyle: NavBarStyle = NavBarStyle.FLOATING,
    val navLabels: NavLabels = NavLabels.ALWAYS,
    val navHideOnScroll: Boolean = true,
    val navTabs: List<NavTab> = listOf(NavTab.HOME, NavTab.LIBRARY, NavTab.SEARCH, NavTab.REPLAY),
    /** Tabs given their own names (1.7.6); the rest keep theirs. */
    val tabNames: Map<NavTab, String> = emptyMap(),
    val navBarSize: NavBarSize = NavBarSize.STANDARD,
    val navBarFill: NavBarFill = NavBarFill.GLASS,
    /** Swiping sideways across a tab's page goes to the tab beside it (1.7.6, off by default). */
    val swipeTabs: Boolean = false,
    /** The mini player and the floating tab bar as one piece of glass (1.7.6). */
    val miniPlayerJoined: Boolean = false,
    /** Local files' sections, shown in this order. */
    val localSections: List<LocalSection> = LocalSection.entries.toList(),
    /** Local files → Songs' order (a name from the page's sort list). */
    val localSort: String = "TITLE",
    val startTab: NavTab = NavTab.HOME,
    val miniPlayerStyle: MiniPlayerStyle = MiniPlayerStyle.CARD,
    val miniSkipButtons: Boolean = true,
    /** The mini player's previous button (off by default: swiping right goes back too). */
    val miniPrevButton: Boolean = false,
    val miniProgress: MiniProgress = MiniProgress.LINE,
    // Player look
    val playerBackground: PlayerBackground = PlayerBackground.MESH,
    /** 0..1: how much of the player's background shows through its darkening (blurred cover and your background). */
    val playerBackdropShow: Float = 0.35f,
    val playerArtCorners: Int = 20,
    val coverSize: CoverSize = CoverSize.STANDARD,
    // Aura × Spectrum (1.7): a solid play button, ringed in split light.
    val transportStyle: TransportStyle = TransportStyle.BUTTON,
    val scrubberStyle: ScrubberStyle = ScrubberStyle.HAIRLINE,
    val artShrinkOnPause: Boolean = true,
    val showOutputDevice: Boolean = true,
    val lyricsAlign: LyricsAlign = LyricsAlign.START,
    // Lyrics, your way (1.7.5). Every default draws the lyrics as 1.7.4 did.
    val lyricsStyle: LyricsStyle = LyricsStyle.PRISM,
    /** Focus blur's strength (0 off … 2 strong; 1 as before). It replaces the old on/off [lyricsBlur], which it starts from. */
    val lyricsBlurAmount: Float = 1f,
    /** Room between lines (0.6 tight … 1.8 airy; 1 as before). */
    val lyricsSpacing: Float = 1f,
    /** How bright the lines not being sung are (0.2 … 1; 0.55 as before). */
    val lyricsOthersAlpha: Float = 0.55f,
    val lyricsSungColor: LyricsColor = LyricsColor.WHITE,
    val lyricsSungCustom: Int = 0xFFFFD166.toInt(),
    val lyricsOtherColor: LyricsColor = LyricsColor.WHITE,
    val lyricsOtherCustom: Int = 0xFFB8C4FF.toInt(),
    val lyricsWeight: LyricsWeight = LyricsWeight.LIGHT,
    /** Background vocals' size against their line (0.4 … 0.9; 0.6 as before). */
    val lyricsBackingSize: Float = 0.6f,
    /** A soft shadow under the words, for photo and animated backgrounds. */
    val lyricsShadow: Boolean = false,
    val lyricsUppercase: Boolean = false,
    val lyricsAhead: LyricsAhead = LyricsAhead.ALL,
    val lyricsPosition: LyricsPosition = LyricsPosition.TOP,
    /** Translations and romanized lines: size (0.7 … 1.4 of before), color, italics and where they go. */
    val lyricsExtraSize: Float = 1f,
    val lyricsExtraColor: LyricsColor = LyricsColor.WHITE,
    val lyricsExtraItalic: Boolean = false,
    val lyricsExtraPlace: LyricsExtraPlace = LyricsExtraPlace.BELOW,
    val lyricsBackground: LyricsBackground = LyricsBackground.PLAYER,
    val immersiveBackground: LyricsBackground = LyricsBackground.PLAYER,
    /** [LyricsBackground.COLOR]'s colors; the second is a gradient's end (0: one color). */
    val lyricsBgColor: Int = 0xFF1B1530.toInt(),
    val lyricsBgColor2: Int = 0,
    /** The current-lyric strip and immersive mode draw the lyrics as the lyrics page does (style, colors, weight…). */
    val lyricsEverywhere: Boolean = true,
    /** The current-lyric line on the player (#121): where it sits, and whether a long line may take two lines. */
    val lyricsStripAlign: LyricsAlign = LyricsAlign.START,
    val lyricsStripTwoLines: Boolean = false,
    // GitHub requests picked for 1.7.5.
    /** #111: a liked song and its liked music video show once, as the song (Liked songs, Library, the car). */
    val mergeLikedVideos: Boolean = true,
    /** #115: the status bar stays hidden in Prism (a swipe from the top shows it for a moment). */
    val hideStatusBar: Boolean = false,
    /** #116: what the player's volume slider changes, and Prism's own level (0 … 1) when it's Prism's. */
    val volumeControl: VolumeControl = VolumeControl.PHONE,
    val appVolume: Float = 1f,
    // The player, your way (1.7.4). Every default is the player as 1.7 had it.
    val playerLayout: PlayerLayout = PlayerLayout.STANDARD,
    /** Vinyl: the tonearm swings onto the record while it plays. */
    val vinylTonearm: Boolean = true,
    /** The parts under the title, in this order (what's switched off elsewhere still hides). */
    val playerDeck: List<DeckPart> = DeckPart.entries,
    val playerButtons: List<PlayerButton> = PlayerButton.DEFAULT,
    /** The buttons beside play in the notification, on the lock screen and in Android Auto, in order. */
    val sessionButtons: List<SessionButton> = SessionButton.DEFAULT,
    val scrubberColor: ScrubberColor = ScrubberColor.SPECTRUM,
    val playerTitleAlign: PlayerTitleAlign = PlayerTitleAlign.START,
    val playerTitleSize: PlayerTitleSize = PlayerTitleSize.MEDIUM,
    /** A title too long for the line scrolls across (off: it's cut short with "…"). */
    val playerTitleScroll: Boolean = true,
    val playerTimes: PlayerTimes = PlayerTimes.BOTH,
    /**
     * The color mesh behind the player: how fast it drifts (0 still, as before, … 1) and how strong its colors are
     * (0.4 soft … 1.6 rich; 1 as before).
     */
    val meshSpeed: Float = 0f,
    val meshIntensity: Float = 1f,
    /** How soft the blurred cover is (0.5 … 1.5; 1 as before). */
    val playerBlur: Float = 1f,
    /** An animated cover fills the whole player behind everything, not only the cover. */
    val canvasFillsBackground: Boolean = false,
    /** The progress bar's thickness (0.6 thin … 2 thick; 1 as the style draws it) and the knob at the playhead. */
    val scrubberThickness: Float = 1f,
    val scrubberThumb: ScrubberThumb = ScrubberThumb.AUTO,
    /**
     * The visualizer (off by default: it's the one part of the player that works on every frame): moves with the music
     * Prism plays (its own sound, so no microphone), only while the player is on screen.
     */
    val visualizer: VisualizerStyle = VisualizerStyle.OFF,
    val visualizerColor: ScrubberColor = ScrubberColor.SPECTRUM,
    // Gestures on the player's cover and on the mini player.
    val coverSwipeLeft: PlayerGesture = PlayerGesture.NEXT,
    val coverSwipeRight: PlayerGesture = PlayerGesture.PREVIOUS,
    val coverSwipeUp: PlayerGesture = PlayerGesture.QUEUE,
    val coverDoubleTap: PlayerGesture = PlayerGesture.SEEK_SIDES,
    val coverLongPress: PlayerGesture = PlayerGesture.NONE,
    val miniSwipeLeft: PlayerGesture = PlayerGesture.NEXT,
    val miniSwipeRight: PlayerGesture = PlayerGesture.PREVIOUS,
    val miniSwipeDown: PlayerGesture = PlayerGesture.STOP,
    // Library & Replay
    val libraryView: LibraryView = LibraryView.LIST,
    /** Library tabs shown their own way (1.7.6: "PLAYLISTS", "ALBUMS", "ARTISTS"); the rest as [libraryView]. */
    val libraryViews: Map<String, LibraryView> = emptyMap(),
    /** How many covers across Library's grid (1.7.6). */
    val libraryColumns: LibraryColumns = LibraryColumns.AUTO,
    /** Song counts, owners and years under Library's playlists, albums and artists (1.7.6). */
    val librarySubtitles: Boolean = true,
    val libraryStartTab: String = "PLAYLISTS",
    /** Library's tiles that show, in order (the rest are hidden). */
    val libraryTiles: List<LibraryTile> = LibraryTile.entries,
    /** Library → Playlists shows your playlists above the tiles (Liked songs, Downloads…) instead of below them. */
    val libraryPlaylistsFirst: Boolean = false,
    /** Playlist pages open with their picture edge to edge, like an artist page. */
    val playlistHeroCover: Boolean = true,
    /** Artist, album and playlist pages sit on frosted glass made from their picture (Apple Music's look). */
    // Aura × Spectrum (1.7): ambient color from what's playing behind every page.
    val frostedPages: Boolean = true,
    val replayThemeColors: Boolean = false,
    /**
     * Performance mode (1.7.6): Prism drawn as lightly as 1.6 was, for slower phones ([effective]). Every feature stays;
     * only the effects that cost the phone the most each frame are paused, and the listener's own choices come back
     * when it's off.
     */
    val performanceMode: Boolean = false,
    val performanceOffered: Boolean = false,
) {
    val isLoggedIn: Boolean get() = cookie.contains("SAPISID")

    /**
     * What Prism draws with: these settings, or with [performanceMode] the lighter look. Blurred glass, frosted pages
     * and the frost behind every page, moving and illustrated backgrounds (plain pages instead), animated covers, the
     * visualizer, the drifting color mesh, focus blur and glow in the lyrics, the picture at the top of Home, and
     * motion (as Reduce motion). Nothing stored changes.
     */
    fun effective(): AppSettings = if (!performanceMode) this else copy(
        liquidGlass = false,
        frostedGlass = false,
        frostedPages = false,
        backdrops = emptyMap(),
        backdropMotion = false,
        reduceMotion = true,
        canvasEnabled = false,
        canvasFillsBackground = false,
        visualizer = VisualizerStyle.OFF,
        meshSpeed = 0f,
        playerBackground = if (playerBackground == PlayerBackground.BLUR || playerBackground == PlayerBackground.APPEARANCE) PlayerBackground.MESH else playerBackground,
        lyricsBlur = false,
        lyricsBlurAmount = 0f,
        lyricsShadow = false,
        lyricsStyle = if (lyricsStyle == LyricsStyle.GLOW) LyricsStyle.PRISM else lyricsStyle,
        lyricsBackground = if (lyricsBackground == LyricsBackground.BLUR || lyricsBackground == LyricsBackground.CANVAS) LyricsBackground.PLAYER else lyricsBackground,
        immersiveBackground = if (immersiveBackground == LyricsBackground.CANVAS) LyricsBackground.PLAYER else immersiveBackground,
        homeHeader = if (homeHeader == HomeHeader.PICTURE || homeHeader == HomeHeader.NOW_PLAYING) HomeHeader.GREETING else homeHeader,
    )

    /** The lyric sources Prism asks, in the listener's order: the ones turned off are left out (#51). */
    val lyricsSources: List<LyricsSource> get() = lyricsOrder.filter { it !in lyricsOff }.ifEmpty { lyricsOrder }
}

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val cookie = stringPreferencesKey("cookie")
        val visitorData = stringPreferencesKey("visitor_data")
        val dataSyncId = stringPreferencesKey("data_sync_id")
        val authUser = intPreferencesKey("auth_user")
        val discordPresence = booleanPreferencesKey("discord_presence")
        val accountName = stringPreferencesKey("account_name")
        val accountEmail = stringPreferencesKey("account_email")
        val accountAvatar = stringPreferencesKey("account_avatar")
        val themeMode = stringPreferencesKey("theme_mode")
        val pureBlack = booleanPreferencesKey("pure_black")
        val colorSource = stringPreferencesKey("color_source")
        val accent = intPreferencesKey("accent")
        val liquidGlass = booleanPreferencesKey("liquid_glass")
        val glassKind = stringPreferencesKey("glass_kind")
        val lightStyle = stringPreferencesKey("light_style")
        val accentExact = booleanPreferencesKey("accent_exact")
        val accentLight = intPreferencesKey("accent_light")
        val spectrumPreset = stringPreferencesKey("spectrum_preset")
        val spectrumStops = stringPreferencesKey("spectrum_stops")
        val spectrumBands = booleanPreferencesKey("spectrum_bands")
        val surfaceTint = stringPreferencesKey("surface_tint")
        val surfaceTintStrength = floatPreferencesKey("surface_tint_strength")
        val surfaceColor = intPreferencesKey("surface_color")
        val contrast = stringPreferencesKey("contrast_level")
        val artworkSwatch = stringPreferencesKey("artwork_swatch")
        val artworkVivid = booleanPreferencesKey("artwork_vivid")
        val artworkChange = stringPreferencesKey("artwork_change")
        // A new key with the new default (your accent): "page_colors" was only ever in unreleased 1.7.2 test builds,
        // where it defaulted to the picture's color, so those settings are left behind on purpose.
        val pageColors = booleanPreferencesKey("page_picture_accent")

        /** Everything Appearance → Color's "Reset colors" puts back. */
        val colors: List<Preferences.Key<*>> by lazy {
            listOf(
                colorSource, accent, lightStyle, accentExact, accentLight, spectrumPreset, spectrumStops, spectrumBands,
                surfaceTint, surfaceTintStrength, surfaceColor, contrast, artworkSwatch, artworkVivid, artworkChange, pageColors,
            )
        }
        val frostedGlass = booleanPreferencesKey("frosted_glass")
        val haptics = booleanPreferencesKey("haptics")
        val fullBleedArtwork = booleanPreferencesKey("full_bleed_artwork")
        val videoFullBleed = booleanPreferencesKey("video_full_bleed")
        val immersivePlayer = booleanPreferencesKey("immersive_player")
        val immersiveArt = stringPreferencesKey("immersive_art")
        val immersiveLyrics = stringPreferencesKey("immersive_lyrics")
        val immersiveControls = booleanPreferencesKey("immersive_controls")
        val playerShuffleRepeat = booleanPreferencesKey("player_shuffle_repeat")
        val preload = stringPreferencesKey("preload")
        val preloadCellular = booleanPreferencesKey("preload_cellular")
        val canvasEnabled = booleanPreferencesKey("canvas_enabled")
        val canvasOnCellular = booleanPreferencesKey("canvas_on_cellular")
        val hideVolumeBar = booleanPreferencesKey("hide_volume_bar")
        val showPlayingFrom = booleanPreferencesKey("show_playing_from")
        val spatial = booleanPreferencesKey("soft_spatial")
        val spatialAmount = floatPreferencesKey("soft_spatial_amount")
        val lyricsBlur = booleanPreferencesKey("lyrics_blur")
        val lyricsProgressLine = booleanPreferencesKey("lyrics_progress_line")
        val backdrops = stringPreferencesKey("backdrops")
        val backdropDim = floatPreferencesKey("backdrop_dim")
        val backdropMotion = booleanPreferencesKey("backdrop_motion")
        val lyricsOnPlayer = booleanPreferencesKey("lyrics_on_player")
        val lyricsOrder = stringPreferencesKey("lyrics_order")
        val lyricsOff = stringPreferencesKey("lyrics_off")
        val preferSynced = booleanPreferencesKey("prefer_synced")
        val skipCensored = booleanPreferencesKey("skip_censored")
        val lyricsTranslate = booleanPreferencesKey("lyrics_translate")
        val lyricsTranslateTo = stringPreferencesKey("lyrics_translate_to")
        val romanize = stringPreferencesKey("romanize_lyrics")
        val romanizeAll = booleanPreferencesKey("romanize_all")
        val singOnCellular = booleanPreferencesKey("sing_cellular")
        val singAlwaysReady = booleanPreferencesKey("sing_always_ready")
        val singCacheMb = intPreferencesKey("sing_cache_mb")
        val djFlow = stringPreferencesKey("dj_flow")
        val releaseNotifications = booleanPreferencesKey("release_notifications")
        val rewindNotification = booleanPreferencesKey("rewind_notification")
        val webRemote = booleanPreferencesKey("web_remote")
        val handoffAvailable = booleanPreferencesKey("handoff_available")
        val lyricsScale = floatPreferencesKey("lyrics_scale")
        val carLyrics = booleanPreferencesKey("car_lyrics")
        val carLyricsStyle = stringPreferencesKey("car_lyrics_style")
        val carLyricsOnPhone = booleanPreferencesKey("car_lyrics_on_phone")
        val carLyricsKeepTitle = booleanPreferencesKey("car_lyrics_keep_title")
        val autoGrid = booleanPreferencesKey("auto_grid")
        val showNerdStats = booleanPreferencesKey("nerd_stats")
        val showQuality = booleanPreferencesKey("show_quality")
        val squareVideoCovers = booleanPreferencesKey("square_video_covers")
        val lyricsOnMobileData = booleanPreferencesKey("lyrics_on_mobile_data")
        val bestPartButton = booleanPreferencesKey("best_part_button")
        val showVideoSwitch = booleanPreferencesKey("show_video_switch")
        val holdSeekSeconds = intPreferencesKey("hold_seek_seconds")
        val canvasWithDownloads = booleanPreferencesKey("canvas_with_downloads")
        val canvasWithDownloadsCellular = booleanPreferencesKey("canvas_with_downloads_cellular")
        val audioQuality = stringPreferencesKey("audio_quality")
        val cellularQuality = stringPreferencesKey("cellular_quality")
        val premiumStream = booleanPreferencesKey("premium_stream")
        val autoplay = booleanPreferencesKey("autoplay")
        val autoplaySource = stringPreferencesKey("autoplay_source")
        val replayCard = stringPreferencesKey("replay_card")
        val radioSongsOnly = booleanPreferencesKey("radio_songs_only")
        val songsEverywhere = booleanPreferencesKey("songs_everywhere")
        val showTrackLength = booleanPreferencesKey("show_track_length")
        val audioDiagnostics = booleanPreferencesKey("audio_diagnostics")
        val searchKeyboard = booleanPreferencesKey("search_keyboard")
        val doubleTapSeekSeconds = intPreferencesKey("double_tap_seek_seconds")
        val stopOnSwipeAway = booleanPreferencesKey("stop_on_swipe_away")
        val dataSaver = booleanPreferencesKey("data_saver")
        val videoContinues = booleanPreferencesKey("video_continues")
        val lossless = booleanPreferencesKey("lossless")
        val losslessLocal = booleanPreferencesKey("lossless_local")
        val normalize = booleanPreferencesKey("normalize")
        val skipSilence = booleanPreferencesKey("skip_silence")
        val mix = booleanPreferencesKey("mix")
        val mixSeconds = intPreferencesKey("mix_seconds")
        val mixAuto = booleanPreferencesKey("mix_auto")
        val mixBeatMatch = booleanPreferencesKey("mix_beat_match")
        val dropLeadSeconds = intPreferencesKey("drop_lead_seconds")
        val mixTempoOnCellular = booleanPreferencesKey("mix_tempo_cellular")
        val contentCountry = stringPreferencesKey("content_country")
        val videoMaxHeight = intPreferencesKey("video_max_height")
        val cacheSizeMb = intPreferencesKey("cache_size_mb")
        val downloadWifiOnly = booleanPreferencesKey("download_wifi_only")
        val downloadLikes = booleanPreferencesKey("download_likes")
        val smartDownloads = booleanPreferencesKey("smart_downloads")
        val smartDownloadGb = floatPreferencesKey("smart_download_gb")
        val offlineGb = floatPreferencesKey("offline_gb")
        val smartDownloadsCellular = booleanPreferencesKey("smart_downloads_cellular")
        val eq = stringPreferencesKey("eq")
        val homeLayout = stringPreferencesKey("home_layout")
        val autoAddSections = booleanPreferencesKey("auto_add_sections")
        val homeHideKinds = stringPreferencesKey("home_hide_kinds")
        val searchSections = stringPreferencesKey("search_sections")
        val homeHeader = stringPreferencesKey("home_header")
        val homeHeaderHeight = stringPreferencesKey("home_header_height")
        val homeHeaderPicture = stringPreferencesKey("home_header_picture")
        val replayHours = booleanPreferencesKey("replay_hours")
        val greetingText = stringPreferencesKey("greeting_text")
        val greetingShowName = booleanPreferencesKey("greeting_show_name")
        val homeShortcuts = stringPreferencesKey("home_shortcuts")
        val homeRecentTiles = intPreferencesKey("home_recent_tiles")
        val homePlaylists = stringPreferencesKey("home_playlists")
        val homeCustomizeButton = booleanPreferencesKey("home_customize_button")
        val homeContinue = booleanPreferencesKey("home_continue")
        val quickPicksRadio = booleanPreferencesKey("quick_picks_radio")
        val speedDialYouTubeOnly = booleanPreferencesKey("speed_dial_youtube_only")
        val homeCustomizeTopButton = booleanPreferencesKey("home_customize_top_button")
        val homePodcastsButton = booleanPreferencesKey("home_podcasts_button")
        val fontChoice = stringPreferencesKey("font_choice")
        val appFontName = stringPreferencesKey("app_font_name")
        val fontStamp = longPreferencesKey("font_stamp")
        val lyricsFont = stringPreferencesKey("lyrics_font")
        val lyricsFontName = stringPreferencesKey("lyrics_font_name")
        val textScale = floatPreferencesKey("text_scale")
        val corners = stringPreferencesKey("corners")
        val uiDensity = stringPreferencesKey("ui_density")
        val titleSize = stringPreferencesKey("title_size")
        val cardSize = stringPreferencesKey("card_size")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val navStyle = stringPreferencesKey("nav_style")
        val navLabels = stringPreferencesKey("nav_labels")
        val navHideOnScroll = booleanPreferencesKey("nav_hide_on_scroll")
        val navTabs = stringPreferencesKey("nav_tabs")
        val tabNames = stringPreferencesKey("tab_names")
        val navBarSize = stringPreferencesKey("nav_bar_size")
        val navBarFill = stringPreferencesKey("nav_bar_fill")
        val swipeTabs = booleanPreferencesKey("swipe_tabs")
        val miniPlayerJoined = booleanPreferencesKey("mini_player_joined")
        val localSections = stringPreferencesKey("local_sections")
        val localSort = stringPreferencesKey("local_sort")
        val startTab = stringPreferencesKey("start_tab")
        val miniPlayerStyle = stringPreferencesKey("mini_player_style")
        val miniSkipButtons = booleanPreferencesKey("mini_skip_buttons")
        val miniPrevButton = booleanPreferencesKey("mini_prev_button")
        val miniProgress = stringPreferencesKey("mini_progress")
        val playerBackground = stringPreferencesKey("player_background")
        val playerBackdropShow = floatPreferencesKey("player_backdrop_show")
        val playerArtCorners = intPreferencesKey("player_art_corners")
        val transportStyle = stringPreferencesKey("transport_style")
        val coverSize = stringPreferencesKey("cover_size")
        val scrubberStyle = stringPreferencesKey("scrubber_style")
        val artShrinkOnPause = booleanPreferencesKey("art_shrink_on_pause")
        val showOutputDevice = booleanPreferencesKey("show_output_device")
        val lyricsAlign = stringPreferencesKey("lyrics_align")
        val playerLayout = stringPreferencesKey("player_layout")
        val vinylTonearm = booleanPreferencesKey("vinyl_tonearm")
        val playerDeck = stringPreferencesKey("player_deck")
        val playerButtons = stringPreferencesKey("player_buttons")
        val sessionButtons = stringPreferencesKey("session_buttons")
        val scrubberColor = stringPreferencesKey("scrubber_color")
        val playerTitleAlign = stringPreferencesKey("player_title_align")
        val playerTitleSize = stringPreferencesKey("player_title_size")
        val playerTitleScroll = booleanPreferencesKey("player_title_scroll")
        val playerTimes = stringPreferencesKey("player_times")
        val meshSpeed = floatPreferencesKey("mesh_speed")
        val meshIntensity = floatPreferencesKey("mesh_intensity")
        val playerBlur = floatPreferencesKey("player_blur")
        val canvasFillsBackground = booleanPreferencesKey("canvas_fills_background")
        val scrubberThickness = floatPreferencesKey("scrubber_thickness")
        val scrubberThumb = stringPreferencesKey("scrubber_thumb")
        val visualizer = stringPreferencesKey("visualizer")
        val visualizerColor = stringPreferencesKey("visualizer_color")
        val lyricsStyle = stringPreferencesKey("lyrics_style")
        val lyricsBlurAmount = floatPreferencesKey("lyrics_blur_amount")
        val lyricsSpacing = floatPreferencesKey("lyrics_spacing")
        val lyricsOthersAlpha = floatPreferencesKey("lyrics_others_alpha")
        val lyricsSungColor = stringPreferencesKey("lyrics_sung_color")
        val lyricsSungCustom = intPreferencesKey("lyrics_sung_custom")
        val lyricsOtherColor = stringPreferencesKey("lyrics_other_color")
        val lyricsOtherCustom = intPreferencesKey("lyrics_other_custom")
        val lyricsWeight = stringPreferencesKey("lyrics_weight")
        val lyricsBackingSize = floatPreferencesKey("lyrics_backing_size")
        val lyricsShadow = booleanPreferencesKey("lyrics_shadow")
        val lyricsUppercase = booleanPreferencesKey("lyrics_uppercase")
        val lyricsAhead = stringPreferencesKey("lyrics_ahead")
        val lyricsPosition = stringPreferencesKey("lyrics_position")
        val lyricsExtraSize = floatPreferencesKey("lyrics_extra_size")
        val lyricsExtraColor = stringPreferencesKey("lyrics_extra_color")
        val lyricsExtraItalic = booleanPreferencesKey("lyrics_extra_italic")
        val lyricsExtraPlace = stringPreferencesKey("lyrics_extra_place")
        val lyricsBackground = stringPreferencesKey("lyrics_background")
        val immersiveBackground = stringPreferencesKey("immersive_background")
        val lyricsBgColor = intPreferencesKey("lyrics_bg_color")
        val lyricsBgColor2 = intPreferencesKey("lyrics_bg_color2")
        val lyricsEverywhere = booleanPreferencesKey("lyrics_everywhere")
        val lyricsStripAlign = stringPreferencesKey("lyrics_strip_align")
        val lyricsStripTwoLines = booleanPreferencesKey("lyrics_strip_two_lines")
        val mergeLikedVideos = booleanPreferencesKey("merge_liked_videos")
        val hideStatusBar = booleanPreferencesKey("hide_status_bar")
        val volumeControl = stringPreferencesKey("volume_control")
        val appVolume = floatPreferencesKey("app_volume")

        /** Lyrics, your way (1.7.5): in Reset look, a Look, and the Lyrics page's own reset. */
        val lyricsLook: List<Pair<Preferences.Key<*>, Char>> by lazy {
            listOf(
                lyricsStyle to 's', lyricsBlurAmount to 'f', lyricsSpacing to 'f', lyricsOthersAlpha to 'f', lyricsSungColor to 's',
                lyricsSungCustom to 'i', lyricsOtherColor to 's', lyricsOtherCustom to 'i', lyricsWeight to 's', lyricsBackingSize to 'f',
                lyricsShadow to 'b', lyricsUppercase to 'b', lyricsAhead to 's', lyricsPosition to 's', lyricsExtraSize to 'f',
                lyricsExtraColor to 's', lyricsExtraItalic to 'b', lyricsExtraPlace to 's', lyricsBackground to 's', immersiveBackground to 's',
                lyricsBgColor to 'i', lyricsBgColor2 to 'i', lyricsEverywhere to 'b', lyricsStripAlign to 's', lyricsStripTwoLines to 'b',
            )
        }
        val coverSwipeLeft = stringPreferencesKey("cover_swipe_left")
        val coverSwipeRight = stringPreferencesKey("cover_swipe_right")
        val coverSwipeUp = stringPreferencesKey("cover_swipe_up")
        val coverDoubleTap = stringPreferencesKey("cover_double_tap")
        val coverLongPress = stringPreferencesKey("cover_long_press")
        val miniSwipeLeft = stringPreferencesKey("mini_swipe_left")
        val miniSwipeRight = stringPreferencesKey("mini_swipe_right")
        val miniSwipeDown = stringPreferencesKey("mini_swipe_down")
        val libraryView = stringPreferencesKey("library_view")
        val libraryViews = stringPreferencesKey("library_views")
        val libraryColumns = stringPreferencesKey("library_columns")
        val librarySubtitles = booleanPreferencesKey("library_subtitles")
        val libraryStartTab = stringPreferencesKey("library_start_tab")
        val libraryTiles = stringPreferencesKey("library_tiles")
        val libraryPlaylistsFirst = booleanPreferencesKey("library_playlists_first")
        val playlistHeroCover = booleanPreferencesKey("playlist_hero_cover")
        val frostedPages = booleanPreferencesKey("frosted_pages")
        val replayThemeColors = booleanPreferencesKey("replay_theme_colors")
        val performanceMode = booleanPreferencesKey("performance_mode")
        /** The Home card offering Performance mode on a slower phone was answered (either way). */
        val performanceOffered = booleanPreferencesKey("performance_offered")

        /** Everything the "Reset look" button puts back. */
        val look: List<Preferences.Key<*>> by lazy {
            listOf(
                fontChoice, textScale, corners, uiDensity, titleSize, cardSize, reduceMotion, navStyle, navLabels, navHideOnScroll,
                navTabs, startTab, miniPlayerStyle, miniSkipButtons, miniPrevButton, miniProgress, playerBackground, playerBackdropShow, playerArtCorners, transportStyle,
                coverSize, scrubberStyle, artShrinkOnPause, showOutputDevice, lyricsAlign, libraryView, playlistHeroCover, frostedPages, replayThemeColors,
                immersiveArt, immersiveLyrics, immersiveControls, playerShuffleRepeat, lightStyle,
                playerLayout, vinylTonearm, playerDeck, playerButtons, scrubberColor, playerTitleAlign, playerTitleSize, playerTitleScroll,
                playerTimes, meshSpeed, meshIntensity, playerBlur, canvasFillsBackground,
                scrubberThickness, scrubberThumb, visualizer, visualizerColor,
                lyricsBlur, lyricsScale, lyricsProgressLine, hideStatusBar,
                tabNames, navBarSize, navBarFill, miniPlayerJoined, libraryViews, libraryColumns, librarySubtitles,
                homeHeader, homeHeaderHeight,
            ) + lyricsLook.map { it.first }
        }

        /**
         * What a Look holds (1.7.3): every key above and the colors, plus theme, glass, backgrounds and the lyrics' look,
         * each with the kind of value it stores (b, i, f, s), so a Look shared by someone else can only ever set these, and
         * only with the right kind of value. `LookTest` fails when a setting is in neither this list nor [notLook].
         */
        val lookSpec: List<Pair<Preferences.Key<*>, Char>> by lazy {
            listOf(
                themeMode to 's', pureBlack to 'b',
                colorSource to 's', accent to 'i', lightStyle to 's', accentExact to 'b', accentLight to 'i', spectrumPreset to 's',
                spectrumStops to 's', spectrumBands to 'b', surfaceTint to 's', surfaceTintStrength to 'f', surfaceColor to 'i', contrast to 's',
                artworkSwatch to 's', artworkVivid to 'b', artworkChange to 's', pageColors to 'b',
                liquidGlass to 'b', glassKind to 's', frostedGlass to 'b', frostedPages to 'b', backdrops to 's', backdropDim to 'f',
                backdropMotion to 'b', reduceMotion to 'b',
                fontChoice to 's', appFontName to 's', lyricsFont to 's', textScale to 'f', corners to 's', uiDensity to 's', titleSize to 's', cardSize to 's',
                navStyle to 's', navLabels to 's', navHideOnScroll to 'b', navTabs to 's', startTab to 's',
                tabNames to 's', navBarSize to 's', navBarFill to 's', miniPlayerJoined to 'b',
                miniPlayerStyle to 's', miniSkipButtons to 'b', miniPrevButton to 'b', miniProgress to 's',
                playerBackground to 's', playerBackdropShow to 'f', playerArtCorners to 'i', transportStyle to 's', coverSize to 's',
                scrubberStyle to 's', artShrinkOnPause to 'b', showOutputDevice to 'b', fullBleedArtwork to 'b', videoFullBleed to 'b', hideVolumeBar to 'b',
                showPlayingFrom to 'b', playerShuffleRepeat to 'b', immersiveArt to 's', immersiveLyrics to 's', immersiveControls to 'b',
                playerLayout to 's', vinylTonearm to 'b', playerDeck to 's', playerButtons to 's', scrubberColor to 's', playerTitleAlign to 's',
                playerTitleSize to 's', playerTitleScroll to 'b', playerTimes to 's', meshSpeed to 'f', meshIntensity to 'f', playerBlur to 'f',
                canvasFillsBackground to 'b', scrubberThickness to 'f', scrubberThumb to 's', visualizer to 's', visualizerColor to 's',
                lyricsAlign to 's', lyricsBlur to 'b', lyricsProgressLine to 'b', lyricsScale to 'f',
                libraryView to 's', libraryViews to 's', libraryColumns to 's', librarySubtitles to 'b', playlistHeroCover to 'b',
                homeHeader to 's', homeHeaderHeight to 's', replayThemeColors to 'b', replayCard to 's', hideStatusBar to 'b',
            ) + lyricsLook
        }

        /** Settings that aren't part of a Look: what you listen to and how, your account, Home's contents. */
        val notLook: Set<String> by lazy {
            setOf(
                "performance_mode", "performance_offered",
                "merge_liked_videos", "volume_control", "app_volume", "speed_dial_youtube_only", "swipe_tabs",
                "onboarded", "cookie", "visitor_data", "data_sync_id", "auth_user", "discord_presence", "account_name", "account_email",
                "account_avatar", "haptics", "immersive_player", "preload", "preload_cellular", "canvas_enabled", "canvas_on_cellular",
                "soft_spatial", "soft_spatial_amount", "lyrics_on_player", "lyrics_order", "lyrics_off", "prefer_synced", "skip_censored",
                "lyrics_translate", "lyrics_translate_to", "romanize_lyrics", "romanize_all", "sing_cellular", "sing_always_ready",
                "sing_cache_mb", "dj_flow", "release_notifications", "rewind_notification", "web_remote", "handoff_available",
                "car_lyrics", "car_lyrics_style", "car_lyrics_on_phone", "car_lyrics_keep_title", "auto_grid", "nerd_stats",
                "show_quality", "square_video_covers", "lyrics_on_mobile_data", "best_part_button", "show_video_switch",
                "hold_seek_seconds", "canvas_with_downloads", "canvas_with_downloads_cellular", "audio_quality", "cellular_quality",
                "premium_stream", "autoplay", "autoplay_source", "radio_songs_only", "songs_everywhere", "show_track_length",
                "audio_diagnostics", "search_keyboard", "double_tap_seek_seconds", "stop_on_swipe_away", "data_saver",
                "video_continues", "lossless", "lossless_local", "normalize", "skip_silence", "mix", "mix_seconds", "mix_auto",
                "mix_beat_match", "mix_tempo_cellular", "content_country", "video_max_height", "cache_size_mb", "download_wifi_only",
                "download_likes", "smart_downloads", "smart_download_gb", "offline_gb", "smart_downloads_cellular", "eq",
                "home_layout", "auto_add_sections", "home_hide_kinds", "home_header_picture", "search_sections", "replay_hours", "greeting_text", "greeting_show_name", "home_shortcuts",
                "home_recent_tiles", "home_playlists", "home_customize_button", "home_continue", "quick_picks_radio",
                "home_customize_top_button", "home_podcasts_button", "font_stamp", "lyrics_font_name", "local_sections", "local_sort",
                "library_start_tab", "library_tiles", "library_playlists_first",
                // 1.7.4: what buttons and gestures do is how you use Prism, not how it looks.
                "session_buttons", "cover_swipe_left", "cover_swipe_right", "cover_swipe_up", "cover_double_tap", "cover_long_press",
                "mini_swipe_left", "mini_swipe_right", "mini_swipe_down",
            )
        }
    }

    /** Every setting's stored name (for `LookTest`: each is either in a Look or listed as not). */
    val allKeyNames: Set<String> by lazy {
        K::class.java.declaredFields.filter { Preferences.Key::class.java.isAssignableFrom(it.type) }
            .mapNotNull { f -> f.isAccessible = true; (f.get(K) as? Preferences.Key<*>)?.name }.toSet()
    }

    /** Settings listed as not part of a Look ([K.notLook]). */
    val notLookNames: Set<String> get() = K.notLook

    /** What Reset look and Reset colors put back (every one of them is in a Look too). */
    val resetNames: Set<String> get() = (K.look + K.colors).map { it.name }.toSet()

    /** What a Look holds, by stored name, with each one's kind of value. */
    val lookKinds: Map<String, Char> by lazy { K.lookSpec.associate { (k, t) -> k.name to t } }

    private val lookKeysByName: Map<String, Preferences.Key<*>> by lazy { K.lookSpec.associate { (k, _) -> k.name to k } }

    /** The look settings that are set in [p], as tagged text ("b:1", "f:0.35", "s:SHARP"…); missing ones are at their defaults. */
    private fun lookOf(p: Preferences): Map<String, String> =
        K.lookSpec.mapNotNull { (k, _) -> p[k]?.let { v -> LookValue.tag(v)?.let { k.name to it } } }.toMap()

    /** Puts a Look's [values] in place of every look setting in [p] (those it doesn't set go back to their defaults). */
    @Suppress("UNCHECKED_CAST")
    private fun putLook(p: MutablePreferences, values: Map<String, String>) {
        K.lookSpec.forEach { (k, _) -> p.remove(k as Preferences.Key<Any>) }
        values.forEach { (name, tagged) ->
            val key = lookKeysByName[name] ?: return@forEach
            val v = LookValue.untag(tagged, lookKinds[name] ?: return@forEach) ?: return@forEach
            p[key as Preferences.Key<Any>] = v
        }
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, def: E): E =
        this[key]?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: def

    /** A comma-separated list of enum names (unknown ones skipped); [def] when unset or nothing in it is known. */
    private inline fun <reified E : Enum<E>> Preferences.enumList(key: Preferences.Key<String>, def: List<E>): List<E> =
        this[key]?.split(',')?.mapNotNull { n -> enumValues<E>().firstOrNull { it.name == n } }?.distinct()?.ifEmpty { null } ?: def

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            onboarded = p[K.onboarded] ?: d.onboarded,
            cookie = p[K.cookie] ?: "",
            visitorData = p[K.visitorData] ?: "",
            dataSyncId = p[K.dataSyncId] ?: "",
            authUser = p[K.authUser] ?: 0,
            discordPresence = p[K.discordPresence] ?: d.discordPresence,
            accountName = p[K.accountName] ?: "",
            accountEmail = p[K.accountEmail] ?: "",
            accountAvatar = p[K.accountAvatar] ?: "",
            themeMode = p.enum(K.themeMode, d.themeMode),
            pureBlack = p[K.pureBlack] ?: d.pureBlack,
            colorSource = p.enum(K.colorSource, d.colorSource),
            accent = p[K.accent] ?: d.accent,
            liquidGlass = p[K.liquidGlass] ?: d.liquidGlass,
            glassKind = p.enum(K.glassKind, d.glassKind),
            lightStyle = p.enum(K.lightStyle, d.lightStyle),
            accentExact = p[K.accentExact] ?: d.accentExact,
            accentLight = p[K.accentLight] ?: d.accentLight,
            spectrumPreset = p[K.spectrumPreset] ?: d.spectrumPreset,
            spectrumStops = p[K.spectrumStops]?.split(',')?.mapNotNull { it.trim().toLongOrNull(16)?.toInt() } ?: d.spectrumStops,
            spectrumBands = p[K.spectrumBands] ?: d.spectrumBands,
            surfaceTint = p.enum(K.surfaceTint, d.surfaceTint),
            surfaceTintStrength = p[K.surfaceTintStrength] ?: d.surfaceTintStrength,
            surfaceColor = p[K.surfaceColor] ?: d.surfaceColor,
            contrast = p.enum(K.contrast, d.contrast),
            artworkSwatch = p.enum(K.artworkSwatch, d.artworkSwatch),
            artworkVivid = p[K.artworkVivid] ?: d.artworkVivid,
            artworkChange = p.enum(K.artworkChange, d.artworkChange),
            pageColors = p[K.pageColors] ?: d.pageColors,
            frostedGlass = p[K.frostedGlass] ?: d.frostedGlass,
            haptics = p[K.haptics] ?: d.haptics,
            fullBleedArtwork = p[K.fullBleedArtwork] ?: d.fullBleedArtwork,
            videoFullBleed = p[K.videoFullBleed] ?: d.videoFullBleed,
            immersivePlayer = p[K.immersivePlayer] ?: d.immersivePlayer,
            immersiveArt = p.enum(K.immersiveArt, d.immersiveArt),
            immersiveLyrics = p.enum(K.immersiveLyrics, d.immersiveLyrics),
            immersiveControls = p[K.immersiveControls] ?: d.immersiveControls,
            playerShuffleRepeat = p[K.playerShuffleRepeat] ?: d.playerShuffleRepeat,
            preload = p.enum(K.preload, d.preload),
            preloadCellular = p[K.preloadCellular] ?: d.preloadCellular,
            canvasEnabled = p[K.canvasEnabled] ?: d.canvasEnabled,
            canvasOnCellular = p[K.canvasOnCellular] ?: d.canvasOnCellular,
            hideVolumeBar = p[K.hideVolumeBar] ?: d.hideVolumeBar,
            showPlayingFrom = p[K.showPlayingFrom] ?: d.showPlayingFrom,
            spatial = p[K.spatial] ?: d.spatial,
            spatialAmount = p[K.spatialAmount] ?: d.spatialAmount,
            lyricsBlur = p[K.lyricsBlur] ?: d.lyricsBlur,
            lyricsProgressLine = p[K.lyricsProgressLine] ?: d.lyricsProgressLine,
            backdrops = p[K.backdrops]?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: d.backdrops,
            backdropDim = p[K.backdropDim] ?: d.backdropDim,
            backdropMotion = p[K.backdropMotion] ?: d.backdropMotion,
            lyricsOnPlayer = p[K.lyricsOnPlayer] ?: d.lyricsOnPlayer,
            lyricsOrder = p[K.lyricsOrder]?.split(",")?.mapNotNull { n ->
                LyricsSource.entries.firstOrNull { it.name == n }
            }?.let { list ->
                // Sources added in an update slot in at their default position.
                val merged = list.toMutableList()
                LyricsSource.entries.forEachIndexed { i, src -> if (src !in merged) merged.add(i.coerceAtMost(merged.size), src) }
                merged
            } ?: d.lyricsOrder,
            lyricsOff = p[K.lyricsOff]?.split(",")?.mapNotNull { n -> LyricsSource.entries.firstOrNull { it.name == n } }?.toSet() ?: d.lyricsOff,
            preferSynced = p[K.preferSynced] ?: d.preferSynced,
            skipCensored = p[K.skipCensored] ?: d.skipCensored,
            lyricsTranslate = p[K.lyricsTranslate] ?: d.lyricsTranslate,
            lyricsTranslateTo = p[K.lyricsTranslateTo] ?: d.lyricsTranslateTo,
            romanize = p.enum(K.romanize, d.romanize),
            romanizeAll = p[K.romanizeAll] ?: d.romanizeAll,
            singOnCellular = p[K.singOnCellular] ?: d.singOnCellular,
            singAlwaysReady = p[K.singAlwaysReady] ?: d.singAlwaysReady,
            singCacheMb = p[K.singCacheMb] ?: d.singCacheMb,
            djFlow = p.enum(K.djFlow, d.djFlow),
            releaseNotifications = p[K.releaseNotifications] ?: d.releaseNotifications,
            rewindNotification = p[K.rewindNotification] ?: d.rewindNotification,
            webRemote = p[K.webRemote] ?: d.webRemote,
            handoffAvailable = p[K.handoffAvailable] ?: d.handoffAvailable,
            lyricsScale = p[K.lyricsScale] ?: d.lyricsScale,
            carLyrics = p[K.carLyrics] ?: d.carLyrics,
            carLyricsStyle = p.enum(K.carLyricsStyle, d.carLyricsStyle),
            carLyricsOnPhone = p[K.carLyricsOnPhone] ?: d.carLyricsOnPhone,
            carLyricsKeepTitle = p[K.carLyricsKeepTitle] ?: d.carLyricsKeepTitle,
            autoGrid = p[K.autoGrid] ?: d.autoGrid,
            showNerdStats = p[K.showNerdStats] ?: d.showNerdStats,
            showQuality = p[K.showQuality] ?: d.showQuality,
            squareVideoCovers = p[K.squareVideoCovers] ?: d.squareVideoCovers,
            lyricsOnMobileData = p[K.lyricsOnMobileData] ?: d.lyricsOnMobileData,
            bestPartButton = p[K.bestPartButton] ?: d.bestPartButton,
            showVideoSwitch = p[K.showVideoSwitch] ?: d.showVideoSwitch,
            holdSeekSeconds = p[K.holdSeekSeconds] ?: d.holdSeekSeconds,
            canvasWithDownloads = p[K.canvasWithDownloads] ?: d.canvasWithDownloads,
            canvasWithDownloadsCellular = p[K.canvasWithDownloadsCellular] ?: d.canvasWithDownloadsCellular,
            audioQuality = p.enum(K.audioQuality, d.audioQuality),
            cellularQuality = p.enum(K.cellularQuality, d.cellularQuality),
            premiumStream = p[K.premiumStream] ?: d.premiumStream,
            autoplay = p[K.autoplay] ?: d.autoplay,
            autoplaySource = p[K.autoplaySource]?.let { n -> AutoplaySource.entries.firstOrNull { it.name == n } } ?: d.autoplaySource,
            replayCard = p[K.replayCard] ?: d.replayCard,
            radioSongsOnly = p[K.radioSongsOnly] ?: d.radioSongsOnly,
            songsEverywhere = p[K.songsEverywhere] ?: d.songsEverywhere,
            showTrackLength = p[K.showTrackLength] ?: d.showTrackLength,
            audioDiagnostics = p[K.audioDiagnostics] ?: d.audioDiagnostics,
            searchKeyboard = p[K.searchKeyboard] ?: d.searchKeyboard,
            doubleTapSeekSeconds = p[K.doubleTapSeekSeconds] ?: d.doubleTapSeekSeconds,
            stopOnSwipeAway = p[K.stopOnSwipeAway] ?: d.stopOnSwipeAway,
            dataSaver = p[K.dataSaver] ?: d.dataSaver,
            videoContinues = p[K.videoContinues] ?: d.videoContinues,
            lossless = p[K.lossless] ?: d.lossless,
            losslessLocal = p[K.losslessLocal] ?: d.losslessLocal,
            normalize = p[K.normalize] ?: d.normalize,
            mix = p[K.mix] ?: d.mix,
            mixSeconds = (p[K.mixSeconds] ?: d.mixSeconds).coerceIn(1, 12),
            mixAuto = p[K.mixAuto] ?: d.mixAuto,
            mixBeatMatch = p[K.mixBeatMatch] ?: d.mixBeatMatch,
            dropLeadSeconds = (p[K.dropLeadSeconds] ?: d.dropLeadSeconds).coerceIn(5, 30),
            mixTempoOnCellular = p[K.mixTempoOnCellular] ?: d.mixTempoOnCellular,
            contentCountry = p[K.contentCountry] ?: d.contentCountry,
            skipSilence = p[K.skipSilence] ?: d.skipSilence,
            videoMaxHeight = p[K.videoMaxHeight] ?: d.videoMaxHeight,
            cacheSizeMb = p[K.cacheSizeMb] ?: d.cacheSizeMb,
            downloadWifiOnly = p[K.downloadWifiOnly] ?: d.downloadWifiOnly,
            downloadLikes = p[K.downloadLikes] ?: d.downloadLikes,
            smartDownloads = p[K.smartDownloads] ?: d.smartDownloads,
            smartDownloadGb = p[K.smartDownloadGb] ?: d.smartDownloadGb,
            offlineGb = p[K.offlineGb] ?: run {
                // The old smart downloads space and song cache add up to the new one (an unlimited cache counts as 20 GB).
                val cacheMb = p[K.cacheSizeMb] ?: d.cacheSizeMb
                (p[K.smartDownloadGb] ?: d.smartDownloadGb) + if (cacheMb <= 0) 20f else cacheMb / 1024f
            },
            smartDownloadsCellular = p[K.smartDownloadsCellular] ?: d.smartDownloadsCellular,
            eq = p[K.eq]?.let { runCatching { json.decodeFromString<EqSettings>(it) }.getOrNull() } ?: d.eq,
            homeLayout = p[K.homeLayout]?.let {
                runCatching { json.decodeFromString<List<HomeSectionConfig>>(it) }.getOrNull()
                    // The tiles section was once called "Greeting & shortcuts"; the greeting is the top bar's.
                    ?.map { s -> if (s.key == HomeSections.GREETING) s.copy(title = HomeSections.TILES_TITLE) else s }
                    // Release Radar's section was "New releases", the same name as YouTube's own shelf beside it.
                    ?.map { s -> if (s.key == HomeSections.NEW_RELEASES && s.title == "New releases") s.copy(title = HomeSections.NEW_RELEASES_TITLE) else s }
            } ?: d.homeLayout,
            autoAddSections = p[K.autoAddSections] ?: d.autoAddSections,
            homeHideKinds = p[K.homeHideKinds]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: d.homeHideKinds,
            searchSections = p[K.searchSections]?.let { SearchSection.parse(it) } ?: d.searchSections,
            homeHeader = p.enum(K.homeHeader, d.homeHeader),
            homeHeaderHeight = p.enum(K.homeHeaderHeight, d.homeHeaderHeight),
            homeHeaderPicture = p[K.homeHeaderPicture] ?: d.homeHeaderPicture,
            replayHours = p[K.replayHours] ?: d.replayHours,
            greetingText = p[K.greetingText] ?: d.greetingText,
            greetingShowName = p[K.greetingShowName] ?: d.greetingShowName,
            homeShortcuts = p[K.homeShortcuts]?.let { v ->
                v.split(",").mapNotNull { n -> HomeShortcut.entries.firstOrNull { it.name == n } }
            } ?: d.homeShortcuts,
            homeRecentTiles = p[K.homeRecentTiles] ?: d.homeRecentTiles,
            homePlaylists = p[K.homePlaylists]?.let { runCatching { json.decodeFromString<List<PinnedPlaylist>>(it) }.getOrNull() } ?: d.homePlaylists,
            homeCustomizeButton = p[K.homeCustomizeButton] ?: d.homeCustomizeButton,
            homeContinue = p[K.homeContinue] ?: d.homeContinue,
            quickPicksRadio = p[K.quickPicksRadio] ?: d.quickPicksRadio,
            speedDialYouTubeOnly = p[K.speedDialYouTubeOnly] ?: d.speedDialYouTubeOnly,
            homeCustomizeTopButton = p[K.homeCustomizeTopButton] ?: d.homeCustomizeTopButton,
            homePodcastsButton = p[K.homePodcastsButton] ?: d.homePodcastsButton,
            fontChoice = p[K.fontChoice]?.let { n -> FontChoice.entries.firstOrNull { it.name == FontChoice.upgrade(n) } } ?: d.fontChoice,
            appFontName = p[K.appFontName] ?: d.appFontName,
            fontStamp = p[K.fontStamp] ?: d.fontStamp,
            lyricsFont = p[K.lyricsFont]?.let(FontChoice::upgrade) ?: d.lyricsFont,
            lyricsFontName = p[K.lyricsFontName] ?: d.lyricsFontName,
            textScale = p[K.textScale] ?: d.textScale,
            corners = p.enum(K.corners, d.corners),
            uiDensity = p.enum(K.uiDensity, d.uiDensity),
            titleSize = p.enum(K.titleSize, d.titleSize),
            cardSize = p.enum(K.cardSize, d.cardSize),
            reduceMotion = p[K.reduceMotion] ?: d.reduceMotion,
            navStyle = p.enum(K.navStyle, d.navStyle),
            navLabels = p.enum(K.navLabels, d.navLabels),
            navHideOnScroll = p[K.navHideOnScroll] ?: d.navHideOnScroll,
            navTabs = p[K.navTabs]?.split(",")?.mapNotNull { n -> NavTab.entries.firstOrNull { it.name == n } }?.takeIf { it.isNotEmpty() } ?: d.navTabs,
            tabNames = p[K.tabNames]?.let(::parseTabNames) ?: d.tabNames,
            navBarSize = p.enum(K.navBarSize, d.navBarSize),
            navBarFill = p.enum(K.navBarFill, d.navBarFill),
            swipeTabs = p[K.swipeTabs] ?: d.swipeTabs,
            miniPlayerJoined = p[K.miniPlayerJoined] ?: d.miniPlayerJoined,
            localSections = p[K.localSections]?.split(",")?.mapNotNull { n -> LocalSection.entries.firstOrNull { it.name == n } }?.takeIf { it.isNotEmpty() } ?: d.localSections,
            localSort = p[K.localSort] ?: d.localSort,
            startTab = p.enum(K.startTab, d.startTab),
            miniPlayerStyle = p.enum(K.miniPlayerStyle, d.miniPlayerStyle),
            miniSkipButtons = p[K.miniSkipButtons] ?: d.miniSkipButtons,
            miniPrevButton = p[K.miniPrevButton] ?: d.miniPrevButton,
            miniProgress = p.enum(K.miniProgress, d.miniProgress),
            playerBackground = p.enum(K.playerBackground, d.playerBackground),
            playerBackdropShow = p[K.playerBackdropShow] ?: d.playerBackdropShow,
            playerArtCorners = p[K.playerArtCorners] ?: d.playerArtCorners,
            transportStyle = p.enum(K.transportStyle, d.transportStyle),
            coverSize = p.enum(K.coverSize, d.coverSize),
            scrubberStyle = p.enum(K.scrubberStyle, d.scrubberStyle),
            artShrinkOnPause = p[K.artShrinkOnPause] ?: d.artShrinkOnPause,
            showOutputDevice = p[K.showOutputDevice] ?: d.showOutputDevice,
            lyricsAlign = p.enum(K.lyricsAlign, d.lyricsAlign),
            playerLayout = p.enum(K.playerLayout, d.playerLayout),
            vinylTonearm = p[K.vinylTonearm] ?: d.vinylTonearm,
            // Every part is in the order, a part added later at the end.
            playerDeck = p.enumList(K.playerDeck, d.playerDeck).let { it + (DeckPart.entries - it.toSet()) },
            playerButtons = p.enumList(K.playerButtons, d.playerButtons),
            sessionButtons = p.enumList(K.sessionButtons, d.sessionButtons),
            scrubberColor = p.enum(K.scrubberColor, d.scrubberColor),
            playerTitleAlign = p.enum(K.playerTitleAlign, d.playerTitleAlign),
            playerTitleSize = p.enum(K.playerTitleSize, d.playerTitleSize),
            playerTitleScroll = p[K.playerTitleScroll] ?: d.playerTitleScroll,
            playerTimes = p.enum(K.playerTimes, d.playerTimes),
            meshSpeed = p[K.meshSpeed] ?: d.meshSpeed,
            meshIntensity = p[K.meshIntensity] ?: d.meshIntensity,
            playerBlur = p[K.playerBlur] ?: d.playerBlur,
            canvasFillsBackground = p[K.canvasFillsBackground] ?: d.canvasFillsBackground,
            scrubberThickness = (p[K.scrubberThickness] ?: d.scrubberThickness).coerceIn(0.6f, 2f),
            scrubberThumb = p.enum(K.scrubberThumb, d.scrubberThumb),
            visualizer = p.enum(K.visualizer, d.visualizer),
            visualizerColor = p.enum(K.visualizerColor, d.visualizerColor),
            lyricsStyle = p.enum(K.lyricsStyle, d.lyricsStyle),
            // Until it's set, the strength follows the old switch: on is 1.7's blur, off none.
            lyricsBlurAmount = (p[K.lyricsBlurAmount] ?: if (p[K.lyricsBlur] ?: d.lyricsBlur) 1f else 0f).coerceIn(0f, 2f),
            lyricsSpacing = (p[K.lyricsSpacing] ?: d.lyricsSpacing).coerceIn(0.6f, 1.8f),
            lyricsOthersAlpha = (p[K.lyricsOthersAlpha] ?: d.lyricsOthersAlpha).coerceIn(0.2f, 1f),
            lyricsSungColor = p.enum(K.lyricsSungColor, d.lyricsSungColor),
            lyricsSungCustom = p[K.lyricsSungCustom] ?: d.lyricsSungCustom,
            lyricsOtherColor = p.enum(K.lyricsOtherColor, d.lyricsOtherColor),
            lyricsOtherCustom = p[K.lyricsOtherCustom] ?: d.lyricsOtherCustom,
            lyricsWeight = p.enum(K.lyricsWeight, d.lyricsWeight),
            lyricsBackingSize = (p[K.lyricsBackingSize] ?: d.lyricsBackingSize).coerceIn(0.4f, 0.9f),
            lyricsShadow = p[K.lyricsShadow] ?: d.lyricsShadow,
            lyricsUppercase = p[K.lyricsUppercase] ?: d.lyricsUppercase,
            lyricsAhead = p.enum(K.lyricsAhead, d.lyricsAhead),
            lyricsPosition = p.enum(K.lyricsPosition, d.lyricsPosition),
            lyricsExtraSize = (p[K.lyricsExtraSize] ?: d.lyricsExtraSize).coerceIn(0.7f, 1.4f),
            lyricsExtraColor = p.enum(K.lyricsExtraColor, d.lyricsExtraColor),
            lyricsExtraItalic = p[K.lyricsExtraItalic] ?: d.lyricsExtraItalic,
            lyricsExtraPlace = p.enum(K.lyricsExtraPlace, d.lyricsExtraPlace),
            lyricsBackground = p.enum(K.lyricsBackground, d.lyricsBackground),
            immersiveBackground = p.enum(K.immersiveBackground, d.immersiveBackground),
            lyricsBgColor = p[K.lyricsBgColor] ?: d.lyricsBgColor,
            lyricsBgColor2 = p[K.lyricsBgColor2] ?: d.lyricsBgColor2,
            lyricsEverywhere = p[K.lyricsEverywhere] ?: d.lyricsEverywhere,
            lyricsStripAlign = p.enum(K.lyricsStripAlign, d.lyricsStripAlign),
            lyricsStripTwoLines = p[K.lyricsStripTwoLines] ?: d.lyricsStripTwoLines,
            mergeLikedVideos = p[K.mergeLikedVideos] ?: d.mergeLikedVideos,
            hideStatusBar = p[K.hideStatusBar] ?: d.hideStatusBar,
            volumeControl = p.enum(K.volumeControl, d.volumeControl),
            appVolume = (p[K.appVolume] ?: d.appVolume).coerceIn(0f, 1f),
            coverSwipeLeft = p.enum(K.coverSwipeLeft, d.coverSwipeLeft),
            coverSwipeRight = p.enum(K.coverSwipeRight, d.coverSwipeRight),
            coverSwipeUp = p.enum(K.coverSwipeUp, d.coverSwipeUp),
            coverDoubleTap = p.enum(K.coverDoubleTap, d.coverDoubleTap),
            coverLongPress = p.enum(K.coverLongPress, d.coverLongPress),
            miniSwipeLeft = p.enum(K.miniSwipeLeft, d.miniSwipeLeft),
            miniSwipeRight = p.enum(K.miniSwipeRight, d.miniSwipeRight),
            miniSwipeDown = p.enum(K.miniSwipeDown, d.miniSwipeDown),
            libraryView = p.enum(K.libraryView, d.libraryView),
            libraryViews = p[K.libraryViews]?.let(::parseLibraryViews) ?: d.libraryViews,
            libraryColumns = p.enum(K.libraryColumns, d.libraryColumns),
            librarySubtitles = p[K.librarySubtitles] ?: d.librarySubtitles,
            libraryStartTab = p[K.libraryStartTab] ?: d.libraryStartTab,
            libraryPlaylistsFirst = p[K.libraryPlaylistsFirst] ?: d.libraryPlaylistsFirst,
            libraryTiles = p[K.libraryTiles]?.let { v -> if (v.isEmpty()) emptyList() else v.split(',').mapNotNull { n -> LibraryTile.entries.firstOrNull { it.name == n } } } ?: d.libraryTiles,
            playlistHeroCover = p[K.playlistHeroCover] ?: d.playlistHeroCover,
            frostedPages = p[K.frostedPages] ?: d.frostedPages,
            replayThemeColors = p[K.replayThemeColors] ?: d.replayThemeColors,
            performanceMode = p[K.performanceMode] ?: d.performanceMode,
            performanceOffered = p[K.performanceOffered] ?: d.performanceOffered,
        )
    }

    /** The stored settings as they last read (what [previewLook] starts from). */
    @Volatile private var raw: Preferences = runBlocking { context.dataStore.data.first() }

    /**
     * The settings as Prism uses them: with Performance mode on, its lighter look in place of the listener's heavier
     * choices ([AppSettings.effective]). The choices themselves are [stored], unchanged.
     */
    val flow: StateFlow<AppSettings> = context.dataStore.data.map { raw = it; read(it).effective() }.stateIn(
        scope, SharingStarted.Eagerly, read(raw).effective()
    )

    val current: AppSettings get() = flow.value

    /** The settings as the listener set them (Settings shows these: Performance mode doesn't change a switch). */
    val stored: StateFlow<AppSettings> = context.dataStore.data.map { read(it) }.stateIn(scope, SharingStarted.Eagerly, read(raw))

    /** The look settings now set ([lookOf]): what Looks are saved from and what undo steps back through. */
    val lookValues: StateFlow<Map<String, String>> = context.dataStore.data.map(::lookOf)
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, lookOf(raw))

    /** The look settings as [lookValues] will read them once [values] is the Look (what a switch is expected to land on). */
    fun lookAfter(values: Map<String, String>): Map<String, String> = lookOf(raw.toMutablePreferences().also { putLook(it, values) })

    /** The settings as they'd be with [values] as the Look, without changing anything (Look cards draw from it). */
    fun previewLook(values: Map<String, String>): AppSettings = read(raw.toMutablePreferences().also { putLook(it, values) })

    /**
     * Makes [values] the Look in one edit, so the app redraws once. [fontChanged]: an imported font file was swapped in
     * for it, so text using it is drawn again.
     */
    suspend fun applyLook(values: Map<String, String>, fontChanged: Boolean = false) = editNow { p ->
        putLook(p, values)
        if (fontChanged) p[K.fontStamp] = System.currentTimeMillis()
    }

    /** Puts the look settings named [names] back to how Prism ships ("Reset this page"). */
    @Suppress("UNCHECKED_CAST")
    fun resetLookKeys(names: Collection<String>) = edit { p ->
        names.forEach { n -> lookKeysByName[n]?.let { p.remove(it as Preferences.Key<Any>) } }
    }

    private val writeScope = scope

    private fun edit(block: (MutablePreferences) -> Unit) {
        writeScope.launch { context.dataStore.edit { block(it) } }
    }

    suspend fun editNow(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    fun setOnboarded(v: Boolean) = edit { it[K.onboarded] = v }

    /** Once (1.7): Home layouts saved before "Made for you" and "New releases" existed get them, after Quick picks. */
    fun addNewHomeSections() = edit { p ->
        fun once(flag: String, change: (List<HomeSectionConfig>) -> List<HomeSectionConfig>) {
            val key = booleanPreferencesKey(flag)
            if (p[key] == true) return
            p[K.homeLayout]?.let { stored ->
                runCatching { json.decodeFromString<List<HomeSectionConfig>>(stored) }.getOrNull()?.let { layout ->
                    p[K.homeLayout] = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(HomeSectionConfig.serializer()), change(layout))
                }
            }
            p[key] = true
        }
        once("home_sections_1_7") { HomeSections.withNewSections(it) }
        // Made on the phone (1.7): On repeat and Mixes from your music, after Made for you.
        once("home_sections_local") {
            HomeSections.withNewSections(
                it, listOf(HomeSectionConfig(HomeSections.ON_REPEAT, "On repeat")),
                after = HomeSections.MADE_FOR_YOU,
            )
        }
        // Mixes from your music joined Made for you (1.7): one section.
        once("home_mixes_merged") { HomeSections.mergeYourMixes(it) }
        // The Replay card heads Home (1.7).
        once("home_replay_top") { HomeSections.replayOnTop(it) }
    }

    suspend fun saveAccount(cookie: String, visitorData: String, dataSyncId: String) = editNow {
        it[K.cookie] = cookie
        it[K.visitorData] = visitorData
        it[K.dataSyncId] = dataSyncId
        // A fresh sign-in is the session's first Google account.
        it[K.authUser] = 0
    }

    fun saveAccountInfo(name: String, email: String?, avatar: String?) = edit {
        it[K.accountName] = name
        it[K.accountEmail] = email ?: ""
        it[K.accountAvatar] = avatar ?: ""
    }

    /** Switches to another account or channel: [authUser] picks the Google account, [dataSyncId] the channel. */
    suspend fun saveIdentity(authUser: Int, dataSyncId: String, cookie: String? = null) = editNow {
        it[K.authUser] = authUser
        it[K.dataSyncId] = dataSyncId
        if (cookie != null) it[K.cookie] = cookie
    }

    fun setVisitorData(v: String) = edit { it[K.visitorData] = v }
    fun setDiscordPresence(v: Boolean) = edit { it[K.discordPresence] = v }

    fun logout() = edit {
        it.remove(K.cookie); it.remove(K.dataSyncId); it.remove(K.authUser)
        it.remove(K.accountName); it.remove(K.accountEmail); it.remove(K.accountAvatar)
    }

    fun setThemeMode(v: ThemeMode) = edit { it[K.themeMode] = v.name }
    fun setPureBlack(v: Boolean) = edit { it[K.pureBlack] = v }
    fun setColorSource(v: ColorSource) = edit { it[K.colorSource] = v.name }
    fun setAccent(v: Int) = edit { it[K.accent] = v }
    fun setLiquidGlass(v: Boolean) = edit { it[K.liquidGlass] = v; if (v) it[K.frostedGlass] = false }
    fun setFrostedGlass(v: Boolean) = edit { it[K.frostedGlass] = v; if (v) it[K.liquidGlass] = false }
    fun setHaptics(v: Boolean) = edit { it[K.haptics] = v }
    fun setGlassKind(v: GlassKind) = edit { it[K.glassKind] = v.name }
    fun setLightStyle(v: LightStyle) = edit { it[K.lightStyle] = v.name }
    fun setAccentExact(v: Boolean) = edit { it[K.accentExact] = v }
    fun setAccentLight(v: Int) = edit { it[K.accentLight] = v }
    fun setSpectrumPreset(v: String) = edit { it[K.spectrumPreset] = v }
    /** Your own split light: its colors (2–7, in order) and, with them, the Custom preset. */
    fun setSpectrumStops(v: List<Int>) = edit {
        it[K.spectrumStops] = v.take(7).joinToString(",") { c -> "%08X".format(c) }
        it[K.spectrumPreset] = "CUSTOM"
    }
    fun setSpectrumBands(v: Boolean) = edit { it[K.spectrumBands] = v }
    fun setSurfaceTint(v: SurfaceTint) = edit { it[K.surfaceTint] = v.name }
    fun setSurfaceTintStrength(v: Float) = edit { it[K.surfaceTintStrength] = v }
    fun setSurfaceColor(v: Int) = edit { it[K.surfaceColor] = v; it[K.surfaceTint] = SurfaceTint.CUSTOM.name }
    fun setContrast(v: ContrastLevel) = edit { it[K.contrast] = v.name }
    fun setArtworkSwatch(v: ArtworkSwatch) = edit { it[K.artworkSwatch] = v.name }
    fun setArtworkVivid(v: Boolean) = edit { it[K.artworkVivid] = v }
    fun setArtworkChange(v: ArtworkChange) = edit { it[K.artworkChange] = v.name }
    fun setPageColors(v: Boolean) = edit { it[K.pageColors] = v }
    /** Appearance → Color → Reset colors: style, accent, split light, background, contrast and cover color as Prism ships. */
    @Suppress("UNCHECKED_CAST")
    fun resetColors() = edit { p -> K.colors.forEach { p.remove(it as Preferences.Key<Any>) } }
    fun setFullBleedArtwork(v: Boolean) = edit { it[K.fullBleedArtwork] = v }
    fun setVideoFullBleed(v: Boolean) = edit { it[K.videoFullBleed] = v }
    fun setImmersivePlayer(v: Boolean) = edit { it[K.immersivePlayer] = v }
    fun setImmersiveArt(v: ImmersiveArt) = edit { it[K.immersiveArt] = v.name }
    fun setImmersiveLyrics(v: ImmersiveLyrics) = edit { it[K.immersiveLyrics] = v.name }
    fun setImmersiveControls(v: Boolean) = edit { it[K.immersiveControls] = v }
    fun setPlayerShuffleRepeat(v: Boolean) = edit { it[K.playerShuffleRepeat] = v }
    fun setPreload(v: Preload) = edit { it[K.preload] = v.name }
    fun setPreloadCellular(v: Boolean) = edit { it[K.preloadCellular] = v }
    fun setCanvasEnabled(v: Boolean) = edit { it[K.canvasEnabled] = v }
    fun setCanvasOnCellular(v: Boolean) = edit { it[K.canvasOnCellular] = v }
    fun setHideVolumeBar(v: Boolean) = edit { it[K.hideVolumeBar] = v }
    fun setShowPlayingFrom(v: Boolean) = edit { it[K.showPlayingFrom] = v }
    fun setSpatial(v: Boolean) = edit { it[K.spatial] = v }
    fun setSpatialAmount(v: Float) = edit { it[K.spatialAmount] = v }
    fun setLyricsBlur(v: Boolean) = edit { it[K.lyricsBlur] = v }
    fun setLyricsProgressLine(v: Boolean) = edit { it[K.lyricsProgressLine] = v }
    fun setBackdrops(v: Map<String, String>) = edit { it[K.backdrops] = json.encodeToString<Map<String, String>>(v) }
    fun setBackdropDim(v: Float) = edit { it[K.backdropDim] = v }
    fun setBackdropMotion(v: Boolean) = edit { it[K.backdropMotion] = v }
    fun setLyricsOnPlayer(v: Boolean) = edit { it[K.lyricsOnPlayer] = v }
    fun setLyricsOrder(v: List<LyricsSource>) = edit { it[K.lyricsOrder] = v.joinToString(",") { s -> s.name } }
    fun setPreferSynced(v: Boolean) = edit { it[K.preferSynced] = v }
    fun setLyricsOff(v: Set<LyricsSource>) = edit { it[K.lyricsOff] = v.joinToString(",") { s -> s.name } }
    fun setSkipCensored(v: Boolean) = edit { it[K.skipCensored] = v }
    fun setLyricsTranslate(v: Boolean) = edit { it[K.lyricsTranslate] = v }
    fun setLyricsTranslateTo(v: String) = edit { it[K.lyricsTranslateTo] = v }
    fun setRomanize(v: RomanizeMode) = edit { it[K.romanize] = v.name }
    fun setRomanizeAll(v: Boolean) = edit { it[K.romanizeAll] = v }
    fun setSingOnCellular(v: Boolean) = edit { it[K.singOnCellular] = v }
    fun setSingAlwaysReady(v: Boolean) = edit { it[K.singAlwaysReady] = v }
    fun setSingCacheMb(v: Int) = edit { it[K.singCacheMb] = v }
    fun setDjFlow(v: com.prism.music.playback.DjFlow) = edit { it[K.djFlow] = v.name }
    fun setReleaseNotifications(v: Boolean) = edit { it[K.releaseNotifications] = v }
    fun setRewindNotification(v: Boolean) = edit { it[K.rewindNotification] = v }
    fun setWebRemote(v: Boolean) = edit { it[K.webRemote] = v }
    fun setHandoffAvailable(v: Boolean) = edit { it[K.handoffAvailable] = v }
    fun setLyricsScale(v: Float) = edit { it[K.lyricsScale] = v }
    fun setCarLyrics(v: Boolean) = edit { it[K.carLyrics] = v }
    fun setCarLyricsStyle(v: CarLyricsStyle) = edit { it[K.carLyricsStyle] = v.name }
    fun setCarLyricsOnPhone(v: Boolean) = edit { it[K.carLyricsOnPhone] = v }
    fun setCarLyricsKeepTitle(v: Boolean) = edit { it[K.carLyricsKeepTitle] = v }
    fun setAutoGrid(v: Boolean) = edit { it[K.autoGrid] = v }
    fun setShowNerdStats(v: Boolean) = edit { it[K.showNerdStats] = v }
    fun setShowQuality(v: Boolean) = edit { it[K.showQuality] = v }
    fun setSquareVideoCovers(v: Boolean) = edit { it[K.squareVideoCovers] = v }
    fun setLyricsOnMobileData(v: Boolean) = edit { it[K.lyricsOnMobileData] = v }
    fun setBestPartButton(v: Boolean) = edit { it[K.bestPartButton] = v }
    fun setShowVideoSwitch(v: Boolean) = edit { it[K.showVideoSwitch] = v }
    fun setHoldSeekSeconds(v: Int) = edit { it[K.holdSeekSeconds] = v.coerceIn(0, 20) }
    fun setCanvasWithDownloads(v: Boolean) = edit { it[K.canvasWithDownloads] = v }
    fun setCanvasWithDownloadsCellular(v: Boolean) = edit { it[K.canvasWithDownloadsCellular] = v }
    fun setAudioQuality(v: AudioQuality) = edit { it[K.audioQuality] = v.name }
    fun setCellularQuality(v: AudioQuality) = edit { it[K.cellularQuality] = v.name }
    fun setPremiumStream(v: Boolean) = edit { it[K.premiumStream] = v }
    fun setAutoplay(v: Boolean) = edit { it[K.autoplay] = v }
    fun setAutoplaySource(v: AutoplaySource) = edit { it[K.autoplaySource] = v.name }
    fun setReplayCard(name: String) = edit { it[K.replayCard] = name }
    fun setRadioSongsOnly(v: Boolean) = edit { it[K.radioSongsOnly] = v }
    fun setSongsEverywhere(v: Boolean) = edit { it[K.songsEverywhere] = v }
    fun setShowTrackLength(v: Boolean) = edit { it[K.showTrackLength] = v }
    fun setAudioDiagnostics(v: Boolean) = edit { it[K.audioDiagnostics] = v }
    fun setSearchKeyboard(v: Boolean) = edit { it[K.searchKeyboard] = v }
    fun setDoubleTapSeekSeconds(v: Int) = edit { it[K.doubleTapSeekSeconds] = v }
    fun setStopOnSwipeAway(v: Boolean) = edit { it[K.stopOnSwipeAway] = v }
    fun setDataSaver(v: Boolean) = edit { it[K.dataSaver] = v }
    fun setVideoContinues(v: Boolean) = edit { it[K.videoContinues] = v }
    fun setLossless(v: Boolean) = edit { it[K.lossless] = v }
    fun setLosslessLocal(v: Boolean) = edit { it[K.losslessLocal] = v }
    fun setNormalize(v: Boolean) = edit { it[K.normalize] = v }
    fun setMix(v: Boolean) = edit { it[K.mix] = v }
    fun setMixSeconds(v: Int) = edit { it[K.mixSeconds] = v.coerceIn(1, 12) }
    fun setMixAuto(v: Boolean) = edit { it[K.mixAuto] = v }
    fun setMixBeatMatch(v: Boolean) = edit { it[K.mixBeatMatch] = v }
    fun setDropLeadSeconds(v: Int) = edit { it[K.dropLeadSeconds] = v.coerceIn(5, 30) }
    fun setMixTempoOnCellular(v: Boolean) = edit { it[K.mixTempoOnCellular] = v }
    fun setContentCountry(v: String) = edit { it[K.contentCountry] = v }
    fun setSkipSilence(v: Boolean) = edit { it[K.skipSilence] = v }
    fun setVideoMaxHeight(v: Int) = edit { it[K.videoMaxHeight] = v }
    fun setCacheSizeMb(v: Int) = edit { it[K.cacheSizeMb] = v }
    fun setDownloadWifiOnly(v: Boolean) = edit { it[K.downloadWifiOnly] = v }
    fun setDownloadLikes(v: Boolean) = edit { it[K.downloadLikes] = v }
    fun setSmartDownloads(v: Boolean) = edit { it[K.smartDownloads] = v }
    fun setSmartDownloadGb(v: Float) = edit { it[K.smartDownloadGb] = v }
    fun setOfflineGb(v: Float) = edit { it[K.offlineGb] = v }
    fun setSmartDownloadsCellular(v: Boolean) = edit { it[K.smartDownloadsCellular] = v }
    fun setEq(v: EqSettings) = edit { it[K.eq] = json.encodeToString(EqSettings.serializer(), v) }
    fun setHomeLayout(v: List<HomeSectionConfig>) = edit {
        it[K.homeLayout] = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(HomeSectionConfig.serializer()), v)
    }
    fun setAutoAddSections(v: Boolean) = edit { it[K.autoAddSections] = v }
    fun setSearchSections(v: List<SearchSection>) = edit { it[K.searchSections] = v.joinToString(",") { s -> s.name } }
    fun setHomeHeader(v: HomeHeader) = edit { it[K.homeHeader] = v.name }
    fun setHomeHeaderHeight(v: HomeHeaderHeight) = edit { it[K.homeHeaderHeight] = v.name }
    fun setHomeHeaderPicture(v: String) = edit { it[K.homeHeaderPicture] = v }
    fun setHomeHideKind(kind: String, hide: Boolean) = edit {
        val now = (it[K.homeHideKinds]?.split(',')?.filter { k -> k.isNotBlank() }?.toSet() ?: emptySet()).let { s -> if (hide) s + kind else s - kind }
        it[K.homeHideKinds] = now.joinToString(",")
    }
    fun setReplayHours(v: Boolean) = edit { it[K.replayHours] = v }
    fun setGreetingText(v: String) = edit { it[K.greetingText] = v }
    fun setGreetingShowName(v: Boolean) = edit { it[K.greetingShowName] = v }
    fun setHomeShortcuts(v: List<HomeShortcut>) = edit { it[K.homeShortcuts] = v.joinToString(",") { s -> s.name } }
    fun setHomeRecentTiles(v: Int) = edit { it[K.homeRecentTiles] = v }
    fun setHomePlaylists(v: List<PinnedPlaylist>) = edit { it[K.homePlaylists] = json.encodeToString<List<PinnedPlaylist>>(v) }
    fun setHomeCustomizeButton(v: Boolean) = edit { it[K.homeCustomizeButton] = v }
    fun setHomeContinue(v: Boolean) = edit { it[K.homeContinue] = v }
    fun setQuickPicksRadio(v: Boolean) = edit { it[K.quickPicksRadio] = v }
    fun setSpeedDialYouTubeOnly(v: Boolean) = edit { it[K.speedDialYouTubeOnly] = v }
    fun setHomeCustomizeTopButton(v: Boolean) = edit { it[K.homeCustomizeTopButton] = v }
    fun setHomePodcastsButton(v: Boolean) = edit { it[K.homePodcastsButton] = v }
    fun setFontChoice(v: FontChoice) = edit { it[K.fontChoice] = v.name }
    /** After importing (or removing, [name] blank) the app's own font. */
    fun setAppFont(name: String) = edit { it[K.appFontName] = name; it[K.fontStamp] = System.currentTimeMillis() }
    fun setLyricsFont(v: String) = edit { it[K.lyricsFont] = v }
    fun setLyricsFontFile(name: String) = edit { it[K.lyricsFontName] = name; it[K.fontStamp] = System.currentTimeMillis() }
    fun setTextScale(v: Float) = edit { it[K.textScale] = v }
    fun setCorners(v: Corners) = edit { it[K.corners] = v.name }
    fun setUiDensity(v: UiDensity) = edit { it[K.uiDensity] = v.name }
    fun setTitleSize(v: TitleSize) = edit { it[K.titleSize] = v.name }
    fun setCardSize(v: CardSize) = edit { it[K.cardSize] = v.name }
    fun setReduceMotion(v: Boolean) = edit { it[K.reduceMotion] = v }
    fun setNavStyle(v: NavBarStyle) = edit { it[K.navStyle] = v.name }
    fun setNavLabels(v: NavLabels) = edit { it[K.navLabels] = v.name }
    fun setNavHideOnScroll(v: Boolean) = edit { it[K.navHideOnScroll] = v }
    fun setNavTabs(v: List<NavTab>) = edit { it[K.navTabs] = v.joinToString(",") { t -> t.name } }
    /** A tab's own name; blank gives it back its own. */
    fun setTabName(tab: NavTab, name: String) = edit {
        val names = (it[K.tabNames]?.let(::parseTabNames) ?: emptyMap()).toMutableMap()
        val clean = cleanTabName(name)
        if (clean.isEmpty() || clean == tab.label) names.remove(tab) else names[tab] = clean
        it[K.tabNames] = names.entries.joinToString("|") { (t, n) -> "${t.name}=$n" }
    }
    fun setNavBarSize(v: NavBarSize) = edit { it[K.navBarSize] = v.name }
    fun setNavBarFill(v: NavBarFill) = edit { it[K.navBarFill] = v.name }
    fun setSwipeTabs(v: Boolean) = edit { it[K.swipeTabs] = v }
    fun setMiniPlayerJoined(v: Boolean) = edit { it[K.miniPlayerJoined] = v }
    fun setLocalSections(v: List<LocalSection>) = edit { it[K.localSections] = v.joinToString(",") { t -> t.name } }
    fun setLocalSort(v: String) = edit { it[K.localSort] = v }
    fun setStartTab(v: NavTab) = edit { it[K.startTab] = v.name }
    fun setMiniPlayerStyle(v: MiniPlayerStyle) = edit { it[K.miniPlayerStyle] = v.name }
    fun setMiniSkipButtons(v: Boolean) = edit { it[K.miniSkipButtons] = v }
    fun setMiniPrevButton(v: Boolean) = edit { it[K.miniPrevButton] = v }
    fun setMiniProgress(v: MiniProgress) = edit { it[K.miniProgress] = v.name }
    fun setPlayerBackground(v: PlayerBackground) = edit { it[K.playerBackground] = v.name }
    fun setPlayerBackdropShow(v: Float) = edit { it[K.playerBackdropShow] = v }
    fun setPlayerArtCorners(v: Int) = edit { it[K.playerArtCorners] = v }
    fun setTransportStyle(v: TransportStyle) = edit { it[K.transportStyle] = v.name }
    fun setCoverSize(v: CoverSize) = edit { it[K.coverSize] = v.name }
    fun setScrubberStyle(v: ScrubberStyle) = edit { it[K.scrubberStyle] = v.name }
    fun setPlayerLayout(v: PlayerLayout) = edit { it[K.playerLayout] = v.name }
    fun setVinylTonearm(v: Boolean) = edit { it[K.vinylTonearm] = v }
    fun setPlayerDeck(v: List<DeckPart>) = edit { it[K.playerDeck] = v.joinToString(",") { p -> p.name } }
    fun setPlayerButtons(v: List<PlayerButton>) = edit { it[K.playerButtons] = v.joinToString(",") { b -> b.name } }
    fun setSessionButtons(v: List<SessionButton>) = edit { it[K.sessionButtons] = v.joinToString(",") { b -> b.name } }
    fun setScrubberColor(v: ScrubberColor) = edit { it[K.scrubberColor] = v.name }
    fun setPlayerTitleAlign(v: PlayerTitleAlign) = edit { it[K.playerTitleAlign] = v.name }
    fun setPlayerTitleSize(v: PlayerTitleSize) = edit { it[K.playerTitleSize] = v.name }
    fun setPlayerTitleScroll(v: Boolean) = edit { it[K.playerTitleScroll] = v }
    fun setPlayerTimes(v: PlayerTimes) = edit { it[K.playerTimes] = v.name }
    fun setMeshSpeed(v: Float) = edit { it[K.meshSpeed] = v }
    fun setMeshIntensity(v: Float) = edit { it[K.meshIntensity] = v }
    fun setPlayerBlur(v: Float) = edit { it[K.playerBlur] = v }
    fun setCanvasFillsBackground(v: Boolean) = edit { it[K.canvasFillsBackground] = v }
    fun setScrubberThickness(v: Float) = edit { it[K.scrubberThickness] = v.coerceIn(0.6f, 2f) }
    fun setScrubberThumb(v: ScrubberThumb) = edit { it[K.scrubberThumb] = v.name }
    fun setVisualizer(v: VisualizerStyle) = edit { it[K.visualizer] = v.name }
    fun setVisualizerColor(v: ScrubberColor) = edit { it[K.visualizerColor] = v.name }
    fun setLyricsStyle(v: LyricsStyle) = edit { it[K.lyricsStyle] = v.name }
    /** The blur's strength; the old switch follows (on above none), so older code paths and Looks agree. */
    fun setLyricsBlurAmount(v: Float) = edit { it[K.lyricsBlurAmount] = v; it[K.lyricsBlur] = v > 0f }
    fun setLyricsSpacing(v: Float) = edit { it[K.lyricsSpacing] = v }
    fun setLyricsOthersAlpha(v: Float) = edit { it[K.lyricsOthersAlpha] = v }
    fun setLyricsSungColor(v: LyricsColor) = edit { it[K.lyricsSungColor] = v.name }
    fun setLyricsSungCustom(v: Int) = edit { it[K.lyricsSungCustom] = v; it[K.lyricsSungColor] = LyricsColor.CUSTOM.name }
    fun setLyricsOtherColor(v: LyricsColor) = edit { it[K.lyricsOtherColor] = v.name }
    fun setLyricsOtherCustom(v: Int) = edit { it[K.lyricsOtherCustom] = v; it[K.lyricsOtherColor] = LyricsColor.CUSTOM.name }
    fun setLyricsWeight(v: LyricsWeight) = edit { it[K.lyricsWeight] = v.name }
    fun setLyricsBackingSize(v: Float) = edit { it[K.lyricsBackingSize] = v }
    fun setLyricsShadow(v: Boolean) = edit { it[K.lyricsShadow] = v }
    fun setLyricsUppercase(v: Boolean) = edit { it[K.lyricsUppercase] = v }
    fun setLyricsAhead(v: LyricsAhead) = edit { it[K.lyricsAhead] = v.name }
    fun setLyricsPosition(v: LyricsPosition) = edit { it[K.lyricsPosition] = v.name }
    fun setLyricsExtraSize(v: Float) = edit { it[K.lyricsExtraSize] = v }
    fun setLyricsExtraColor(v: LyricsColor) = edit { it[K.lyricsExtraColor] = v.name }
    fun setLyricsExtraItalic(v: Boolean) = edit { it[K.lyricsExtraItalic] = v }
    fun setLyricsExtraPlace(v: LyricsExtraPlace) = edit { it[K.lyricsExtraPlace] = v.name }
    fun setLyricsBackground(v: LyricsBackground) = edit { it[K.lyricsBackground] = v.name }
    fun setImmersiveBackground(v: LyricsBackground) = edit { it[K.immersiveBackground] = v.name }
    fun setLyricsBgColors(first: Int, second: Int) = edit { it[K.lyricsBgColor] = first; it[K.lyricsBgColor2] = second }
    fun setLyricsEverywhere(v: Boolean) = edit { it[K.lyricsEverywhere] = v }
    fun setLyricsStripAlign(v: LyricsAlign) = edit { it[K.lyricsStripAlign] = v.name }
    fun setLyricsStripTwoLines(v: Boolean) = edit { it[K.lyricsStripTwoLines] = v }
    fun setMergeLikedVideos(v: Boolean) = edit { it[K.mergeLikedVideos] = v }
    fun setHideStatusBar(v: Boolean) = edit { it[K.hideStatusBar] = v }
    fun setVolumeControl(v: VolumeControl) = edit { it[K.volumeControl] = v.name }
    fun setAppVolume(v: Float) = edit { it[K.appVolume] = v.coerceIn(0f, 1f) }
    /** One of the gestures ([which]: "cover_swipe_left" … "mini_swipe_down", the setting's stored name). */
    fun setGesture(which: String, v: PlayerGesture) = edit { p ->
        val key = gestureKeys[which] ?: return@edit
        p[key] = v.name
    }
    private val gestureKeys = listOf(
        K.coverSwipeLeft, K.coverSwipeRight, K.coverSwipeUp, K.coverDoubleTap, K.coverLongPress, K.miniSwipeLeft, K.miniSwipeRight, K.miniSwipeDown,
    ).associateBy { it.name }
    fun setArtShrinkOnPause(v: Boolean) = edit { it[K.artShrinkOnPause] = v }
    fun setShowOutputDevice(v: Boolean) = edit { it[K.showOutputDevice] = v }
    fun setLyricsAlign(v: LyricsAlign) = edit { it[K.lyricsAlign] = v.name }
    fun setLibraryView(v: LibraryView) = edit { it[K.libraryView] = v.name; it.remove(K.libraryViews) }
    /** One Library tab's view ("PLAYLISTS", "ALBUMS", "ARTISTS"). */
    fun setLibraryTabView(tab: String, v: LibraryView) = edit {
        val views = (it[K.libraryViews]?.let(::parseLibraryViews) ?: emptyMap()) + (tab to v)
        it[K.libraryViews] = views.entries.joinToString("|") { (t, view) -> "$t=${view.name}" }
    }
    fun setLibraryColumns(v: LibraryColumns) = edit { it[K.libraryColumns] = v.name }
    fun setLibrarySubtitles(v: Boolean) = edit { it[K.librarySubtitles] = v }
    fun setLibraryStartTab(v: String) = edit { it[K.libraryStartTab] = v }
    fun setLibraryTiles(v: List<LibraryTile>) = edit { it[K.libraryTiles] = v.joinToString(",") { t -> t.name } }
    fun setLibraryPlaylistsFirst(v: Boolean) = edit { it[K.libraryPlaylistsFirst] = v }
    fun setPlaylistHeroCover(v: Boolean) = edit { it[K.playlistHeroCover] = v }
    fun setFrostedPages(v: Boolean) = edit { it[K.frostedPages] = v }
    fun setReplayThemeColors(v: Boolean) = edit { it[K.replayThemeColors] = v }
    fun setPerformanceMode(v: Boolean) = edit { it[K.performanceMode] = v; it[K.performanceOffered] = true }
    fun setPerformanceOffered() = edit { it[K.performanceOffered] = true }

    /** Puts every look & layout option back to how Prism ships (colors and backgrounds are left alone). */
    @Suppress("UNCHECKED_CAST")
    fun resetLook() = edit { p -> K.look.forEach { p.remove(it as Preferences.Key<Any>) } }
}
