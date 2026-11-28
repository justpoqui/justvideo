package com.justvideo.app.media.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.justvideo.app.data.model.CanvasFormat
import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.Timeline

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
     *   PREVIEW (null): only the canvas shape is applied, at the clips'
     *   own resolution, which is cheaper for the phone to show live.
     */
    fun build(timeline: Timeline, output: CanvasFormat? = null): Composition? {
        if (timeline.videoClips.isEmpty()) return null

        // Every clip is fitted into the canvas (black bars where the shape
        // differs), so mixed portrait/landscape clips line up.
        val fit = if (output != null) {
            Presentation.createForWidthAndHeight(output.width, output.height, Presentation.LAYOUT_SCALE_TO_FIT)
        } else {
            Presentation.createForAspectRatio(timeline.canvas.aspectRatio, Presentation.LAYOUT_SCALE_TO_FIT)
        }
        val effects = Effects(emptyList(), listOf(fit))

        // Main track: video + its sound. Declaring both track types means
        // Media3 fills silence for photos and silent videos automatically.
        val main = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
            .addItems(timeline.videoClips.map { visualItem(it, effects) })
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
