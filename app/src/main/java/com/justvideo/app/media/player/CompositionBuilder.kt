package com.justvideo.app.media.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.justvideo.app.data.model.CanvasFormat
import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.KeyframeMath
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.OverlayItem
import com.justvideo.app.data.model.OverlayKind
import com.justvideo.app.data.model.Timeline
import com.justvideo.app.data.model.TransitionType
import com.justvideo.app.editor.TimelineOps
import com.justvideo.app.media.captions.CaptionOverlay
import com.justvideo.app.media.effects.ColorEffects
import com.justvideo.app.media.effects.FadeToBlack
import com.justvideo.app.media.effects.GainProcessor
import com.justvideo.app.media.effects.LayerOverlay
import com.justvideo.app.media.effects.LayerRenderer
import com.justvideo.app.media.effects.TransitionMotion
import com.justvideo.app.media.effects.ZoomPan

// =====================================================================
// CompositionBuilder.kt
// Turns our Timeline into a Media3 "Composition", the description of an
// edit that Media3 can both PREVIEW (CompositionPlayer) and EXPORT
// (Transformer). Because both use the same Composition, the export
// matches what you saw in the preview.
//
// Layers (top to bottom):
//   picture-in-picture videos → crossfade "tails" → main track → (audio track)
// The extra layers are only added when a project uses them, so simple
// projects keep the simplest, fastest setup.
// =====================================================================

@OptIn(UnstableApi::class) // Media3 marks its editing API as "unstable" (it may change between versions)
object CompositionBuilder {

    /** Frame rate given to photos. They don't move, so 30 fps is plenty. */
    private const val IMAGE_FPS = 30

    /**
     * STEP 1: Build the whole composition. Returns null if there's no
     * video/photo yet (nothing to show).
     *
     * @param output EXPORT: the exact pixel size. PREVIEW (null): the canvas at half size.
     * @param context needed to draw photo/sticker/text layers.
     * @param includeLayers burn text/sticker/photo layers in. The editor preview
     *   draws those itself (so you can drag them), so it passes false.
     */
    fun build(
        timeline: Timeline,
        output: CanvasFormat? = null,
        context: Context? = null,
        includeLayers: Boolean = output != null,
    ): Composition? {
        if (timeline.videoClips.isEmpty()) return null
        val (outW, outH) = if (output != null) output.width to output.height
        else timeline.canvas.width / 2 to timeline.canvas.height / 2
        val starts = TimelineOps.videoStarts(timeline)
        val total = timeline.durationMs

        // STEP 2: Main track (video + its sound).
        val main = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
            .addItems(timeline.videoClips.mapIndexed { i, clip ->
                // A "fade through black" into the NEXT clip also fades this one out.
                val next = timeline.videoClips.getOrNull(i + 1)?.transitionIn
                val fadeOut = if (next?.type == TransitionType.FADE) next.durationMs / 2 else 0
                visualItem(clip, videoEffects(clip, starts[i], fadeOut, timeline, outW, outH, context, includeLayers))
            })
            .build()

        // STEP 3: Audio track (music etc.), mixed with the main track's sound.
        val segments = AudioLayout.plan(timeline)
        val audioSeq = if (segments.isEmpty()) null else EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).apply {
            for (s in segments) {
                if (s.gapBeforeMs > 0) addGap(s.gapBeforeMs * 1000)
                addItem(audioItem(s))
            }
        }.build()

        // STEP 4: Extra video layers: picture-in-picture videos, and the
        // crossfade tails (the end of clip A shown over the start of clip B).
        val pips = timeline.overlays.filter { it.kind == OverlayKind.VIDEO && it.startMs < total }
        val tails = crossfadeTails(timeline, starts)
        val layers = mutableListOf<EditedMediaItemSequence>()
        pips.forEach { layers += pipSequence(it, total, outW) }
        if (tails.isNotEmpty()) layers += tailSequence(tails, total, timeline, outW, outH)

        val sequences = layers + main + listOfNotNull(audioSeq)
        val b = Composition.Builder(sequences)
            // Phones often record HDR. Converting to normal (SDR) colour makes
            // the export play correctly everywhere, including every social app.
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        if (layers.isNotEmpty()) b.setVideoCompositorSettings(Compositor(outW, outH, pips, if (tails.isNotEmpty()) tails else null))
        return b.build()
    }

    // STEP 5: Per-clip picture effects, in order:
    // zoom/pan/reframe → fit into canvas → transition motion → colour →
    // captions + layers → fades.
    private fun videoEffects(
        clip: Clip, clipStart: Long, fadeOutMs: Long, timeline: Timeline,
        outW: Int, outH: Int, context: Context?, includeLayers: Boolean,
    ): Effects {
        val list = mutableListOf<Effect>()
        val t = clip.transform
        val fill = t.fill || t.autoReframe
        val needsZoomPan = t.scale != 1f || t.offsetX != 0f || t.offsetY != 0f || t.autoReframe || clip.keyframes.isNotEmpty()
        if (needsZoomPan) list += ZoomPan(clip, visibleFraction(clip, outW, outH))
        list += Presentation.createForWidthAndHeight(
            outW, outH, if (fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT,
        )
        val tr = clip.transitionIn
        if (tr.type == TransitionType.SLIDE || tr.type == TransitionType.ZOOM) list += TransitionMotion(tr.type, tr.durationMs)
        list += ColorEffects.build(clip.color)

        val overlays = mutableListOf<TextureOverlay>()
        if (timeline.showCaptions && clip.captions.isNotEmpty()) {
            // The item's frame times start at 0 at the trim point and run at
            // the clip's speed, so the source time is trimStart + t × speed.
            val shifted = clip.captions.map { w ->
                w.copy(startMs = ((w.startMs - clip.trimStartMs) / clip.speed).toLong(), endMs = ((w.endMs - clip.trimStartMs) / clip.speed).toLong())
            }
            overlays += CaptionOverlay(shifted, outW, accent = timeline.captionStyle.accent, anchorY = timeline.captionStyle.positionY)
        }
        if (includeLayers && context != null) overlays += layerOverlays(timeline, clipStart, clipStart + clip.durationMs, outW, context)
        if (overlays.isNotEmpty()) list += OverlayEffect(overlays)

        val fadeIn = if (tr.type == TransitionType.FADE) tr.durationMs / 2 else 0
        if (fadeIn > 0 || fadeOutMs > 0) list += FadeToBlack(fadeIn, fadeOutMs, clip.durationMs)
        return Effects(audioProcessors(clip), list)
    }

    /** How much of the source's width stays visible when cropping to fill the canvas (for reframe limits). */
    private fun visibleFraction(clip: Clip, outW: Int, outH: Int): Float {
        val (w, h) = if (clip.rotationDegrees % 180 == 0) clip.width to clip.height else clip.height to clip.width
        if (w <= 0 || h <= 0) return 1f
        val src = w.toFloat() / h
        val dst = outW.toFloat() / outH
        return if (src > dst) dst / src else 1f
    }

    // Text/sticker/photo layers overlapping this clip's time span.
    private fun layerOverlays(timeline: Timeline, from: Long, to: Long, outW: Int, context: Context): List<TextureOverlay> {
        val out = mutableListOf<TextureOverlay>()
        for (t in timeline.texts) if (t.endMs > from && t.startMs < to) {
            out += LayerOverlay(LayerRenderer.renderText(t, outW), t.startMs, t.endMs, t.x, t.y, 1f, t.keyframes, from)
        }
        for (o in timeline.overlays) if (o.kind != OverlayKind.VIDEO && o.endMs > from && o.startMs < to) {
            val bmp = LayerRenderer.renderOverlay(context, o, outW) ?: continue
            // Photos are drawn at the decoded size, so scale them to the wanted width.
            val k = (o.scale * outW) / bmp.width
            out += LayerOverlay(
                if (o.kind == OverlayKind.IMAGE) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true) else bmp,
                o.startMs, o.endMs, o.x, o.y, o.opacity, o.keyframes, from,
            )
        }
        return out
    }

    // STEP 6: Sound: volume + fades, and speed for both picture and sound.
    private fun audioProcessors(clip: Clip): List<AudioProcessor> =
        if (clip.volume != 1f || clip.fadeInMs > 0 || clip.fadeOutMs > 0) {
            listOf(GainProcessor(clip.volume, clip.fadeInMs, clip.fadeOutMs, clip.durationMs))
        } else {
            emptyList()
        }

    private fun constantSpeed(speed: Float) = object : SpeedProvider {
        override fun getSpeed(timeUs: Long) = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
    }

    // STEP 7: A video clip, or a photo shown for its duration.
    private fun visualItem(clip: Clip, effects: Effects): EditedMediaItem =
        if (clip.kind == MediaKind.IMAGE) {
            val media = MediaItem.Builder().setUri(clip.sourceUri).setImageDurationMs(clip.durationMs).build()
            EditedMediaItem.Builder(media)
                .setDurationUs(clip.durationMs * 1000)
                .setFrameRate(IMAGE_FPS)
                .setEffects(effects)
                .build()
        } else {
            EditedMediaItem.Builder(clipped(clip.sourceUri, clip.trimStartMs, clip.trimEndMs))
                // The duration of the WHOLE source file, before trimming.
                .setDurationUs(clip.sourceDurationMs * 1000)
                .setEffects(effects)
                .apply { if (clip.speed != 1f) setSpeed(constantSpeed(clip.speed)) }
                .build()
        }

    // An audio piece. We keep only the sound (some "audio" files, like
    // music videos, also contain pictures).
    private fun audioItem(s: AudioSegment): EditedMediaItem =
        EditedMediaItem.Builder(clipped(s.clip.sourceUri, s.startMs, s.endMs))
            .setDurationUs(s.clip.sourceDurationMs * 1000)
            .setRemoveVideo(true)
            .setEffects(Effects(audioProcessors(s.clip), emptyList()))
            .apply { if (s.clip.speed != 1f) setSpeed(constantSpeed(s.clip.speed)) }
            .build()

    // A MediaItem that plays only startMs..endMs of the file.
    private fun clipped(uri: String, startMs: Long, endMs: Long): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()
            )
            .build()

    // STEP 8: Picture-in-picture: a layer that's empty except while the
    // overlay is on screen. The clip is shrunk to its on-screen width.
    private fun pipSequence(o: OverlayItem, total: Long, outW: Int): EditedMediaItemSequence {
        val w = ((o.scale * outW).toInt() / 2 * 2).coerceAtLeast(16)
        val aspect = if (o.width > 0 && o.height > 0) o.height.toFloat() / o.width else 9f / 16f
        val h = ((w * aspect).toInt() / 2 * 2).coerceAtLeast(16)
        val dur = o.durationMs.coerceAtMost(total - o.startMs)
        val item = EditedMediaItem.Builder(clipped(o.source, o.trimStartMs, o.trimStartMs + dur))
            .setDurationUs(o.sourceDurationMs.coerceAtLeast(o.trimStartMs + dur) * 1000)
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))))
            .build()
        return EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).apply {
            if (o.startMs > 0) addGap(o.startMs * 1000)
            addItem(item)
            val rest = total - o.startMs - dur
            if (rest > 0) addGap(rest * 1000)
        }.build()
    }

    // STEP 9: Crossfades. For each clip B that crossfades in, take the
    // part of the previous clip A right after its trim end (or its last
    // moment if the file ends there) and show it over B, fading out.
    data class Tail(val clip: Clip, val atMs: Long, val durationMs: Long)

    private fun crossfadeTails(timeline: Timeline, starts: List<Long>): List<Tail> =
        timeline.videoClips.mapIndexedNotNull { i, b ->
            if (i == 0 || b.transitionIn.type != TransitionType.CROSSFADE) return@mapIndexedNotNull null
            val a = timeline.videoClips[i - 1]
            val d = b.transitionIn.durationMs.coerceAtMost(b.durationMs / 2).coerceAtMost(2_000)
            val span = (d * a.speed).toLong()
            val (from, to) = if (a.kind == MediaKind.IMAGE || a.trimEndMs + span <= a.sourceDurationMs) {
                a.trimEndMs to a.trimEndMs + span
            } else {
                (a.trimEndMs - span).coerceAtLeast(a.trimStartMs) to a.trimEndMs
            }
            Tail(a.copy(trimStartMs = from, trimEndMs = to, transitionIn = com.justvideo.app.data.model.Transition(), captions = emptyList()), starts[i], d)
        }

    private fun tailSequence(tails: List<Tail>, total: Long, timeline: Timeline, outW: Int, outH: Int): EditedMediaItemSequence =
        EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).apply {
            var cursor = 0L
            for (t in tails) {
                if (t.atMs > cursor) addGap((t.atMs - cursor) * 1000)
                val fx = videoEffects(t.clip, t.atMs, 0, timeline.copy(showCaptions = false, texts = emptyList(), overlays = emptyList()), outW, outH, null, false)
                addItem(visualItem(t.clip, Effects(emptyList(), fx.videoEffects)).buildUpon().setRemoveAudio(true).build())
                cursor = t.atMs + t.clip.durationMs
            }
            if (total > cursor) addGap((total - cursor) * 1000)
        }.build()

    // STEP 10: How the layers are stacked and placed, frame by frame.
    // Input order = sequence order: PiPs (0..n-1), then tails, then main.
    private class Compositor(
        private val outW: Int, private val outH: Int,
        private val pips: List<OverlayItem>,
        private val tails: List<Tail>?,
    ) : VideoCompositorSettings {
        private val tailIndex = pips.size
        private val hidden: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()

        override fun getOutputSize(inputSizes: List<Size>): Size = Size(outW, outH)

        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
            val t = presentationTimeUs / 1000
            if (inputId < pips.size) {
                val o = pips[inputId]
                if (t < o.startMs || t >= o.endMs) return hidden
                val p = KeyframeMath.at(o.x, o.y, o.opacity, o.keyframes, t - o.startMs)
                return StaticOverlaySettings.Builder()
                    .setBackgroundFrameAnchor(p.x.coerceIn(-1.5f, 1.5f), p.y.coerceIn(-1.5f, 1.5f))
                    .setScale(p.scaleMul, p.scaleMul)
                    .setAlphaScale(p.opacity.coerceIn(0f, 1f))
                    .build()
            }
            if (tails != null && inputId == tailIndex) {
                val tail = tails.firstOrNull { t >= it.atMs && t < it.atMs + it.durationMs } ?: return hidden
                val k = 1f - (t - tail.atMs).toFloat() / tail.durationMs
                return StaticOverlaySettings.Builder().setAlphaScale(k.coerceIn(0f, 1f)).build()
            }
            return StaticOverlaySettings.Builder().build() // main track: full frame
        }
    }
}
