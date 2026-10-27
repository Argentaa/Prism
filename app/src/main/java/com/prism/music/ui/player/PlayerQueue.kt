package com.prism.music.ui.player

import androidx.compose.foundation.border
import com.prism.music.ui.theme.PrismIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes
import com.prism.music.playback.QueueEntry
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The queue, inside the player: now playing, what's next, then autoplay. */
@Composable
fun InlineQueue(onRevealPlayer: () -> Unit, onHidePlayer: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val pc = c.player
    val queue by pc.queue.collectAsState()
    val index by pc.currentIndex.collectAsState()
    val autoplayStart by c.queue.autoplayStart.collectAsState()
    val source by c.queue.source.collectAsState()
    val settings by c.settings.flow.collectAsState()
    // Songs already played sit above Now Playing (#68), a scroll up; the list opens at Now Playing.
    val playedIdx by pc.played.collectAsState()
    val played = playedIdx.takeLast(MAX_PLAYED).mapNotNull { queue.getOrNull(it) }
    val nowItem = if (played.isEmpty()) 0 else played.size + 1
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = nowItem)
    LaunchedEffect(index) { if (listState.firstVisibleItemIndex != nowItem) listState.animateScrollToItem(nowItem) }

    val now = queue.getOrNull(index)
    val autoplayFrom = if (autoplayStart > 0) autoplayStart else queue.size
    val upNext by pc.upNext.collectAsState()
    val shuffled by pc.shuffle.collectAsState()
    // In play order, so with shuffle on this is the shuffled order.
    val order = upNext.mapNotNull { queue.getOrNull(it) }
    val userQueued by c.queue.userQueued.collectAsState()
    // What the listener added (Play next, Add to queue) comes first, under its own heading.
    val queued = order.filter { it.song.id in userQueued }
    val upcoming = order.filter { it.index < autoplayFrom && it.song.id !in userQueued }
    val autoplay = order.filter { it.index >= autoplayFrom && it.song.id !in userQueued }

    val controlsOnScroll = remember { ControlsOnScroll(onHide = onHidePlayer, onReveal = onRevealPlayer) }

    // DJ mode: its switch, energy curve and how far the scans have got; each song's key and tempo as badges.
    val dj by c.dj.on.collectAsState()
    val djProgress by c.dj.progress.collectAsState()
    val djRevision by c.dj.revision.collectAsState()
    // The automatic transition's plan (or why the current song isn't blended), for the transition card.
    val transition by c.mixPreview.preview.collectAsState()
    val noBlendReason by c.mixPreview.noBlendReason.collectAsState()
    // Smart Shuffle's picks are marked ✦.
    val smartPicks by c.queue.smartPicks.collectAsState()
    val badge: (Song) -> String? = remember(dj, djRevision, smartPicks, transition) {
        { song ->
            val t = transition
            when {
                t != null && t.toId == song.id -> "→ ${t.lengthMs / 1000}s" + if (t.beats) " · beats" else ""
                dj -> if (!c.mixTempo.keyed(song.id)) "…" else djBadge(c.mixTempo.key(song.id), c.mixTempo.djBpm(song.id))
                song.id in smartPicks -> "✦ Pick"
                else -> null
            }
        }
    }

    var saving by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    if (saving) com.prism.music.ui.components.NewPlaylistDialog(onDismiss = { saving = false }, local = !settings.isLoggedIn) { name, privacy ->
        saving = false
        // Signed out, the queue becomes a Prism playlist on the phone (H7).
        if (!settings.isLoggedIn) {
            val list = (listOfNotNull(now) + order).map { it.song }.filter { !it.isLive && !it.isEpisode }.distinctBy { it.id }
            c.prismLibrary.create(name, list)
            android.widget.Toast.makeText(context, "Saved ${com.prism.music.data.model.songCount(list.size)} to $name", android.widget.Toast.LENGTH_SHORT).show()
            return@NewPlaylistDialog
        }
        val songs = (listOfNotNull(now) + order).map { it.song.id }.distinct().take(300)
        c.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val id = runCatching { c.ytm.createPlaylist(name, songs, privacy = privacy) }.getOrNull()
            android.widget.Toast.makeText(context, if (id != null) "Saved ${com.prism.music.data.model.songCount(songs.size)} to $name" else "Couldn't save the queue", android.widget.Toast.LENGTH_SHORT).show()
            if (id != null) c.library.refreshCollections()
        }
    }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Queue", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.weight(1f))
            if (order.size > 1) {
                QueueChip("DJ", selected = dj) { pc.setDjMode(!dj) }
                Spacer(Modifier.width(8.dp))
            }
            if (queue.size > 1) QueueChip("Save") { saving = true }
            if (order.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                QueueChip("Clear") { pc.clearUpcoming() }
            }
        }
        if (dj) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (flow in com.prism.music.playback.DjFlow.entries) {
                    QueueChip(flow.label, selected = settings.djFlow == flow) { c.settings.setDjFlow(flow) }
                    Spacer(Modifier.width(8.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            // Only an order it has: how far the scans are, songs waiting for Wi-Fi (with mobile data offered), or how many are in order.
            val (status, offerData) = com.prism.music.playback.DjStatus.of(djProgress)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.weight(1f, fill = false))
                if (offerData) {
                    Spacer(Modifier.width(8.dp))
                    // For this DJ set only (until Prism closes): "Measure songs on mobile data" stays as the listener set it.
                    QueueChip("Use mobile data") { c.dj.rescan(allowData = true) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().nestedScroll(controlsOnScroll), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (played.isNotEmpty()) {
                item(key = "h-played") { Heading("Played", Modifier.padding(top = 12.dp, bottom = 6.dp)) }
                items(played, key = { "played-" + it.uid }) { entry ->
                    QueueRow(
                        entry.song, isCurrent = false, onClick = { pc.skipTo(entry.index) }, onRemove = { pc.removeAt(entry.index) },
                        modifier = Modifier.alpha(0.6f),
                    )
                }
            }
            if (now != null) {
                item(key = "h-now") { Heading("Now Playing", Modifier.padding(top = 12.dp, bottom = 6.dp)) }
                item(key = "now-" + now.uid) { QueueRow(now.song, isCurrent = true, onClick = {}, onRemove = null, badge = badge(now.song)) }
            }
            // PR1 card: DJ mode blends regardless of the crossfade switch, so the card follows DJ too.
            if ((settings.mix || dj) && (transition != null || noBlendReason != null)) {
                item(key = "transition") { TransitionCard() }
            }
            if (queued.isNotEmpty()) {
                item(key = "h-queued") { Heading("Next in queue", Modifier.padding(top = 16.dp, bottom = 6.dp)) }
                queueSection(queued, reorder = !shuffled, badge = badge) { from, to -> pc.move(from, to) }
            }
            if (upcoming.isNotEmpty()) {
                item(key = "h-next") { Heading("Next from: ${source.title.ifBlank { now?.song?.album?.title ?: "Queue" }}", Modifier.padding(top = 16.dp, bottom = 6.dp)) }
                queueSection(upcoming, reorder = !shuffled, badge = badge) { from, to -> pc.move(from, to) }
            }
            if (settings.autoplay || autoplay.isNotEmpty()) {
                item(key = "h-auto") { AutoplayHeading(autoplay.isNotEmpty()) }
                queueSection(autoplay, reorder = !shuffled, badge = badge) { from, to -> pc.move(from, to) }
            }
        }
    }
}

/** How many played songs the queue lists above Now Playing (the latest ones). */
private const val MAX_PLAYED = 100

/** [reorder] is off while shuffled: dragging moves songs in the list, not in the shuffled order. */
private fun LazyListScope.queueSection(rows: List<QueueEntry>, reorder: Boolean = true, badge: (Song) -> String? = { null }, onMove: (Int, Int) -> Unit) {
    items(rows, key = { it.uid }) { entry ->
        val pc = LocalContainer.current.player
        val rowPx = with(LocalDensity.current) { 56.dp.toPx() }
        var drag by remember { mutableFloatStateOf(0f) }
        var position by remember(entry.index) { mutableStateOf(entry.index) }
        QueueRow(
            entry.song,
            isCurrent = false,
            onClick = { pc.skipTo(entry.index) },
            onRemove = { pc.removeAt(entry.index) },
            badge = badge(entry.song),
            modifier = Modifier.offset { IntOffset(0, drag.roundToInt()) },
            dragHandle = if (!reorder) Modifier else Modifier.pointerInput(entry.uid) {
                detectDragGestures(
                    onDragEnd = { drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { change, amount ->
                    change.consume()
                    drag += amount.y
                    // Trade places with a neighbour each time the row crosses one.
                    if (drag > rowPx * 0.6f) { onMove(position, position + 1); position += 1; drag -= rowPx }
                    else if (drag < -rowPx * 0.6f && position > 0) { onMove(position, position - 1); position -= 1; drag += rowPx }
                }
            },
        )
    }
}

/**
 * The automatic transition's card: what the next blend looks like (length, reason, countdown),
 * or why the current song isn't blended. Read-only: it steers nothing.
 */
@Composable
private fun TransitionCard() {
    val c = LocalContainer.current
    val pc = c.player
    val transition by c.mixPreview.preview.collectAsState()
    val noBlendReason by c.mixPreview.noBlendReason.collectAsState()
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(transition?.fromId, transition?.toId) {
        if (transition == null) return@LaunchedEffect
        while (true) {
            pos = pc.position
            delay(500)
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        val t = transition
        if (t != null) {
            val left = (t.startMs - pos) / 1000
            Text(
                if (left > 0) "Next blend · mix in ${left}s" else "Blending now",
                style = MaterialTheme.typography.titleMedium, color = Color.White,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${t.lengthMs / 1000}s · ${t.reason}",
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f),
            )
        } else {
            Text("No blend", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(Modifier.height(2.dp))
            Text(
                noBlendReason ?: "", style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A small action at the top of the queue ("Save", "Clear"; [selected] for a switch like "DJ"). */
@Composable
private fun QueueChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.Black else Color.White,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** A scanned song's key on the Camelot wheel and its tempo ("8A · 124"), as DJ mode sees them (what's clear of them). */
private fun djBadge(key: com.prism.music.playback.MusicKey?, bpmValue: Double?): String? {
    val k = key?.takeIf { it.sure }?.camelot
    val bpm = bpmValue?.roundToInt()?.toString()
    return listOfNotNull(k, bpm).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun Heading(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.fillMaxWidth())
}

@Composable
private fun AutoplayHeading(hasTracks: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(PrismIcons.Autoplay, null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text("Autoplay", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(
                if (hasTracks) "Similar music plays next" else "Similar music will play after your queue",
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun QueueRow(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    dragHandle: Modifier? = null,
    badge: String? = null,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dragHandle != null) {
            Icon(PrismIcons.DragHandle, "Drag to reorder", tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(20.dp).offset(x = (-4).dp).then(dragHandle))
            Spacer(Modifier.width(4.dp))
        }
        AsyncImage(hiRes(song.thumbnail, 226), null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.92f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artistText, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (badge != null) {
            Text(
                badge, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        if (isCurrent) {
            Icon(PrismIcons.Equalizer, "Now playing", tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        if (onRemove != null) Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(PrismIcons.Close, "Remove", tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
        }
    }
}
