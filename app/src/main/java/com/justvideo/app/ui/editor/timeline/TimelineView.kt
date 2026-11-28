package com.justvideo.app.ui.editor.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.Timeline
import com.justvideo.app.media.thumbs.ThumbKey
import com.justvideo.app.media.thumbs.ThumbnailLoader
import com.justvideo.app.ui.theme.AudioGreen
import com.justvideo.app.ui.theme.PanelHigh
import com.justvideo.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.distinctUntilChanged

// =====================================================================
// TimelineView.kt
// The CapCut-style timeline: a time ruler, the video track (with frame
// thumbnails), the audio track, and a fixed playhead line in the middle.
//
// Gestures:
// - Drag sideways (with fling): scrub through the video.
// - Pinch: zoom in/out around the playhead.
// - Tap a clip: select it (tap again to deselect).
//
// Everything is drawn on ONE Canvas, and only what's visible is drawn.
// That keeps scrolling at 60 fps even for a 2-hour video.
// =====================================================================

// STEP 1: Row sizes.
private val RulerHeight = 24.dp
private val VideoTrackHeight = 56.dp
private val TrackGap = 8.dp
private val AudioTrackHeight = 36.dp
private val TotalHeight = RulerHeight + VideoTrackHeight + TrackGap + AudioTrackHeight + 8.dp

/** Default zoom: one 56dp thumbnail ≈ 2 seconds. */
private const val DEFAULT_PX_PER_MS = 0.07f

@Composable
fun TimelineView(
    timeline: Timeline,
    /** Where the player is (ms). The timeline follows it unless you're dragging. */
    playheadMs: Long,
    isPlaying: Boolean,
    selectedClipId: String?,
    thumbnails: ThumbnailLoader,
    onScrub: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onClipTapped: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // STEP 2: Timeline state.
    // - position: the time under the playhead (a Double, for smooth sub-ms scrolling)
    // - zoom: pixels per millisecond (saved across rotation)
    var position by remember { mutableDoubleStateOf(playheadMs.toDouble()) }
    var zoom by rememberSaveable { mutableFloatStateOf(DEFAULT_PX_PER_MS) }
    var pinching by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val durationMs = timeline.durationMs.toDouble()
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnScrubStart by rememberUpdatedState(onScrubStart)

    // STEP 3: Sideways drag + fling. Dragging left moves forward in time.
    // Only YOUR gestures go through here, so this is where we seek the player.
    val scrollState = rememberScrollableState { delta ->
        val before = position
        position = (position - delta / zoom).coerceIn(0.0, durationMs)
        currentOnScrub(position.toLong())
        // Report how much we actually moved, so a fling stops at the ends.
        ((before - position) * zoom).toFloat()
    }

    // STEP 4: Follow the player (playback, or the scrub bar) unless you are
    // touching the timeline right now.
    LaunchedEffect(playheadMs) {
        if (!scrollState.isScrollInProgress && !pinching) position = playheadMs.toDouble()
    }
    // Starting to drag while playing pauses playback, as in CapCut.
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.isScrollInProgress }.distinctUntilChanged().collect { scrolling ->
            if (scrolling) currentOnScrubStart()
        }
    }

    // STEP 5: Ask the thumbnail loader for exactly the frames on screen.
    // snapshotFlow re-runs this only when position/zoom/clips change, and
    // the bucketing keeps the list identical between small scrolls.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val tilePx = with(density) { VideoTrackHeight.toPx() }
    LaunchedEffect(thumbnails) {
        snapshotFlow {
            visibleThumbKeys(timeline, position, zoom, size.width.toFloat(), tilePx)
        }.distinctUntilChanged().collect { thumbnails.want(it) }
    }

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = TextSecondary, fontSize = 10.sp)
    val clipNameStyle = TextStyle(color = Color.White, fontSize = 11.sp)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(TotalHeight)
            .semantics { contentDescription = "Timeline" }
            .onSizeChanged { size = it }
            // STEP 6: Pinch zoom. It runs before the drag handler and takes
            // two-finger gestures for itself, so pinching never also scrolls.
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2) {
                            if (!pinching) {
                                pinching = true; currentOnScrubStart()
                            }
                            val z = event.calculateZoom()
                            // We always zoom around the playhead, so the time under it stays put.
                            if (z != 1f) zoom = TimelineScale.clampZoom(zoom * z)
                            event.changes.forEach { it.consume() }
                        }
                    }
                    pinching = false
                }
            }
            .scrollable(scrollState, Orientation.Horizontal)
            // STEP 7: Tap → find which clip is under your finger.
            .pointerInput(timeline) {
                detectTapGestures { tap ->
                    onClipTapped(hitTest(timeline, tap, position, zoom, size.width / 2f, density.density))
                }
            },
    ) {
        val cx = this.size.width / 2f
        val rulerH = RulerHeight.toPx()
        val videoTop = rulerH
        val videoH = VideoTrackHeight.toPx()
        val audioTop = videoTop + videoH + TrackGap.toPx()
        val audioH = AudioTrackHeight.toPx()

        drawRuler(position, zoom, cx, rulerH, textMeasurer, labelStyle)

        // STEP 8: Video track. Clips play back to back.
        var start = 0L
        for (clip in timeline.videoClips) {
            val x0 = TimelineScale.timeToX(start.toDouble(), position, cx, zoom)
            val x1 = TimelineScale.timeToX((start + clip.durationMs).toDouble(), position, cx, zoom)
            if (x1 >= 0 && x0 <= this.size.width) {
                drawVideoClip(clip, x0, x1, videoTop, videoH, start, zoom, cx, thumbnails, clip.id == selectedClipId)
            }
            start += clip.durationMs
        }

        // STEP 9: Audio track. Each clip sits at its own offset.
        for (clip in timeline.audioClips) {
            val x0 = TimelineScale.timeToX(clip.timelineOffsetMs.toDouble(), position, cx, zoom)
            val x1 = TimelineScale.timeToX((clip.timelineOffsetMs + clip.durationMs).toDouble(), position, cx, zoom)
            if (x1 < 0 || x0 > this.size.width) continue
            val selected = clip.id == selectedClipId
            drawRoundRect(AudioGreen.copy(alpha = 0.35f), Offset(x0 + 1, audioTop), Size(x1 - x0 - 2, audioH), CornerRadius(6.dp.toPx()))
            if (selected) drawRoundRect(Color.White, Offset(x0 + 1, audioTop), Size(x1 - x0 - 2, audioH), CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx()))
            val textX = maxOf(x0, 0f) + 6.dp.toPx()
            val room = (x1 - textX - 4.dp.toPx()).toInt()
            if (room > 20) {
                drawText(
                    textMeasurer, "♪ ${clip.displayName}", Offset(textX, audioTop + (audioH - 14.sp.toPx()) / 2),
                    clipNameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    size = Size(room.toFloat(), audioH),
                )
            }
        }

        // STEP 10: The playhead: a white line with a small triangle on top.
        drawLine(Color.White, Offset(cx, 0f), Offset(cx, this.size.height), strokeWidth = 2.dp.toPx())
        val tri = Path().apply {
            moveTo(cx - 6.dp.toPx(), 0f); lineTo(cx + 6.dp.toPx(), 0f); lineTo(cx, 8.dp.toPx()); close()
        }
        drawPath(tri, Color.White)
    }
}

// STEP 11: The ruler: tick marks with time labels, spaced so labels never overlap.
private fun DrawScope.drawRuler(
    position: Double, zoom: Float, cx: Float, h: Float,
    tm: androidx.compose.ui.text.TextMeasurer, style: TextStyle,
) {
    val step = TimelineScale.rulerStepMs(zoom, 72.dp.toPx())
    val firstMs = (TimelineScale.xToTime(0f, position, cx, zoom) / step).toLong().coerceAtLeast(0) * step
    var t = firstMs
    while (true) {
        val x = TimelineScale.timeToX(t.toDouble(), position, cx, zoom)
        if (x > size.width) break
        if (x >= -50) {
            drawLine(TextSecondary, Offset(x, h - 6.dp.toPx()), Offset(x, h), strokeWidth = 1.dp.toPx())
            drawText(tm, TimelineScale.rulerLabel(t, step), Offset(x + 3.dp.toPx(), 2.dp.toPx()), style)
            // Half-step minor tick.
            val xm = TimelineScale.timeToX(t + step / 2.0, position, cx, zoom)
            drawLine(TextSecondary.copy(alpha = 0.5f), Offset(xm, h - 3.dp.toPx()), Offset(xm, h), strokeWidth = 1.dp.toPx())
        }
        t += step
    }
}

// STEP 12: One video/photo clip: a rounded box filled with thumbnails.
private fun DrawScope.drawVideoClip(
    clip: Clip, x0: Float, x1: Float, top: Float, h: Float, timelineStartMs: Long,
    zoom: Float, cx: Float, thumbs: ThumbnailLoader, selected: Boolean,
) {
    val left = x0 + 1
    val width = x1 - x0 - 2
    if (width <= 0) return
    val radius = CornerRadius(6.dp.toPx())
    drawRoundRect(PanelHigh, Offset(left, top), Size(width, h), radius)

    // Thumbnails are square tiles (side = track height), drawn only where
    // they're on screen.
    clipRect(maxOf(left, 0f), top, minOf(left + width, size.width), top + h) {
        val firstTile = (((0f - left) / h).toInt()).coerceAtLeast(0)
        var i = firstTile
        while (true) {
            val tx = left + i * h
            if (tx > size.width || tx > left + width) break
            val key = thumbKeyFor(clip, i, h, zoom)
            val img = thumbs[key]
            if (img != null) {
                drawImage(
                    img,
                    dstOffset = androidx.compose.ui.unit.IntOffset(tx.toInt(), top.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(h.toInt(), h.toInt()),
                )
            }
            i++
        }
    }
    if (selected) drawRoundRect(Color.White, Offset(left, top), Size(width, h), radius, style = Stroke(2.dp.toPx()))
}

/** The thumbnail for tile number [i] of a clip (photos use one picture everywhere). */
private fun thumbKeyFor(clip: Clip, i: Int, tilePx: Float, zoom: Float): ThumbKey =
    if (clip.kind == MediaKind.IMAGE) {
        ThumbKey(clip.sourceUri, MediaKind.IMAGE, 0)
    } else {
        val sourceMs = clip.trimStartMs + ((i + 0.5f) * tilePx / zoom).toLong()
        ThumbKey(clip.sourceUri, MediaKind.VIDEO, TimelineScale.thumbnailBucketMs(sourceMs.coerceAtMost(clip.trimEndMs), zoom, tilePx))
    }

// STEP 13: Every thumbnail currently on screen, nearest the playhead first
// (so the middle fills in first).
private fun visibleThumbKeys(timeline: Timeline, position: Double, zoom: Float, width: Float, tilePx: Float): List<ThumbKey> {
    if (width <= 0f) return emptyList()
    val cx = width / 2f
    val out = ArrayList<Pair<Float, ThumbKey>>()
    var start = 0L
    for (clip in timeline.videoClips) {
        val x0 = TimelineScale.timeToX(start.toDouble(), position, cx, zoom) + 1
        val x1 = TimelineScale.timeToX((start + clip.durationMs).toDouble(), position, cx, zoom) - 1
        if (x1 >= 0 && x0 <= width) {
            var i = ((0f - x0) / tilePx).toInt().coerceAtLeast(0)
            while (true) {
                val tx = x0 + i * tilePx
                if (tx > width || tx > x1) break
                out += kotlin.math.abs(tx + tilePx / 2 - cx) to thumbKeyFor(clip, i, tilePx, zoom)
                i++
            }
        }
        start += clip.durationMs
    }
    return out.sortedBy { it.first }.map { it.second }.distinct()
}

// STEP 14: Which clip is at a tapped point (null = empty space → deselect).
private fun hitTest(timeline: Timeline, tap: Offset, position: Double, zoom: Float, cx: Float, density: Float): String? {
    val rulerH = RulerHeight.value * density
    val videoBottom = rulerH + VideoTrackHeight.value * density
    val audioTop = videoBottom + TrackGap.value * density
    val audioBottom = audioTop + AudioTrackHeight.value * density
    val t = TimelineScale.xToTime(tap.x, position, cx, zoom)
    return when (tap.y) {
        in rulerH..videoBottom -> {
            var start = 0L
            timeline.videoClips.firstOrNull { c ->
                val hit = t >= start && t < start + c.durationMs
                start += c.durationMs
                hit
            }?.id
        }
        in audioTop..audioBottom -> timeline.audioClips.firstOrNull {
            t >= it.timelineOffsetMs && t < it.timelineOffsetMs + it.durationMs
        }?.id
        else -> null
    }
}
