package com.justvideo.app.data.model

import com.justvideo.app.media.captions.CaptionWord
import kotlinx.serialization.Serializable

// =====================================================================
// Clip.kt
// One piece of media placed on the timeline, plus everything you can
// adjust on it (speed, volume, fades, transition, crop/zoom/reframe).
// =====================================================================

// STEP 1: What kind of file a clip comes from.
@Serializable
enum class MediaKind { VIDEO, IMAGE, AUDIO }

// STEP 2: How a clip starts (the transition FROM the previous clip).
@Serializable
enum class TransitionType(val label: String) {
    NONE("None"),
    FADE("Fade through black"),
    CROSSFADE("Crossfade"),
    SLIDE("Slide in"),
    ZOOM("Zoom in"),
}

@Serializable
data class Transition(val type: TransitionType = TransitionType.NONE, val durationMs: Long = 500)

// STEP 3: Crop / zoom / pan. Scale 1 = the whole frame fits the canvas.
// fill = crop to fill the canvas (no black bars). Offsets move the picture
// (-1..1 = one half frame). autoReframe follows faces (reframeX per second).
@Serializable
data class ClipTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val fill: Boolean = false,
    val autoReframe: Boolean = false,
    /** Face centre per source second (0..1), filled in by Auto-reframe. */
    val reframeX: List<Float> = emptyList(),
    /** The source second that reframeX[0] belongs to (so long videos store only the used part). */
    val reframeStartSec: Int = 0,
)

// STEP 3b: Colour (Phase 4). 0 = unchanged for brightness/contrast; 1 = normal saturation.
@Serializable
enum class FilterType(val label: String) { NONE("None"), WARM("Warm"), COOL("Cool"), VIVID("Vivid"), MONO("B&W"), VINTAGE("Vintage"), CINEMA("Cinema"), LUT("Custom LUT") }

@Serializable
data class ColorAdjust(
    val brightness: Float = 0f,   // -1..1
    val contrast: Float = 0f,     // -1..1
    val saturation: Float = 1f,   // 0..2
    val filter: FilterType = FilterType.NONE,
    val filterStrength: Float = 1f,
    /** A ".cube" LUT file copied into the app (for FilterType.LUT). */
    val lutPath: String? = null,
) {
    val isIdentity get() = brightness == 0f && contrast == 0f && saturation == 1f && filter == FilterType.NONE
}

// STEP 4: The clip itself.
// A clip never changes the original file. It only stores WHICH part of the
// file to use (trimStartMs..trimEndMs) and how to play it.
@Serializable
data class Clip(
    /** Unique id, so the UI can track a clip while it is dragged or reordered. */
    val id: String,
    /** content:// link to the original file in your gallery/storage. */
    val sourceUri: String,
    val kind: MediaKind,
    /** File name shown in the UI. */
    val displayName: String,
    /**
     * Full length of the source file in ms.
     * Photos have no real length, so they get [IMAGE_MAX_DURATION_MS]
     * as the most you can stretch them to.
     */
    val sourceDurationMs: Long,
    /** Where in the source file this clip starts (ms). */
    val trimStartMs: Long,
    /** Where in the source file this clip ends (ms). */
    val trimEndMs: Long,
    /** Pixel size and rotation of the source (0 for audio). */
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
    /** False for photos and for silent videos. */
    val hasAudio: Boolean = false,
    /**
     * AUDIO TRACK ONLY: where the clip starts on the timeline (ms).
     * Video-track clips play back-to-back, so for them this is ignored.
     */
    val timelineOffsetMs: Long = 0,
    /**
     * Caption words for this clip, in the SOURCE file's time. Stored per clip,
     * so trimming, splitting or moving the clip keeps captions in sync.
     */
    val captions: List<CaptionWord> = emptyList(),
    /** Playback speed, 0.25 – 4 (pitch is kept). */
    val speed: Float = 1f,
    /** Sound volume, 0 (mute) – 2 (double). */
    val volume: Float = 1f,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val transitionIn: Transition = Transition(),
    val transform: ClipTransform = ClipTransform(),
    val color: ColorAdjust = ColorAdjust(),
    /** Phase 4: animate position/zoom/opacity of the clip's picture (times are clip-local). */
    val keyframes: List<Keyframe> = emptyList(),
) {
    /** Length of the used part of the source file. */
    val sourceSpanMs: Long get() = trimEndMs - trimStartMs

    /** How long the clip plays on the timeline (speed included). */
    val durationMs: Long get() = (sourceSpanMs / speed).toLong()

    /** Timeline offset inside this clip → time in the source file. */
    fun sourceTimeAt(localMs: Long): Long = trimStartMs + (localMs * speed).toLong()

    companion object {
        /** A photo shows for 3 seconds when first added. */
        const val IMAGE_DEFAULT_DURATION_MS = 3_000L

        /** The longest you can stretch a photo. */
        const val IMAGE_MAX_DURATION_MS = 60_000L

        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
    }
}
