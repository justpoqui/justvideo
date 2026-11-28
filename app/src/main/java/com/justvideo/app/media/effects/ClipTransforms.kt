package com.justvideo.app.media.effects

import android.graphics.Matrix
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix
import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.KeyframeMath
import com.justvideo.app.data.model.TransitionType

// =====================================================================
// ClipTransforms.kt
// Picture effects that change over time, written as small matrices that
// Media3 applies on the GPU for every frame:
// - ZoomPan: crop/zoom/pan, auto-reframe (follow the face) and keyframes
// - TransitionMotion: the "slide in" and "zoom in" transitions
// - FadeToBlack: the "fade through black" transition (and crossfade's
//   fallback), done by dimming the colours
// Times arrive as the clip's own playback time (0 = clip start).
// =====================================================================

// STEP 1: Zoom / pan / reframe / keyframes, applied BEFORE the frame is
// fitted into the canvas, in the source picture's coordinates (-1..1).
@OptIn(UnstableApi::class)
class ZoomPan(private val clip: Clip, private val fillCropFraction: Float) : MatrixTransformation {

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val localMs = presentationTimeUs / 1000
        val t = clip.transform
        var scale = t.scale
        var dx = t.offsetX
        var dy = t.offsetY

        // Auto-reframe: centre on the face for this moment. reframeX holds
        // the face's position per SOURCE second, 0..1 across the frame.
        if (t.autoReframe && t.reframeX.isNotEmpty()) {
            val srcSec = clip.sourceTimeAt(localMs) / 1000f - t.reframeStartSec
            val i = srcSec.toInt().coerceIn(0, t.reframeX.lastIndex)
            val j = (i + 1).coerceAtMost(t.reframeX.lastIndex)
            val fx = t.reframeX[i] + (t.reframeX[j] - t.reframeX[i]) * (srcSec - i)
            // Move the face to the centre, but never so far that the edge shows.
            val maxShift = (1f - fillCropFraction).coerceAtLeast(0f)
            dx += ((0.5f - fx) * 2f).coerceIn(-maxShift, maxShift)
        }

        // Phase 4 keyframes (position/zoom over time) add on top.
        if (clip.keyframes.isNotEmpty()) {
            val p = KeyframeMath.at(0f, 0f, 1f, clip.keyframes, localMs)
            dx += p.x; dy += p.y; scale *= p.scaleMul
        }
        return Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
    }
}

// STEP 2: Slide-in / zoom-in at the clip's start, applied AFTER fitting
// (in canvas coordinates), so the picture moves over black.
@OptIn(UnstableApi::class)
class TransitionMotion(private val type: TransitionType, private val durationMs: Long) : MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val p = ((presentationTimeUs / 1000f) / durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        val e = 1f - (1f - p) * (1f - p) // ease-out
        return Matrix().apply {
            when (type) {
                TransitionType.SLIDE -> postTranslate(2f * (1f - e), 0f) // from the right edge to centre
                TransitionType.ZOOM -> (1.25f - 0.25f * e).let { postScale(it, it) }
                else -> Unit
            }
        }
    }
}

// STEP 3: Fade from black at the start ([inMs]) and/or to black at the
// end ([outMs]) of a clip of length [clipMs]. Done with a colour matrix.
@OptIn(UnstableApi::class)
class FadeToBlack(private val inMs: Long, private val outMs: Long, private val clipMs: Long) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val t = presentationTimeUs / 1000
        var k = 1f
        if (inMs > 0 && t < inMs) k = minOf(k, t.toFloat() / inMs)
        if (outMs > 0 && t > clipMs - outMs) k = minOf(k, ((clipMs - t).toFloat() / outMs).coerceIn(0f, 1f))
        // 4×4 column-major colour matrix that scales R, G, B by k.
        return floatArrayOf(
            k, 0f, 0f, 0f,
            0f, k, 0f, 0f,
            0f, 0f, k, 0f,
            0f, 0f, 0f, 1f,
        )
    }
}
