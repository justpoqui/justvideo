package com.justvideo.app.ui.editor.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.Timeline
import com.justvideo.app.editor.Snapping
import com.justvideo.app.editor.TimelineOps
import com.justvideo.app.media.thumbs.ThumbKey
import com.justvideo.app.media.thumbs.ThumbnailLoader
import com.justvideo.app.ui.theme.AudioGreen
import com.justvideo.app.ui.theme.Ink
import com.justvideo.app.ui.theme.PanelHigh
import com.justvideo.app.ui.theme.Teal
import com.justvideo.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

// =====================================================================
// TimelineView.kt
// The CapCut-style timeline: a time ruler, the video track (with frame
// thumbnails), the audio track, and a fixed playhead line in the middle.
//
// Gestures:
// - Drag sideways (with fling): scrub through the video.
// - Pinch: zoom in/out around the playhead.
// - Tap a clip: select it (tap again, or tap empty space, to deselect).
// - Drag the white handles of the selected clip: trim it.
// - Long-press a video clip, then drag: reorder (clips shrink to squares).
// - Long-press an audio clip, then drag: slide it along the timeline.
// Edges snap to the playhead and to other clips' edges, with a small
// vibration.
//
// Everything is drawn on ONE Canvas, and only what's visible is drawn.
// That keeps scrolling at 60 fps even for a 2-hour video.
// =====================================================================

// STEP 1: Sizes.
private val RulerHeight = 24.dp
private val VideoTrackHeight = 56.dp
private val TrackGap = 8.dp
private val AudioTrackHeight = 36.dp
private val LayerLaneHeight = 24.dp // text / sticker / overlay bars
private val LayerGap = 4.dp
private val TotalHeight = RulerHeight + LayerLaneHeight + LayerGap + VideoTrackHeight + TrackGap + AudioTrackHeight + 8.dp
private val HandleWidth = 14.dp
private val HandleTouch = 24.dp   // how far from an edge still counts as grabbing the handle
private val SnapDistance = 10.dp  // how close an edge must get before it snaps
private val ReorderGap = 4.dp

/** Default zoom: one 56dp thumbnail ≈ 2 seconds. */
private const val DEFAULT_PX_PER_MS = 0.07f

/** What a finger is doing to a clip right now (drawn live, saved when you let go). */
private sealed interface EditDrag {
    val clipId: String

    /** Trimming. leftShiftMs (video, left edge only): how far the left edge moved, used to draw it under your finger. */
    data class Trim(override val clipId: String, val leftEdge: Boolean, val leftShiftMs: Long) : EditDrag

    /** Reordering video: the finger's x, where slot 0 starts, and the slot it would drop into. */
    data class Reorder(override val clipId: String, val fingerX: Float, val baseX: Float, val target: Int) : EditDrag

    data class MoveAudio(override val clipId: String) : EditDrag
}

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
    /** A trim/reorder/move finished: save this new timeline. */
    onEditCommit: (Timeline) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // STEP 2: Timeline state.
    // - position: the time under the playhead (a Double, for smooth scrolling)
    // - zoom: pixels per millisecond (saved across rotation)
    // - preview/drag: the edit you're making right now, before you let go
    var position by remember { mutableDoubleStateOf(playheadMs.toDouble()) }
    var zoom by rememberSaveable { mutableFloatStateOf(DEFAULT_PX_PER_MS) }
    var pinching by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var preview by remember { mutableStateOf<Timeline?>(null) }
    var drag by remember { mutableStateOf<EditDrag?>(null) }
    val shown = preview ?: timeline

    val latestTimeline by rememberUpdatedState(timeline)
    val latestSelected by rememberUpdatedState(selectedClipId)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnScrubStart by rememberUpdatedState(onScrubStart)
    val currentOnCommit by rememberUpdatedState(onEditCommit)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val tilePx = with(density) { VideoTrackHeight.toPx() }

    // STEP 3: Sideways drag + fling. Dragging left moves forward in time.
    // Only YOUR gestures go through here, so this is where we seek the player.
    val scrollState = rememberScrollableState { delta ->
        val before = position
        position = (position - delta / zoom).coerceIn(0.0, latestTimeline.durationMs.toDouble())
        currentOnScrub(position.toLong())
        ((before - position) * zoom).toFloat()
    }

    // STEP 4: Follow the player (playback, or the scrub bar) unless you are
    // touching the timeline right now.
    LaunchedEffect(playheadMs) {
        if (!scrollState.isScrollInProgress && !pinching && drag == null) position = playheadMs.toDouble()
    }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.isScrollInProgress }.distinctUntilChanged().collect { if (it) currentOnScrubStart() }
    }

    // STEP 5: Ask the thumbnail loader for exactly the frames on screen.
    LaunchedEffect(thumbnails) {
        snapshotFlow {
            val tl = preview ?: latestTimeline
            visibleThumbKeys(tl, position, zoom, size.width.toFloat(), tilePx) +
                (if (drag is EditDrag.Reorder) tl.videoClips.map { thumbKeyFor(it, 0, tilePx, zoom) } else emptyList())
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
            // STEP 6: Pinch zoom. It runs first and takes two-finger gestures
            // for itself, so pinching never also scrolls.
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2 && drag == null) {
                            if (!pinching) {
                                pinching = true; currentOnScrubStart()
                            }
                            // We always zoom around the playhead, so the time under it stays put.
                            val z = event.calculateZoom()
                            if (z != 1f) zoom = TimelineScale.clampZoom(zoom * z)
                            event.changes.forEach { it.consume() }
                        }
                    }
                    pinching = false
                }
            }
            // STEP 7: Editing gestures (trim handles, long-press reorder/move).
            // They also run before scrolling, and only claim the touch when
            // it's on a handle or turns into a long-press. Otherwise the touch
            // falls through to scroll or tap.
            .pointerInput(Unit) {
                val d = density.density
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val tl = latestTimeline
                    val cx = size.width / 2f
                    val geo = Geometry(d)

                    // 7a-0: A selected text/sticker bar: drag its edges to change
                    // when it starts/ends.
                    val layerHandle = layerHandleAt(tl, latestSelected, down.position, position, zoom, cx, geo)
                    if (layerHandle != null) {
                        down.consume(); currentOnScrubStart()
                        val (box, leftEdge) = layerHandle
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            ev.changes.forEach { it.consume() }
                            if (!ch.pressed) break
                            val deltaMs = ((ch.position.x - down.position.x) / zoom).toLong()
                            val edge = if (leftEdge) box.startMs + deltaMs else box.startMs + box.durationMs + deltaMs
                            val snapped = Snapping.snap(edge, TimelineOps.edgeTimes(tl) + position.toLong(), zoom, SnapDistance.value * d).timeMs
                            preview = if (leftEdge) TimelineOps.retimeLayer(tl, box.id, snapped, box.startMs + box.durationMs - snapped)
                            else TimelineOps.retimeLayer(tl, box.id, box.startMs, snapped - box.startMs)
                            drag = EditDrag.MoveAudio(box.id)
                        }
                        preview?.let(currentOnCommit); preview = null; drag = null
                        return@awaitEachGesture
                    }

                    // 7a: Grabbed a trim handle of the selected clip?
                    val handle = handleAt(tl, latestSelected, down.position, position, zoom, cx, geo)
                    if (handle != null) {
                        down.consume()
                        currentOnScrubStart()
                        val (clip, leftEdge) = handle
                        val clipStart = clipTimelineStart(tl, clip)
                        var lastSnap: Long? = null
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            ev.changes.forEach { it.consume() }
                            if (!ch.pressed) break
                            val deltaMs = ((ch.position.x - down.position.x) / zoom).toLong()
                            val snapPoints = snapPointsFor(tl, clip, position)
                            val result: Timeline
                            val shift: Long
                            if (leftEdge) {
                                // Edge time on screen = clip start + how far the edge moved.
                                val edgeT = clipStart + deltaMs
                                val s = Snapping.snap(edgeT, snapPoints, zoom, SnapDistance.value * d)
                                if (s.snappedTo != null && s.snappedTo != lastSnap) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastSnap = s.snappedTo
                                // Timeline ms × speed = source ms.
                                result = TimelineOps.trimStart(tl, clip.id, clip.trimStartMs + ((s.timeMs - clipStart) * clip.speed).toLong())
                                val newClip = TimelineOps.findClip(result, clip.id)!!
                                shift = if (clip.kind == MediaKind.AUDIO) 0 else ((newClip.trimStartMs - clip.trimStartMs) / clip.speed).toLong()
                                // Show the frame at the new in-point.
                                currentOnScrub((clipStart + shift).coerceIn(clipStart, clipStart + clip.durationMs - 1))
                            } else {
                                val edgeT = clipStart + clip.durationMs + deltaMs
                                val s = Snapping.snap(edgeT, snapPoints, zoom, SnapDistance.value * d)
                                if (s.snappedTo != null && s.snappedTo != lastSnap) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastSnap = s.snappedTo
                                result = TimelineOps.trimEnd(tl, clip.id, clip.trimStartMs + ((s.timeMs - clipStart) * clip.speed).toLong())
                                shift = 0
                                val newClip = TimelineOps.findClip(result, clip.id)!!
                                currentOnScrub((clipStart + newClip.durationMs).coerceIn(clipStart, clipStart + clip.durationMs - 1))
                            }
                            preview = result
                            drag = EditDrag.Trim(clip.id, leftEdge, shift)
                        }
                        preview?.let(currentOnCommit)
                        preview = null
                        drag = null
                        return@awaitEachGesture
                    }

                    // 7b: Wait to see if this becomes a long-press (finger
                    // held still). Moving or lifting early = a normal scroll/tap.
                    val pressedLayer = layerAt(tl, down.position, position, zoom, cx, geo)
                    val pressedClip = clipAt(tl, down.position, position, zoom, cx, geo)
                    if (pressedLayer == null && pressedClip == null) return@awaitEachGesture
                    var cancelled = false
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                            if (ch == null || !ch.pressed || ev.changes.count { it.pressed } > 1 ||
                                (ch.position - down.position).getDistance() > viewConfiguration.touchSlop
                            ) {
                                cancelled = true; break
                            }
                        }
                    }
                    if (cancelled) return@awaitEachGesture

                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentOnScrubStart()

                    if (pressedLayer != null) {
                        // 7b-1: Slide a text/sticker bar along the timeline.
                        drag = EditDrag.MoveAudio(pressedLayer.id)
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            ev.changes.forEach { it.consume() }
                            if (!ch.pressed) break
                            val raw = pressedLayer.startMs + ((ch.position.x - down.position.x) / zoom).toLong()
                            val start = Snapping.snap(raw, TimelineOps.edgeTimes(tl) + position.toLong(), zoom, SnapDistance.value * d).timeMs
                            preview = TimelineOps.retimeLayer(tl, pressedLayer.id, start, pressedLayer.durationMs)
                        }
                        preview?.let(currentOnCommit); preview = null; drag = null
                        return@awaitEachGesture
                    }
                    if (pressedClip == null) return@awaitEachGesture

                    if (pressedClip.kind != MediaKind.AUDIO) {
                        // 7c: Reorder video clips.
                        val slot = geo.trackH + geo.reorderGap
                        val origin = tl.videoClips.indexOfFirst { it.id == pressedClip.id }
                        var baseX = down.position.x - origin * slot - geo.trackH / 2
                        drag = EditDrag.Reorder(pressedClip.id, down.position.x, baseX, origin)
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            ev.changes.forEach { it.consume() }
                            if (!ch.pressed) break
                            val x = ch.position.x
                            // Near the screen edge: slide the row so you can reach far clips.
                            if (x < 48 * d) baseX += 12 * d else if (x > size.width - 48 * d) baseX -= 12 * d
                            val target = ((x - baseX - geo.trackH / 2) / slot).roundToInt().coerceIn(0, tl.videoClips.size - 1)
                            val prev = drag as? EditDrag.Reorder
                            if (prev != null && prev.target != target) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            drag = EditDrag.Reorder(pressedClip.id, x, baseX, target)
                        }
                        val target = (drag as? EditDrag.Reorder)?.target ?: origin
                        if (target != origin) currentOnCommit(TimelineOps.moveVideo(tl, pressedClip.id, target))
                        drag = null
                    } else {
                        // 7d: Slide an audio clip, snapping its start or end.
                        drag = EditDrag.MoveAudio(pressedClip.id)
                        var lastSnap: Long? = null
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            ev.changes.forEach { it.consume() }
                            if (!ch.pressed) break
                            val raw = pressedClip.timelineOffsetMs + ((ch.position.x - down.position.x) / zoom).toLong()
                            val points = snapPointsFor(tl, pressedClip, position)
                            val sStart = Snapping.snap(raw, points, zoom, SnapDistance.value * d)
                            val sEnd = Snapping.snap(raw + pressedClip.durationMs, points, zoom, SnapDistance.value * d)
                            val offset = when {
                                sStart.snappedTo != null -> sStart.timeMs
                                sEnd.snappedTo != null -> sEnd.timeMs - pressedClip.durationMs
                                else -> raw
                            }
                            val snapped = sStart.snappedTo ?: sEnd.snappedTo
                            if (snapped != null && snapped != lastSnap) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            lastSnap = snapped
                            preview = TimelineOps.moveAudio(tl, pressedClip.id, offset)
                        }
                        preview?.let(currentOnCommit)
                        preview = null
                        drag = null
                    }
                }
            }
            .scrollable(scrollState, Orientation.Horizontal, enabled = drag == null)
            // STEP 8: Tap → find which clip is under your finger.
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val g = Geometry(density.density)
                    val cxx = size.width / 2f
                    onClipTapped(
                        layerAt(latestTimeline, tap, position, zoom, cxx, g)?.id
                            ?: clipAt(latestTimeline, tap, position, zoom, cxx, g)?.id
                    )
                }
            },
    ) {
        val cx = this.size.width / 2f
        val geo = Geometry(density.density)
        val videoTop = geo.videoTop
        val audioTop = geo.audioTop

        drawRuler(position, zoom, cx, geo.rulerH, textMeasurer, labelStyle)

        // STEP 8b: Layers lane: texts (violet) and stickers/overlays (orange).
        for (b in layerBoxes(shown)) {
            val x0 = TimelineScale.timeToX(b.startMs.toDouble(), position, cx, zoom)
            val x1 = TimelineScale.timeToX((b.startMs + b.durationMs).toDouble(), position, cx, zoom)
            if (x1 < 0 || x0 > this.size.width) continue
            drawRoundRect(b.color.copy(alpha = 0.55f), Offset(x0 + 1, geo.layerTop), Size(x1 - x0 - 2, geo.layerH), CornerRadius(5.dp.toPx()))
            val room = (x1 - maxOf(x0, 0f) - 8.dp.toPx()).toInt()
            if (room > 16) {
                drawText(textMeasurer, b.label, Offset(maxOf(x0, 0f) + 4.dp.toPx(), geo.layerTop + 4.dp.toPx()), clipNameStyle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, size = Size(room.toFloat(), geo.layerH))
            }
            if (b.id == selectedClipId) drawSelection(x0, x1, geo.layerTop, geo.layerH)
        }

        // STEP 9: Video track (or the reorder row while reordering).
        val reorder = drag as? EditDrag.Reorder
        if (reorder != null) {
            drawReorderRow(shown, reorder, videoTop, geo, thumbnails, tilePx, zoom)
        } else {
            val trim = drag as? EditDrag.Trim
            var start = 0L
            var shiftPx = 0f
            for (clip in shown.videoClips) {
                if (trim != null && trim.clipId == clip.id && trim.leftEdge) shiftPx = trim.leftShiftMs * zoom
                val x0 = TimelineScale.timeToX(start.toDouble(), position, cx, zoom) + shiftPx
                val x1 = TimelineScale.timeToX((start + clip.durationMs).toDouble(), position, cx, zoom) + shiftPx
                if (x1 >= 0 && x0 <= this.size.width) {
                    drawVideoClip(clip, x0, x1, videoTop, geo.trackH, zoom, thumbnails, clip.id == selectedClipId)
                }
                start += clip.durationMs
            }
        }

        // STEP 10: Audio track. Each clip sits at its own offset.
        for (clip in shown.audioClips) {
            val x0 = TimelineScale.timeToX(clip.timelineOffsetMs.toDouble(), position, cx, zoom)
            val x1 = TimelineScale.timeToX((clip.timelineOffsetMs + clip.durationMs).toDouble(), position, cx, zoom)
            if (x1 < 0 || x0 > this.size.width) continue
            val selected = clip.id == selectedClipId
            val lifted = (drag as? EditDrag.MoveAudio)?.clipId == clip.id
            drawRoundRect(
                AudioGreen.copy(alpha = if (lifted) 0.6f else 0.35f),
                Offset(x0 + 1, audioTop), Size(x1 - x0 - 2, geo.audioH), CornerRadius(6.dp.toPx()),
            )
            val textX = maxOf(x0, 0f) + if (selected) HandleWidth.toPx() + 4.dp.toPx() else 6.dp.toPx()
            val room = (x1 - textX - 4.dp.toPx() - if (selected) HandleWidth.toPx() else 0f).toInt()
            if (room > 20) {
                drawText(
                    textMeasurer, "♪ ${clip.displayName}", Offset(textX, audioTop + (geo.audioH - 14.sp.toPx()) / 2),
                    clipNameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    size = Size(room.toFloat(), geo.audioH),
                )
            }
            if (selected) drawSelection(x0, x1, audioTop, geo.audioH)
        }

        // STEP 11: The playhead: a white line with a small triangle on top.
        drawLine(Color.White, Offset(cx, 0f), Offset(cx, this.size.height), strokeWidth = 2.dp.toPx())
        val tri = Path().apply {
            moveTo(cx - 6.dp.toPx(), 0f); lineTo(cx + 6.dp.toPx(), 0f); lineTo(cx, 8.dp.toPx()); close()
        }
        drawPath(tri, Color.White)
    }
}

// STEP 12: Row positions in pixels (computed from dp with the screen density).
private class Geometry(d: Float) {
    val rulerH = RulerHeight.value * d
    val layerTop = rulerH
    val layerH = LayerLaneHeight.value * d
    val videoTop = rulerH + layerH + LayerGap.value * d
    val trackH = VideoTrackHeight.value * d
    val audioTop = videoTop + trackH + TrackGap.value * d
    val audioH = AudioTrackHeight.value * d
    val handleTouch = HandleTouch.value * d
    val reorderGap = ReorderGap.value * d
}

// STEP 13: Snap targets for an edge of [clip]: the playhead, time 0, and
// the edges of clips on the OTHER track (and other audio clips). Edges of
// later video clips move while you trim, so they aren't useful targets.
private fun snapPointsFor(tl: Timeline, clip: Clip, playheadMs: Double): List<Long> {
    val points = mutableListOf(0L, playheadMs.toLong())
    if (clip.kind == MediaKind.AUDIO) {
        points += TimelineOps.edgeTimes(tl.copy(audioClips = emptyList()))
        tl.audioClips.filter { it.id != clip.id }.forEach { points += listOf(it.timelineOffsetMs, it.timelineOffsetMs + it.durationMs) }
    } else {
        tl.audioClips.forEach { points += listOf(it.timelineOffsetMs, it.timelineOffsetMs + it.durationMs) }
    }
    return points
}

private fun clipTimelineStart(tl: Timeline, clip: Clip): Long =
    if (clip.kind == MediaKind.AUDIO) clip.timelineOffsetMs
    else TimelineOps.videoStarts(tl)[tl.videoClips.indexOfFirst { it.id == clip.id }]

// STEP 14: Is the finger on a trim handle of the selected clip? Returns
// (clip, isLeftEdge).
private fun handleAt(
    tl: Timeline, selectedId: String?, p: Offset, position: Double, zoom: Float, cx: Float, g: Geometry,
): Pair<Clip, Boolean>? {
    val clip = TimelineOps.findClip(tl, selectedId) ?: return null
    val isAudio = clip.kind == MediaKind.AUDIO
    val top = if (isAudio) g.audioTop else g.videoTop
    val h = if (isAudio) g.audioH else g.trackH
    if (p.y < top - 8 || p.y > top + h + 8) return null
    val start = clipTimelineStart(tl, clip)
    val x0 = TimelineScale.timeToX(start.toDouble(), position, cx, zoom)
    val x1 = TimelineScale.timeToX((start + clip.durationMs).toDouble(), position, cx, zoom)
    val dl = abs(p.x - x0)
    val dr = abs(p.x - x1)
    return when {
        dl <= g.handleTouch && dl <= dr -> clip to true
        dr <= g.handleTouch -> clip to false
        else -> null
    }
}

// STEP 14b: Text/sticker/overlay bars in the layers lane.
private class LayerBox(val id: String, val startMs: Long, val durationMs: Long, val label: String, val color: Color)

private fun layerBoxes(tl: Timeline): List<LayerBox> =
    tl.overlays.map { o ->
        val label = when (o.kind) {
            com.justvideo.app.data.model.OverlayKind.STICKER -> o.source
            com.justvideo.app.data.model.OverlayKind.IMAGE -> "🖼 Photo"
            com.justvideo.app.data.model.OverlayKind.VIDEO -> "▶ Video overlay"
        }
        LayerBox(o.id, o.startMs, o.durationMs, label, Color(0xFFFF8A00))
    } + tl.texts.map { LayerBox(it.id, it.startMs, it.durationMs, "T  " + it.text.lineSequence().first(), Color(0xFF7B5CFF)) }

private fun layerAt(tl: Timeline, p: Offset, position: Double, zoom: Float, cx: Float, g: Geometry): LayerBox? {
    if (p.y !in g.layerTop..(g.layerTop + g.layerH)) return null
    val t = TimelineScale.xToTime(p.x, position, cx, zoom)
    return layerBoxes(tl).lastOrNull { t >= it.startMs && t < it.startMs + it.durationMs }
}

private fun layerHandleAt(tl: Timeline, selectedId: String?, p: Offset, position: Double, zoom: Float, cx: Float, g: Geometry): Pair<LayerBox, Boolean>? {
    val box = layerBoxes(tl).firstOrNull { it.id == selectedId } ?: return null
    if (p.y < g.layerTop - 8 || p.y > g.layerTop + g.layerH + 8) return null
    val x0 = TimelineScale.timeToX(box.startMs.toDouble(), position, cx, zoom)
    val x1 = TimelineScale.timeToX((box.startMs + box.durationMs).toDouble(), position, cx, zoom)
    val dl = abs(p.x - x0)
    val dr = abs(p.x - x1)
    return when {
        dl <= g.handleTouch && dl <= dr -> box to true
        dr <= g.handleTouch -> box to false
        else -> null
    }
}

// STEP 15: Which clip is at a point (null = empty space).
private fun clipAt(tl: Timeline, p: Offset, position: Double, zoom: Float, cx: Float, g: Geometry): Clip? {
    val t = TimelineScale.xToTime(p.x, position, cx, zoom)
    return when (p.y) {
        in g.videoTop..(g.videoTop + g.trackH) ->
            TimelineOps.videoClipAt(tl, t.toLong())?.let { (i, _) -> tl.videoClips[i] }
        in g.audioTop..(g.audioTop + g.audioH) -> tl.audioClips.firstOrNull {
            t >= it.timelineOffsetMs && t < it.timelineOffsetMs + it.durationMs
        }
        else -> null
    }
}

// STEP 16: The ruler: tick marks with time labels, spaced so labels never overlap.
private fun DrawScope.drawRuler(position: Double, zoom: Float, cx: Float, h: Float, tm: TextMeasurer, style: TextStyle) {
    val step = TimelineScale.rulerStepMs(zoom, 72.dp.toPx())
    val firstMs = (TimelineScale.xToTime(0f, position, cx, zoom) / step).toLong().coerceAtLeast(0) * step
    var t = firstMs
    while (true) {
        val x = TimelineScale.timeToX(t.toDouble(), position, cx, zoom)
        if (x > size.width) break
        if (x >= -50) {
            drawLine(TextSecondary, Offset(x, h - 6.dp.toPx()), Offset(x, h), strokeWidth = 1.dp.toPx())
            drawText(tm, TimelineScale.rulerLabel(t, step), Offset(x + 3.dp.toPx(), 2.dp.toPx()), style)
            val xm = TimelineScale.timeToX(t + step / 2.0, position, cx, zoom)
            drawLine(TextSecondary.copy(alpha = 0.5f), Offset(xm, h - 3.dp.toPx()), Offset(xm, h), strokeWidth = 1.dp.toPx())
        }
        t += step
    }
}

// STEP 17: One video/photo clip: a rounded box filled with thumbnails.
private fun DrawScope.drawVideoClip(
    clip: Clip, x0: Float, x1: Float, top: Float, h: Float, zoom: Float, thumbs: ThumbnailLoader, selected: Boolean,
) {
    val left = x0 + 1
    val width = x1 - x0 - 2
    if (width <= 0) return
    drawRoundRect(PanelHigh, Offset(left, top), Size(width, h), CornerRadius(6.dp.toPx()))
    clipRect(maxOf(left, 0f), top, minOf(left + width, size.width), top + h) {
        var i = ((0f - left) / h).toInt().coerceAtLeast(0)
        while (true) {
            val tx = left + i * h
            if (tx > size.width || tx > left + width) break
            thumbs[thumbKeyFor(clip, i, h, zoom)]?.let { img ->
                drawImage(img, dstOffset = IntOffset(tx.toInt(), top.toInt()), dstSize = IntSize(h.toInt(), h.toInt()))
            }
            i++
        }
    }
    if (selected) drawSelection(x0, x1, top, h)
}

// STEP 18: Selection: white outline plus two grab handles for trimming.
private fun DrawScope.drawSelection(x0: Float, x1: Float, top: Float, h: Float) {
    val hw = HandleWidth.toPx()
    val r = CornerRadius(6.dp.toPx())
    drawRoundRect(Color.White, Offset(x0 + 1, top), Size(x1 - x0 - 2, h), r, style = Stroke(2.dp.toPx()))
    for (x in listOf(x0 + 1, x1 - 1 - hw)) {
        drawRoundRect(Color.White, Offset(x, top), Size(hw, h), r)
        // Grip line in the middle of the handle.
        drawLine(Ink, Offset(x + hw / 2, top + h * 0.3f), Offset(x + hw / 2, top + h * 0.7f), strokeWidth = 2.dp.toPx())
    }
}

// STEP 19: Reorder mode: every clip becomes a square tile. The one you're
// holding follows your finger, and the others open a gap where it would land.
private fun DrawScope.drawReorderRow(
    tl: Timeline, r: EditDrag.Reorder, top: Float, g: Geometry, thumbs: ThumbnailLoader, tilePx: Float, zoom: Float,
) {
    val s = g.trackH
    val slot = s + g.reorderGap
    val others = tl.videoClips.filter { it.id != r.clipId }
    others.forEachIndexed { j, clip ->
        val index = if (j < r.target) j else j + 1
        drawTile(clip, r.baseX + index * slot, top, s, thumbs, tilePx, zoom, lifted = false)
    }
    // Drop marker + the lifted tile under your finger.
    val markerX = r.baseX + r.target * slot - g.reorderGap / 2
    drawLine(Teal, Offset(markerX, top - 4.dp.toPx()), Offset(markerX, top + s + 4.dp.toPx()), strokeWidth = 3.dp.toPx())
    tl.videoClips.firstOrNull { it.id == r.clipId }?.let {
        drawTile(it, r.fingerX - s / 2, top - 6.dp.toPx(), s, thumbs, tilePx, zoom, lifted = true)
    }
}

private fun DrawScope.drawTile(
    clip: Clip, x: Float, top: Float, s: Float, thumbs: ThumbnailLoader, tilePx: Float, zoom: Float, lifted: Boolean,
) {
    if (x + s < 0 || x > size.width) return
    drawRoundRect(PanelHigh, Offset(x, top), Size(s, s), CornerRadius(8.dp.toPx()))
    thumbs[thumbKeyFor(clip, 0, tilePx, zoom)]?.let {
        drawImage(it, dstOffset = IntOffset(x.toInt(), top.toInt()), dstSize = IntSize(s.toInt(), s.toInt()))
    }
    if (lifted) drawRoundRect(Teal, Offset(x, top), Size(s, s), CornerRadius(8.dp.toPx()), style = Stroke(3.dp.toPx()))
}

/** The thumbnail for tile number [i] of a clip (photos use one picture everywhere). */
private fun thumbKeyFor(clip: Clip, i: Int, tilePx: Float, zoom: Float): ThumbKey =
    if (clip.kind == MediaKind.IMAGE) {
        ThumbKey(clip.sourceUri, MediaKind.IMAGE, 0)
    } else {
        val sourceMs = clip.sourceTimeAt(((i + 0.5f) * tilePx / zoom).toLong())
        ThumbKey(clip.sourceUri, MediaKind.VIDEO, TimelineScale.thumbnailBucketMs(sourceMs.coerceAtMost(clip.trimEndMs), zoom, tilePx))
    }

// STEP 20: Every thumbnail currently on screen, nearest the playhead first
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
                out += abs(tx + tilePx / 2 - cx) to thumbKeyFor(clip, i, tilePx, zoom)
                i++
            }
        }
        start += clip.durationMs
    }
    return out.sortedBy { it.first }.map { it.second }.distinct()
}
