package com.justvideo.app.media.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.justvideo.app.data.model.CanvasFormat
import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.Timeline
import com.justvideo.app.media.captions.CaptionOverlay

// =====================================================================
// CompositionBuilder.kt
// Turns our Timeline into a Media3 "Composition", the description of an
// edit that Media3 can both PREVIEW (CompositionPlayer) and EXPORT
// (Transformer). Because both use the same Composition, the export
// matches what you saw in the preview.
// =====================================================================

@OptIn(UnstableApi::class) // Media3 marks its editing API as "unstable" (it may change between versions)
object CompositionBuilder {

    /** Frame rate given to photos. They don't move, so 30 fps is plenty. */
    private const val IMAGE_FPS = 30

    /**
     * STEP 1: Build the whole composition. Returns null if there's no
     * video/photo yet (nothing to show).
     *
     * @param output For EXPORT: the exact pixel size of the file. For
     *   PREVIEW (null): the canvas at half size, cheaper to show live.
     */
    fun build(timeline: Timeline, output: CanvasFormat? = null): Composition? {
        if (timeline.videoClips.isEmpty()) return null

        // Every clip is fitted into the canvas (black bars where the shape
        // differs), so mixed portrait/landscape clips line up.
        // Preview renders at half size: cheaper to show live, same layout.
        val (outW, outH) = if (output != null) output.width to output.height
        else timeline.canvas.width / 2 to timeline.canvas.height / 2
        val fit = Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT)

        // Main track: video + its sound. Declaring both track types means
        // Media3 fills silence for photos and silent videos automatically.
        val main = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
            .addItems(timeline.videoClips.map { visualItem(it, videoEffects(it, timeline, fit, outW)) })
            .build()

        // Audio track (music etc.), mixed on top of the main track's sound.
        val segments = AudioLayout.plan(timeline)
        val sequences = mutableListOf(main)
        if (segments.isNotEmpty()) {
            sequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).apply {
                for (s in segments) {
                    if (s.gapBeforeMs > 0) addGap(s.gapBeforeMs * 1000)
                    addItem(audioItem(s))
                }
            }.build()
        }
        return Composition.Builder(sequences)
            // Phones often record HDR. Converting to normal (SDR) colour makes
            // the export play correctly everywhere, including every social app.
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    // STEP 1b: Per-clip picture effects, in order: fit into the canvas,
    // then burned-in captions on top.
    private fun videoEffects(clip: Clip, timeline: Timeline, fit: Presentation, outW: Int): Effects {
        val list = mutableListOf<androidx.media3.common.Effect>(fit)
        if (timeline.showCaptions && clip.captions.isNotEmpty()) {
            // The item's frame times start at 0 at the trim point, so shift by
            // trimStart to get back to the source time the words use.
            list += OverlayEffect(listOf(CaptionOverlay(clip.captions, outW, timeOffsetMs = clip.trimStartMs)))
        }
        return Effects(emptyList(), list)
    }

    // STEP 2: A video clip, or a photo shown for its duration.
    private fun visualItem(clip: Clip, effects: Effects): EditedMediaItem =
        if (clip.kind == MediaKind.IMAGE) {
            val media = MediaItem.Builder()
                .setUri(clip.sourceUri)
                .setImageDurationMs(clip.durationMs)
                .build()
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
                .build()
        }

    // STEP 3: An audio piece. We keep only the sound (some "audio" files,
    // like music videos, also contain pictures).
    private fun audioItem(s: AudioSegment): EditedMediaItem =
        EditedMediaItem.Builder(clipped(s.clip.sourceUri, s.startMs, s.endMs))
            .setDurationUs(s.clip.sourceDurationMs * 1000)
            .setRemoveVideo(true)
            .build()

    // A MediaItem that plays only startMs..endMs of the file.
    private fun clipped(uri: String, startMs: Long, endMs: Long): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .build()
}
