package com.justvideo.app.ui.editor.preview

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.justvideo.app.data.model.KeyframeMath
import com.justvideo.app.data.model.OverlayKind
import com.justvideo.app.data.model.Timeline
import com.justvideo.app.media.effects.LayerRenderer

// =====================================================================
// LayerCanvas.kt
// Draws text, stickers and photo overlays ON TOP of the preview, so you
// can see them and move them with your finger:
// - tap a layer to select it
// - drag to move it, pinch to resize it
// (Video picture-in-picture is drawn by the player; here it just gets a
// selection frame so it can be moved too.) The export burns the same
// pictures in (LayerRenderer), so it looks the same.
// =====================================================================

@Composable
fun LayerCanvas(
    timeline: Timeline,
    playheadMs: Long,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    /** Live move: new x/y (canvas units) and a size multiplier since the last call. */
    onMove: (x: Float, y: Float, scaleMul: Float, live: Boolean) -> Unit,
    onMoveStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val latest by rememberUpdatedState(timeline)
    val latestSelected by rememberUpdatedState(selectedId)
    val latestPlayhead by rememberUpdatedState(playheadMs)

    // STEP 1: Pictures for each layer, made once per (content, size).
    val cache = remember { HashMap<String, ImageBitmap?>() }
    fun bitmapFor(key: String, make: () -> Bitmap?): ImageBitmap? = cache.getOrPut(key) { make()?.asImageBitmap() }

    // STEP 2: Everything visible now, with its on-screen box (top layer last).
    data class Shown(val id: String, val rect: Rect, val image: ImageBitmap?, val alpha: Float)

    fun visible(tl: Timeline, t: Long, w: Int, h: Int): List<Shown> {
        if (w == 0 || h == 0) return emptyList()
        val out = mutableListOf<Shown>()
        fun box(x: Float, y: Float, bw: Float, bh: Float): Rect {
            val cx = (x + 1f) / 2f * w
            val cy = (1f - y) / 2f * h
            return Rect(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
        }
        for (o in tl.overlays) if (t >= o.startMs && t < o.endMs) {
            val p = KeyframeMath.at(o.x, o.y, o.opacity, o.keyframes, t - o.startMs)
            val bw = o.scale * w * p.scaleMul
            val img = when (o.kind) {
                OverlayKind.STICKER -> bitmapFor("s:${o.source}:${bw.toInt() / 8}") { LayerRenderer.renderSticker(o.source, bw.toInt()) }
                OverlayKind.IMAGE -> bitmapFor("i:${o.source}:${(o.scale * w).toInt() / 32}") { LayerRenderer.loadImage(context, o.source, (o.scale * w).toInt()) }
                OverlayKind.VIDEO -> null
            }
            val aspect = when {
                img != null -> img.height.toFloat() / img.width
                o.width > 0 -> o.height.toFloat() / o.width
                else -> 16f / 9f
            }
            out += Shown(o.id, box(p.x, p.y, bw, bw * aspect), img, p.opacity)
        }
        for (tx in tl.texts) if (t >= tx.startMs && t < tx.endMs) {
            val p = KeyframeMath.at(tx.x, tx.y, 1f, tx.keyframes, t - tx.startMs)
            val img = bitmapFor("t:${tx.hashCode()}:$w") { LayerRenderer.renderText(tx, w) }
            val bw = (img?.width ?: 1) * p.scaleMul
            val bh = (img?.height ?: 1) * p.scaleMul
            out += Shown(tx.id, box(p.x, p.y, bw, bh), img, p.opacity)
        }
        return out
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            // STEP 3: Gestures: tap selects; one finger drags; two fingers resize.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val shown = visible(latest, latestPlayhead, size.width, size.height)
                    val hit = shown.lastOrNull { it.rect.inflate(24f).contains(down.position) }
                    // Not on a layer: let the tap reach the video (play/pause).
                    if (hit == null) return@awaitEachGesture
                    down.consume()
                    if (hit.id != latestSelected) onSelect(hit.id)
                    var center = hit.rect.center
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.none { it.pressed }) break
                        val pan = ev.calculatePan()
                        val zoom = ev.calculateZoom()
                        if (pan != Offset.Zero || zoom != 1f) {
                            if (!moved) { moved = true; onMoveStart() }
                            center += pan
                            val x = (center.x / size.width) * 2f - 1f
                            val y = 1f - (center.y / size.height) * 2f
                            onMove(x.coerceIn(-1.2f, 1.2f), y.coerceIn(-1.2f, 1.2f), zoom, true)
                        }
                        ev.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        // STEP 4: Draw the layers, and a dashed frame around the selected one.
        for (s in visible(timeline, playheadMs, size.width, size.height)) {
            s.image?.let { img ->
                drawImage(
                    img,
                    dstOffset = IntOffset(s.rect.left.toInt(), s.rect.top.toInt()),
                    dstSize = IntSize(s.rect.width.toInt().coerceAtLeast(1), s.rect.height.toInt().coerceAtLeast(1)),
                    alpha = s.alpha.coerceIn(0f, 1f),
                )
            }
            if (s.id == selectedId) {
                drawRect(
                    Color.White, topLeft = s.rect.topLeft, size = Size(s.rect.width, s.rect.height),
                    style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))),
                )
            }
        }
    }
}
