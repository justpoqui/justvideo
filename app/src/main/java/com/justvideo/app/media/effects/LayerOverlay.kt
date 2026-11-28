package com.justvideo.app.media.effects

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.justvideo.app.data.model.Keyframe
import com.justvideo.app.data.model.KeyframeMath

// =====================================================================
// LayerOverlay.kt
// Burns one text/sticker/photo layer into the video during EXPORT, at the
// right times and position (with keyframe animation). The layer's picture
// is drawn once; Media3 then positions it on every frame.
//
// It's attached to each main-track clip that overlaps the layer, with
// [clipTimelineStartMs] so clip time → timeline time can be worked out.
// =====================================================================

@OptIn(UnstableApi::class)
class LayerOverlay(
    private val bitmap: Bitmap,
    private val startMs: Long,
    private val endMs: Long,
    private val x: Float,
    private val y: Float,
    private val opacity: Float,
    private val keyframes: List<Keyframe>,
    private val clipTimelineStartMs: Long,
) : BitmapOverlay() {

    private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    private fun timelineMs(presentationTimeUs: Long) = clipTimelineStartMs + presentationTimeUs / 1000
    private fun visible(t: Long) = t in startMs until endMs

    // STEP 1: The picture, or an empty 1×1 pixel when the layer is hidden.
    override fun getBitmap(presentationTimeUs: Long): Bitmap =
        if (visible(timelineMs(presentationTimeUs))) bitmap else empty

    // STEP 2: Where: the layer's x/y (canvas centre = 0, edges ±1),
    // animated by keyframes, with opacity.
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val t = timelineMs(presentationTimeUs)
        val p = KeyframeMath.at(x, y, opacity, keyframes, t - startMs)
        return StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(p.x.coerceIn(-1.5f, 1.5f), p.y.coerceIn(-1.5f, 1.5f))
            .setScale(p.scaleMul, p.scaleMul)
            .setAlphaScale(if (visible(t)) p.opacity.coerceIn(0f, 1f) else 0f)
            .build()
    }
}
