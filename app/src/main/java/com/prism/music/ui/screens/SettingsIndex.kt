package com.prism.music.ui.screens

import com.prism.music.data.innertube.SearchRank

/**
 * What Settings' search looks through (#73): every setting by its title, the page and group it sits in, and other words
 * people may look for it by. [SettingsIndexTest] checks that each setting row and group in [SettingsScreen] is listed,
 * so a new setting needs a line here.
 */
data class SettingEntry(
    /** The page it's on; null for the Settings hub itself (the account). */
    val section: SettingsSection?,
    val group: String,
    val title: String,
    val keywords: String = "",
    /** How a result reads when the title alone wouldn't say what it is ("Everywhere"). */
    val shown: String = title,
    /** The switch it only shows under (Crossfade's Length needs Crossfade on); the result says to turn that on first. */
    val needs: Needs? = null,
)

/** A setting that only shows while [on] holds; [hint] says what to change first ("Turn on Crossfade songs first"). */
class Needs(val hint: String, val on: (com.prism.music.data.prefs.AppSettings) -> Boolean)

object SettingsIndex {
    private fun turnOn(name: String, on: (com.prism.music.data.prefs.AppSettings) -> Boolean) = Needs("Turn on $name first", on)
    private val immersive = turnOn("Immersive mode") { it.immersivePlayer }
    private val canvas = turnOn("Animated covers") { it.canvasEnabled }
    private val canvasSave = turnOn("Animated covers and Save with downloads") { it.canvasEnabled && it.canvasWithDownloads }
    private val holdSeek = turnOn("Hold previous / next to seek") { it.holdSeekSeconds > 0 }
    private val songsOnly = turnOn("Songs, not videos") { it.radioSongsOnly }
    private val crossfade = turnOn("Crossfade songs") { it.mix }
    private val manualLength = Needs("Turn on Crossfade songs and turn off Automatic length first") { it.mix && !it.mixAuto }
    private val lossless = turnOn("Lossless playback") { it.lossless }
    private val notLossless = Needs("Lossless playback always plays the best audio; turn it off to pick a quality") { !it.lossless }
    private val saver = turnOn("Lossless playback and Data saver") { it.lossless && it.dataSaver }
    private val measuring = turnOn("Normalize volume or Crossfade songs") { it.normalize || it.mix }
    private val translate = turnOn("Translate lyrics") { it.lyricsTranslate }
    private val romanize = Needs("Pick a Romanize lyrics option first") { it.romanize != com.prism.music.data.prefs.RomanizeMode.OFF }
    private val carLyrics = turnOn("Lyrics in the car") { it.carLyrics }
    private val smart = turnOn("Smart Downloads") { it.smartDownloads }
    private val webRemote = turnOn("Control from a browser") { it.webRemote }

    private val A = SettingsSection.APPEARANCE
    private val CO = SettingsSection.COLOR
    private val T = SettingsSection.TEXT
    private val I = SettingsSection.ICONS
    private val G = SettingsSection.GLASS
    private val LK = SettingsSection.LOOKS
    private val L = SettingsSection.INTERFACE
    private val P = SettingsSection.PLAYER
    private val S = SettingsSection.SOUND
    private val Y = SettingsSection.LYRICS
    private val D = SettingsSection.DOWNLOADS
    private val H = SettingsSection.HOME
    private val R = SettingsSection.LIBRARY
    private val B = SettingsSection.ABOUT
    private val C = SettingsSection.CONNECT
    private val DC = SettingsSection.DISCORD
    private val YL = SettingsSection.LYRICS_LOOK
    private val CT = SettingsSection.CONTROLS
    private val DV = SettingsSection.DEVICES

    val entries: List<SettingEntry> = listOf(
        // The hub
        SettingEntry(null, "Account", "Account", "google youtube sign in sign out switch account add account"),
        SettingEntry(null, "Account", "Prism Account", "profile name picture photo local on this phone copy library youtube playlists"),
        SettingEntry(null, "Performance", "Performance mode", "slow lag laggy stutter battery old phone fast faster lighter simple plain 1.6 effects blur animations smooth"),
        SettingEntry(null, "Performance", "Performance mode is on", "paused effects turn off"),
        SettingEntry(DC, "Discord", "Show what you're playing", "discord rich presence status listening"),
        SettingEntry(DC, "Discord", "Sign out of Discord"),
        SettingEntry(DC, "Discord", "About this", "discord terms risk"),
        // Appearance
        SettingEntry(A, "Theme", "Theme", "dark light system mode night"),
        SettingEntry(A, "Theme", "Pure black", "amoled oled"),
        SettingEntry(A, "The whole look", "Color", "color colors colour accent split light spectrum"),
        SettingEntry(A, "The whole look", "Text", "font typeface size"),
        SettingEntry(A, "The whole look", "Icons & shapes", "icon pack app icon corners"),
        SettingEntry(A, "The whole look", "Glass & backgrounds", "blur backgrounds motion"),
        SettingEntry(A, "Each part", "Tabs & mini player", "layout navigation tab bar bottom bar mini player pages"),
        SettingEntry(A, "Each part", "Home & Library", "home screen library look views"),
        SettingEntry(A, "Each part", "Player", "now playing look layout cover"),
        SettingEntry(A, "Each part", "Lyrics", "lyrics look style colors"),
        SettingEntry(A, "The whole look", "Looks", "theme preset save switch share style outfit skin"),
        SettingEntry(A, "Customize this", "Customize by tapping", "edit mode customise tap long press mini player tab bar player home section"),
        // Appearance → Looks (1.7.3)
        SettingEntry(LK, "Prism's Looks", "Prism's Looks", "theme preset paper midnight neon soft studio built in"),
        SettingEntry(LK, "Your Looks", "Your Looks", "saved theme preset save rename duplicate delete share qr code"),
        SettingEntry(LK, "From the community", "Browse community Looks", "gallery github themes shared download others community"),
        SettingEntry(LK, "From the community", "Couldn't load the gallery", "gallery offline"),
        SettingEntry(LK, "From the community", "Refresh the list", "gallery github themes"),
        SettingEntry(LK, "From the community", "Add your Look", "gallery github pull request contribute share theme"),
        SettingEntry(LK, "Get a Look", "Scan a Look's QR code", "camera theme share friend"),
        SettingEntry(LK, "Get a Look", "Paste a Look code", "clipboard theme share"),
        SettingEntry(LK, "Get a Look", "Open a Look file", "prismlook file import theme"),
        SettingEntry(LK, "Day and night", "Day and night Looks", "schedule sunset sunrise automatic dark mode night time theme"),
        SettingEntry(LK, "Day and night", "By day", "schedule day look morning"),
        SettingEntry(LK, "Day and night", "By night", "schedule night look evening dark"),
        SettingEntry(LK, "Day and night", "Switch", "schedule sunrise sunset set times"),
        SettingEntry(LK, "Day and night", "Day starts at", "schedule time"),
        SettingEntry(LK, "Day and night", "Night starts at", "schedule time"),
        SettingEntry(LK, "History", "History", "undo go back changes revert"),
        SettingEntry(LK, "Your Looks", "Include its photos", "share file"),
        SettingEntry(LK, "Your Looks", "Include its font", "share file"),
        SettingEntry(H, "This section", "Hide this section", "customize this home"),
        // Appearance → Color (1.7.2)
        SettingEntry(CO, "Spectrum", "Spectrum", "split light gradient rainbow preset sunset ocean aurora forest ember candy neon ice gold silver color-blind colour blind custom own colors style light colors"),
        SettingEntry(CO, "Spectrum", "White", "white light black monochrome neutral grey gray minimal colorless simple style"),
        SettingEntry(CO, "Spectrum", "Smooth or bands", "stripes hard spectrum split light rainbow"),
        SettingEntry(CO, "Accent color", "Accent from", "song cover wallpaper your pick now playing artwork dynamic material you color"),
        SettingEntry(CO, "Accent color", "Accent color", "color accent picker wheel hex code rgb hsl custom eyedropper"),
        SettingEntry(CO, "Accent color", "Exactly as picked", "accent color exact shade softened tone"),
        SettingEntry(CO, "Accent color", "How it's used", "accent color exact softened as picked",
            needs = Needs("Pick Your pick for the accent first") { it.colorSource == com.prism.music.data.prefs.ColorSource.CUSTOM }),
        SettingEntry(CO, "Accent color", "Which of its colors", "song cover vibrant muted dominant swatch accent",
            needs = Needs("Pick Song's cover for the accent first") { it.colorSource == com.prism.music.data.prefs.ColorSource.ARTWORK }),
        SettingEntry(CO, "Accent color", "Different color for light mode", "accent color light theme another"),
        SettingEntry(CO, "Accent color", "Light mode accent", "accent color light theme"),
        SettingEntry(CO, "Accent color", "Cover color", "song cover now playing artwork accent vibrant muted color",
            needs = Needs("Pick Song's cover for the accent first") { it.colorSource == com.prism.music.data.prefs.ColorSource.ARTWORK }),
        SettingEntry(CO, "Accent color", "Always colorful", "song cover now playing artwork accent saturation grey color lively",
            needs = Needs("Pick Song's cover for the accent first") { it.colorSource == com.prism.music.data.prefs.ColorSource.ARTWORK }),
        SettingEntry(CO, "Accent color", "Between songs", "song cover now playing artwork accent fade album change color",
            needs = Needs("Pick Song's cover for the accent first") { it.colorSource == com.prism.music.data.prefs.ColorSource.ARTWORK }),
        SettingEntry(CO, "Page background", "Page background", "tint surface page neutral plain color colour background"),
        SettingEntry(CO, "Page background", "Background color", "tint surface page color",
            needs = Needs("Pick Your color under Page background first") { it.surfaceTint == com.prism.music.data.prefs.SurfaceTint.CUSTOM }),
        SettingEntry(CO, "Page background", "Tint strength", "surface page tint",
            needs = Needs("Pick Accent tint or Your color under Page background first") { it.surfaceTint == com.prism.music.data.prefs.SurfaceTint.ACCENT || it.surfaceTint == com.prism.music.data.prefs.SurfaceTint.CUSTOM }),
        SettingEntry(CO, "Text contrast", "Text contrast", "high contrast accessibility readability legible"),
        SettingEntry(CO, "Artist & album pages", "Photo's color", "artist album playlist page color color photo accent picture"),
        SettingEntry(T, "Text", "Font", "typeface custom ttf otf outfit prism system clean manrope geometric plus jakarta sans airy urbanist rounded nunito editorial serif fraunces mono geist"),
        SettingEntry(T, "Text", "Remove your font"),
        SettingEntry(T, "Text", "Lyrics font", "typeface"),
        SettingEntry(T, "Text", "Lyrics font file"),
        SettingEntry(T, "Text", "Remove the lyrics font file"),
        SettingEntry(T, "Text", "Text size", "font size scale bigger smaller"),
        SettingEntry(I, "Icons", "Pack", "icon style cupertino apple"),
        SettingEntry(I, "Icons", "Weight", "icon thin bold"),
        SettingEntry(I, "Icons", "Rounded", "icon corners"),
        SettingEntry(I, "Icons", "Likes as stars", "heart star like favourite favorite icon"),
        SettingEntry(I, "Icons", "Restart Prism to see them"),
        SettingEntry(I, "App icon", "App icon", "launcher home screen icon logo"),
        SettingEntry(I, "Shape", "Corners", "rounded square shape radius"),
        SettingEntry(G, "Backgrounds", "Screen backgrounds", "wallpaper photo pattern design"),
        SettingEntry(G, "Glass", "Liquid Glass", "blur transparent"),
        SettingEntry(G, "Glass", "Glass style"),
        SettingEntry(G, "Glass", "Frosted Glass", "blur transparent"),
        SettingEntry(G, "Glass", "Frosted pages", "blur artist album playlist"),
        SettingEntry(G, "Motion", "Reduce motion", "animations accessibility"),
        // Layout & navigation
        SettingEntry(L, "Navigation bar", "Style", "tab bar bottom bar"),
        SettingEntry(L, "Navigation bar", "Labels", "tab bar names",
            needs = Needs("The Minimal bar has no labels; pick Floating or Docked first") { it.navStyle != com.prism.music.data.prefs.NavBarStyle.MINIMAL }),
        SettingEntry(L, "Navigation bar", "Shrink while scrolling", "tab bar hide"),
        SettingEntry(L, "Navigation bar", "Height", "tab bar size tall compact"),
        SettingEntry(L, "Navigation bar", "Background", "tab bar see-through solid opacity transparent"),
        SettingEntry(L, "Navigation bar", "Swipe between tabs", "tab bar swipe gesture sideways"),
        SettingEntry(L, "Mini player", "Joined to the tab bar", "mini player merged tab bar one"),
        SettingEntry(L, "Tabs", "Tabs", "tab bar order home search library replay settings local files"),
        SettingEntry(L, "Tabs", "Open Prism on", "start tab first screen"),
        SettingEntry(L, "Mini player", "Mini player", "style card slim pill"),
        SettingEntry(L, "Mini player", "Previous button", "back skip"),
        SettingEntry(L, "Mini player", "Next button", "skip forward"),
        SettingEntry(L, "Mini player", "Progress"),
        SettingEntry(L, "Pages", "Spacing", "density compact"),
        SettingEntry(L, "Pages", "Page titles"),
        SettingEntry(L, "Pages", "Album & playlist cards"),
        SettingEntry(CT, "Search", "Open the keyboard", "search typing"),
        // Player
        SettingEntry(P, "Look", "Background", "player blur cover color"),
        SettingEntry(P, "Look", "Show background"),
        SettingEntry(P, "Look", "Controls", "buttons transport"),
        SettingEntry(P, "Look", "Progress bar", "scrubber seek bar"),
        SettingEntry(P, "Cover", "Cover size", "large full width"),
        SettingEntry(P, "Cover", "Corners", "cover rounded"),
        SettingEntry(P, "Cover", "Full-bleed artwork", "edge to edge"),
        SettingEntry(P, "Cover", "Full-bleed video", "music video edge to edge fill top youtube music"),
        SettingEntry(P, "Cover", "Shrink when paused"),
        SettingEntry(P, "Cover", "Square video covers", "video thumbnail crop black bars letterbox square artwork audio only"),
        SettingEntry(P, "Immersive mode", "Immersive mode", "lyric video full screen"),
        SettingEntry(P, "Immersive mode", "Artwork", needs = immersive),
        SettingEntry(P, "Immersive mode", "Lyrics", needs = immersive),
        SettingEntry(P, "Immersive mode", "Keep controls on screen", "play pause skip next previous buttons visible always", needs = immersive),
        SettingEntry(P, "Animated covers", "Animated covers", "canvas motion video cover"),
        SettingEntry(D, "Mobile data", "Animated covers on mobile data", "canvas cellular play", needs = canvas),
        SettingEntry(D, "Animated covers", "Save with downloads", "canvas offline", needs = canvas),
        SettingEntry(D, "Mobile data", "Save animated covers on mobile data", "canvas cellular", needs = canvasSave),
        SettingEntry(D, "Animated covers", "Saved animated covers", "canvas storage", needs = canvas),
        SettingEntry(D, "Animated covers", "Recently played covers", "canvas storage cache", needs = canvas),
        SettingEntry(P, "Show on the player", "From the album", "album caption playing from"),
        SettingEntry(P, "Show on the player", "Stream quality", "codec bitrate nerd stats"),
        SettingEntry(P, "Show on the player", "Best part", "skip chorus hook highlight most replayed jump"),
        SettingEntry(P, "Show on the player", "Song length", "duration time left remaining"),
        SettingEntry(P, "Show on the player", "Shuffle & repeat"),
        SettingEntry(P, "Show on the player", "Song / Video", "music video audio only"),
        SettingEntry(P, "Show on the player", "Output device", "bluetooth speaker headphones"),
        // The player, your way (1.7.4)
        SettingEntry(P, "Layout", "Layout", "player layout standard cover lyrics minimal vinyl record turntable design style"),
        SettingEntry(P, "Layout", "Tonearm", "vinyl record turntable arm needle", needs = turnOn("Vinyl layout") { it.playerLayout == com.prism.music.data.prefs.PlayerLayout.VINYL }),
        SettingEntry(P, "Look", "Motion", "mesh background drift animate move still"),
        SettingEntry(P, "Look", "Colors", "mesh background saturation vivid soft rich intensity"),
        SettingEntry(P, "Look", "Softness", "blur background strength soft clear"),
        SettingEntry(P, "Buttons", "Buttons", "player bottom row buttons lyrics video queue sleep like share sing dj equalizer download radio add to playlist customize"),
        SettingEntry(CT, "Notification and car", "Notification and car", "notification lock screen android auto car buttons like shuffle repeat radio skip back ahead 10 30 seconds media controls"),
        SettingEntry(P, "Under the title", "Under the title", "order arrange rearrange move current lyric progress bar controls volume buttons"),
        SettingEntry(P, "Under the title", "Current lyric", "lyrics line"),
        SettingEntry(P, "Under the title", "Volume", "slider"),
        SettingEntry(P, "Title and times", "Title and times", "title alignment centered centre left"),
        SettingEntry(P, "Title and times", "Title size", "big small large font"),
        SettingEntry(P, "Title and times", "Scrolling titles", "marquee scroll long title ellipsis"),
        SettingEntry(P, "Title and times", "Times under the progress bar", "elapsed remaining time left none"),
        SettingEntry(P, "Title and times", "Progress bar color", "scrubber color accent white cover spectrum"),
        SettingEntry(P, "Title and times", "Progress bar thickness", "scrubber thick thin height bold"),
        SettingEntry(P, "Title and times", "Knob at the playhead", "scrubber thumb knob dot line ring handle"),
        SettingEntry(P, "Visualizer", "Visualizer", "visualiser bars wave ring pulse glow spectrum music moves beat animation"),
        SettingEntry(P, "Visualizer", "Visualizer color", "visualiser colour accent white cover"),
        SettingEntry(P, "Look", "Which background", "player background own design photo pattern same as home"),
        SettingEntry(P, "Animated covers", "Fill the player", "canvas background full screen behind", needs = canvas),
        SettingEntry(CT, "Gestures", "Gestures", "swipe double tap long press cover mini player next previous seek like queue lyrics sleep video sing stop"),
        SettingEntry(CT, "Gestures", "Double tap", "seek skip seconds fast forward rewind"),
        SettingEntry(CT, "Gestures", "Long press", "hold cover"),
        SettingEntry(CT, "Gestures", "Pull down", "mini player stop"),
        SettingEntry(CT, "Buttons and touch", "Hold previous / next to seek", "fast forward rewind"),
        SettingEntry(CT, "Buttons and touch", "Each step", needs = holdSeek),
        SettingEntry(CT, "Buttons and touch", "Haptics", "vibration vibrate"),
        // Playback & sound
        SettingEntry(S, "Playback", "Autoplay", "radio continue similar"),
        SettingEntry(S, "Playback", "Autoplay from", "radio your music prism radio youtube offline local", needs = turnOn("Autoplay") { it.autoplay }),
        SettingEntry(S, "Playback", "Songs, not videos", "music videos audio"),
        SettingEntry(S, "Playback", "Everywhere", "songs not videos music videos audio", shown = "Songs, not videos: everywhere", needs = songsOnly),
        SettingEntry(S, "Playback", "Keep playing videos", "music video"),
        SettingEntry(S, "Playback", "Skip silence"),
        SettingEntry(S, "Playback", "Stop when swiped away", "recents background"),
        SettingEntry(S, "Playback", "Load ahead", "preload buffer"),
        SettingEntry(D, "Mobile data", "Load ahead on mobile data", "preload cellular"),
        SettingEntry(S, "Crossfade", "Crossfade songs", "mix blend transition fade"),
        SettingEntry(S, "Crossfade", "Automatic length", needs = crossfade),
        SettingEntry(S, "Crossfade", "Length", needs = manualLength),
        SettingEntry(S, "Crossfade", "Match tempo and beats (DJ-style)", "bpm beatmatch", needs = crossfade),
        SettingEntry(S, "Crossfade", "Drop jump", "transition card jump drop blend before", needs = crossfade),
        SettingEntry(S, "Quality", "Lossless playback", "flac hi-res"),
        SettingEntry(S, "Quality", "Allow access to lossless files", "flac permission", needs = lossless),
        SettingEntry(S, "Quality", "Scan for lossless files again", "flac", needs = lossless),
        SettingEntry(S, "Quality", "Streaming quality on Wi-Fi", "bitrate audio quality", needs = notLossless),
        SettingEntry(S, "Quality", "Streaming quality on mobile data", "bitrate audio quality cellular", needs = notLossless),
        SettingEntry(S, "Quality", "Streaming quality on mobile data (data saver)", "bitrate cellular", needs = saver),
        SettingEntry(S, "Quality", "Music video quality", "resolution 1080p 720p"),
        SettingEntry(S, "Quality", "Premium quality", "youtube music premium 256 kbps aac opus high bitrate subscription",
            needs = Needs("Sign in to YouTube Music first") { it.isLoggedIn }),
        SettingEntry(D, "Mobile data", "Data saver", "cellular low data"),
        SettingEntry(S, "Sound", "Normalize volume", "loudness lufs level"),
        SettingEntry(D, "Mobile data", "Measure songs on mobile data", "cellular", needs = measuring),
        SettingEntry(S, "Sound", "Prism Spatial", "spatial audio 3d surround atmos"),
        SettingEntry(S, "Sound", "Intensity", "spatial audio width", shown = "Prism Spatial intensity", needs = Needs("Turn on Prism Spatial first") { it.spatial }),
        SettingEntry(S, "Sound", "Equalizer", "eq bass boost treble loudness headphone correction autoeq harman target"),
        // Lyrics
        SettingEntry(YL, "Look", "Focus blur"),
        SettingEntry(YL, "Look", "Progress line", "spectrum line underline sung lyric"),
        SettingEntry(Y, "Language", "Translate lyrics", "translation language"),
        SettingEntry(Y, "Language", "Translate into", "translation language", needs = translate),
        SettingEntry(Y, "Language", "Romanize lyrics", "romanization romaji pinyin latin letters transliteration korean japanese chinese hindi arabic"),
        SettingEntry(Y, "Language", "Romanize my own language too", "romanization native script", needs = romanize),
        SettingEntry(Y, "Language", "Japanese kanji readings", "romaji kanji dictionary download kuromoji japanese reading data"),
        SettingEntry(Y, "Sing", "Sing's voice separation", "karaoke vocals remove instrumental download model"),
        SettingEntry(D, "Mobile data", "Get songs ready for Sing on mobile data", "karaoke cellular sing"),
        SettingEntry(Y, "Sing", "Get songs ready while Sing is off", "karaoke prepare ahead background preload vocals always ready"),
        SettingEntry(Y, "Sing", "Room for sung songs", "karaoke storage space"),
        SettingEntry(Y, "Sing", "Clear sung songs", "karaoke storage delete"),
        SettingEntry(YL, "Look", "Alignment", "left centre center right"),
        SettingEntry(YL, "Look", "Text size", "lyrics font size"),
        // GitHub requests in 1.7.5 (#111 #115 #116).
        SettingEntry(R, "Liked songs", "A song and its video as one", "merge liked music video duplicate same song twice"),
        SettingEntry(SettingsSection.INTERFACE, "Screen", "Hide the status bar", "status bar clock notifications full screen immersive"),
        SettingEntry(SettingsSection.PLAYER, "Under the title", "The volume slider changes", "volume independent app volume prism's own system media"),
        // Lyrics, your way (1.7.5).
        SettingEntry(YL, "Style", "Lyrics style", "prism classic karaoke bold card glow typewriter calm animation sweep fill"),
        SettingEntry(YL, "Look", "Weight", "lyrics bold thin light heavy font weight"),
        SettingEntry(YL, "Look", "Line spacing", "lyrics gap room between lines"),
        SettingEntry(YL, "Look", "Other lines", "lyrics dim faint brightness opacity inactive"),
        SettingEntry(YL, "Look", "Background vocals", "backing vocals size ad libs"),
        SettingEntry(YL, "Look", "Shadow", "lyrics text shadow readable photo"),
        SettingEntry(YL, "Look", "Capitals", "uppercase caps all capitals lyrics"),
        SettingEntry(YL, "Colors", "The line being sung", "lyrics color sung highlight accent spectrum cover custom"),
        SettingEntry(YL, "Colors", "The other lines", "lyrics color dim inactive"),
        SettingEntry(YL, "Where the lines go", "Where the sung line sits", "lyrics position middle centre center top third scroll"),
        SettingEntry(YL, "Where the lines go", "Lines to come", "lyrics ahead next lines hide preview"),
        SettingEntry(YL, "Where the lines go", "The line on the player", "current lyric strip center centered left align player"),
        SettingEntry(YL, "Where the lines go", "Up to two lines", "current lyric strip wrap two lines verse cut off"),
        SettingEntry(YL, "Translations & romanized lines", "Translation look", "translation romanization size color italic above below"),
        SettingEntry(YL, "Background", "Behind the lyrics", "lyrics background dark blurred animated cover color gradient"),
        SettingEntry(YL, "Background", "Immersive mode background", "immersive background cover dark blurred color"),
        SettingEntry(YL, "Background", "Same look everywhere", "current lyric strip immersive match lyrics page"),
        SettingEntry(Y, "Sources", "Prefer karaoke & synced lyrics", "word by word time synced"),
        SettingEntry(Y, "Sources", "Uncensor lyrics", "explicit swear stars"),
        SettingEntry(D, "Mobile data", "Lyrics on mobile data", "cellular data saver metered load lyrics later open"),
        SettingEntry(Y, "Sources", "Order", "lyrics providers sources priority"),
        SettingEntry(Y, "Your music files", "Save lyrics into your music files", "flac mp3 tags embed"),
        SettingEntry(DV, "Android Auto", "Lyrics in the car", "android auto"),
        SettingEntry(DV, "Android Auto", "Style", "android auto car", needs = carLyrics),
        SettingEntry(DV, "Android Auto", "Keep the song title", "android auto car", needs = carLyrics),
        SettingEntry(DV, "Android Auto", "On this phone too", "lock screen notification", needs = carLyrics),
        SettingEntry(DV, "Android Auto", "Covers in a grid", "android auto car home tiles grid spotify layout tabs"),
        SettingEntry(DV, "Android Auto", "Buttons in the car", "android auto car buttons shuffle like radio"),
        SettingEntry(DV, "Other devices", "Speakers, TVs & browser", "prism connect cast remote handoff"),
        SettingEntry(DV, "Other devices", "Discord", "rich presence status"),
        // Downloads & storage
        SettingEntry(D, "Downloads", "Manage downloads", "offline delete"),
        SettingEntry(D, "Downloads", "Download on Wi-Fi only", "cellular mobile data"),
        SettingEntry(D, "Downloads", "Download liked songs automatically", "offline likes"),
        SettingEntry(D, "Smart Downloads", "Smart Downloads", "offline cache storage space"),
        SettingEntry(D, "Smart Downloads", "Space to use", "storage cache size gb"),
        SettingEntry(D, "Mobile data", "Smart Downloads on mobile data", "cellular", needs = smart),
        SettingEntry(D, "Smart Downloads", "Clear Smart Downloads", "cache delete free space"),
        // Home screen
        SettingEntry(H, "Home", "Customize Home", "sections order shortcuts quick picks speed dial"),
        SettingEntry(H, "Home", "Add new sections automatically", "shelves"),
        SettingEntry(H, "Home", "Continue line", "continue album resume greeting hide context rewind"),
        SettingEntry(H, "Replay card", "Replay card", "replay teaser look style design quiet ledger wash spectrum frame ring cover stripe bars aurora"),
        SettingEntry(H, "Home", "Podcasts button"),
        SettingEntry(H, "Home", "Customize button at the top"),
        SettingEntry(H, "Home", "Customize button at the bottom"),
        SettingEntry(R, "Region", "Content country", "region location gl"),
        // Prism Connect
        SettingEntry(C, "Speakers & TVs", "Play on a speaker or TV", "cast casting google cast google tv dlna upnp tv television speaker receiver avr sonos chromecast nest play on output"),
        SettingEntry(C, "Control from a browser", "Control from a browser", "web remote computer laptop tablet browser control prism connect"),
        SettingEntry(C, "Control from a browser", "Address", "web remote url link ip", needs = webRemote),
        SettingEntry(C, "Control from a browser", "Pairing code", "web remote pair code browser", shown = "Browser pairing code", needs = webRemote),
        SettingEntry(C, "Control from a browser", "Unpair all browsers", "web remote forget browsers", needs = webRemote),
        SettingEntry(C, "Handoff", "Available for handoff", "move playback another device tablet phone continue transfer handoff"),
        SettingEntry(C, "Handoff", "Pairing code", "handoff pair code", shown = "Handoff pairing code"),
        SettingEntry(R, "Made for you", "Your artists", "onboarding pick artists favourites starred recommendations"),
        SettingEntry(R, "Made for you", "New release notifications", "release radar new music alerts"),
        SettingEntry(R, "Made for you", "Make my mixes again", "daily mix refresh recommendations"),
        // Library & Replay
        SettingEntry(H, "Library", "Playlists as", "grid list view"),
        SettingEntry(H, "Library", "Albums as", "grid list view"),
        SettingEntry(H, "Library", "Artists as", "grid list view"),
        SettingEntry(H, "Library", "Covers across", "columns grid two three four"),
        SettingEntry(H, "Library", "Details under each one", "subtitles counts song count owner year hide"),
        SettingEntry(H, "Library", "Open Library on", "start tab"),
        SettingEntry(H, "Library", "Full-width playlist pictures"),
        SettingEntry(H, "Library", "Library tiles", "liked downloads local files replay podcasts listen together blend recognized new playlist import reorder hide shortcuts"),
        SettingEntry(R, "Replay", "Show time in hours", "stats minutes"),
        SettingEntry(R, "Replay", "Use theme colors", "colors stats"),
        SettingEntry(R, "Replay", "Rewind notification", "rewind wrapped year in review december notification"),
        SettingEntry(R, "Made for you", "Exclusions", "taste profile exclude artist playlist don't play block"),
        // About
        SettingEntry(B, "Backup", "Back up", "backup export save move new phone"),
        SettingEntry(B, "Backup", "Restore from a backup", "import move new phone"),
        SettingEntry(B, "About", "Prism", "version github"),
        SettingEntry(B, "About", "Your data stays here", "privacy"),
        SettingEntry(B, "About", "Check for updates", "update new version"),
        SettingEntry(B, "About", "Take the tour", "tutorial walkthrough help guide what's new how to"),
        SettingEntry(B, "About", "Open-source licenses", "license gpl"),
        SettingEntry(B, "Diagnostics", "Playback diagnostics", "stutter judder skip glitch lag bluetooth airpods log debug"),
        SettingEntry(B, "Diagnostics", "Share diagnostics", "log send report"),
        SettingEntry(B, "Diagnostics", "Clear diagnostics", "log reset"),
        SettingEntry(B, "Diagnostics", "Last crash", "crash closed unexpectedly error report stack trace"),
        SettingEntry(B, "Diagnostics", "Forget crashes", "crash delete clear"),
    )

    /**
     * Settings matching [query]: every word typed has to turn up in the title, group, page or keywords. Titles that start
     * with the query come first, then titles that contain it, then the rest.
     */
    fun search(query: String): List<SettingEntry> {
        val q = SearchRank.norm(query)
        if (q.isBlank()) return emptyList()
        val words = q.split(' ')
        fun hay(e: SettingEntry) = SearchRank.norm("${e.title} ${e.group} ${e.section?.title ?: "Settings"} ${e.keywords}")
        return entries.filter { e -> hay(e).let { h -> words.all { it in h } } }
            .sortedBy { e ->
                val t = SearchRank.norm(e.title)
                when { t.startsWith(q) -> 0; q in t -> 1; else -> 2 }
            }
    }
}
