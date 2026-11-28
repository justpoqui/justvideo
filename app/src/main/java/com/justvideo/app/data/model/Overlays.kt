package com.justvideo.app.data.model

import kotlinx.serialization.Serializable

// =====================================================================
// Overlays.kt
// Things drawn ON TOP of the main video, each with its own timing:
// text, stickers (emoji), and picture-in-picture (a photo or video).
// Positions use the canvas centre as (0, 0), with -1..1 to each edge, so
// they look the same at any export size.
// =====================================================================

// STEP 1: Text overlay.
@Serializable
enum class TextFont(val label: String) { BOLD("Bold"), CLASSIC("Classic"), SERIF("Serif"), MONO("Typewriter"), HANDWRITTEN("Handwritten") }

@Serializable
data class TextItem(
    val id: String,
    val text: String,
    val startMs: Long,
    val durationMs: Long = 3_000,
    val x: Float = 0f,
    val y: Float = 0f,
    /** Text height as a fraction of the canvas width (0.06 ≈ a TikTok title). */
    val size: Float = 0.07f,
    /** ARGB colour. */
    val color: Int = 0xFFFFFFFF.toInt(),
    val font: TextFont = TextFont.BOLD,
    /** Draw a rounded box behind the text. */
    val background: Boolean = false,
    /** Phase 4 keyframes: animate position/scale/opacity over time. */
    val keyframes: List<Keyframe> = emptyList(),
) {
    val endMs get() = startMs + durationMs
}

// STEP 2: Sticker (an emoji drawn big) or picture-in-picture (photo/video).
@Serializable
enum class OverlayKind { STICKER, IMAGE, VIDEO }

@Serializable
data class OverlayItem(
    val id: String,
    val kind: OverlayKind,
    /** Emoji for stickers, content:// link for images/videos. */
    val source: String,
    val startMs: Long,
    val durationMs: Long = 3_000,
    val x: Float = 0.45f,
    val y: Float = 0.55f,
    /** Width as a fraction of the canvas width. */
    val scale: Float = 0.35f,
    val opacity: Float = 1f,
    /** Video PiP: which part of the source to use. */
    val trimStartMs: Long = 0,
    val sourceDurationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val keyframes: List<Keyframe> = emptyList(),
) {
    val endMs get() = startMs + durationMs
}

// STEP 3: Phase 4 keyframes: at [atMs] after the item starts, the item has
// this position/scale/opacity. Values in between are smoothly interpolated.
@Serializable
data class Keyframe(
    val atMs: Long,
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
    val opacity: Float = 1f,
)

/** Where an overlay is at a moment, after keyframes. */
data class Placement(val x: Float, val y: Float, val scaleMul: Float, val opacity: Float)

object KeyframeMath {
    // STEP 4: Position at [localMs] (time since the item started). With no
    // keyframes, the item's own x/y are used. Ease-in-out between frames.
    fun at(baseX: Float, baseY: Float, baseOpacity: Float, keyframes: List<Keyframe>, localMs: Long): Placement {
        if (keyframes.isEmpty()) return Placement(baseX, baseY, 1f, baseOpacity)
        val k = keyframes.sortedBy { it.atMs }
        if (localMs <= k.first().atMs) return k.first().let { Placement(it.x, it.y, it.scale, it.opacity) }
        if (localMs >= k.last().atMs) return k.last().let { Placement(it.x, it.y, it.scale, it.opacity) }
        val i = k.indexOfLast { it.atMs <= localMs }
        val a = k[i]
        val b = k[i + 1]
        val raw = (localMs - a.atMs).toFloat() / (b.atMs - a.atMs).coerceAtLeast(1)
        val t = raw * raw * (3 - 2 * raw) // smoothstep
        fun lerp(p: Float, q: Float) = p + (q - p) * t
        return Placement(lerp(a.x, b.x), lerp(a.y, b.y), lerp(a.scale, b.scale), lerp(a.opacity, b.opacity))
    }
}
