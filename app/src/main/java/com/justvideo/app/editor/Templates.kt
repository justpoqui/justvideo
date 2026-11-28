package com.justvideo.app.editor

import com.justvideo.app.data.model.CanvasFormat
import com.justvideo.app.data.model.Clip
import com.justvideo.app.data.model.Keyframe
import com.justvideo.app.data.model.MediaKind
import com.justvideo.app.data.model.TextFont
import com.justvideo.app.data.model.TextItem
import com.justvideo.app.data.model.Timeline
import com.justvideo.app.data.model.Transition
import com.justvideo.app.data.model.TransitionType
import java.util.UUID

// =====================================================================
// Templates.kt
// The Create tab's ready-made starting points. Each one turns the files
// you pick into a finished-looking timeline you can then tweak in the
// editor. All offline, pure code (unit-tested):
// - Slideshow: photos with slow pan/zoom ("Ken Burns") and crossfades
// - Music video: clips cut exactly on the song's beats
// - Promo: animated title card, your clips, end card
// - Tutorial: "Step 1, Step 2…" titles on each clip
// Plus animated title presets for any text (motion graphics).
// =====================================================================

/** Ways a title can animate in (used by the Text tool and templates). */
enum class TextAnimation(val label: String) { NONE("None"), POP("Pop in"), SLIDE_UP("Slide up"), SLIDE_LEFT("Slide in"), DRIFT("Slow drift") }

object Templates {

    private fun id() = UUID.randomUUID().toString()

    // STEP 1: Animated titles: keyframes for a text at (x, y) lasting [durationMs].
    fun animate(item: TextItem, a: TextAnimation): TextItem {
        val x = item.x
        val y = item.y
        val d = item.durationMs
        val k = when (a) {
            TextAnimation.NONE -> emptyList()
            TextAnimation.POP -> listOf(Keyframe(0, x, y, 0.4f, 0f), Keyframe(220, x, y, 1.12f, 1f), Keyframe(380, x, y, 1f, 1f))
            TextAnimation.SLIDE_UP -> listOf(Keyframe(0, x, y - 0.25f, 1f, 0f), Keyframe(450, x, y, 1f, 1f))
            TextAnimation.SLIDE_LEFT -> listOf(Keyframe(0, x + 1.4f, y, 1f, 1f), Keyframe(500, x, y, 1f, 1f), Keyframe(d - 400, x, y, 1f, 1f), Keyframe(d, x - 1.4f, y, 1f, 1f))
            TextAnimation.DRIFT -> listOf(Keyframe(0, x - 0.05f, y, 0.96f, 0f), Keyframe(400, x - 0.04f, y, 0.97f, 1f), Keyframe(d, x + 0.05f, y, 1.04f, 1f))
        }
        return item.copy(keyframes = k.filter { it.atMs in 0..d })
    }

    // STEP 2: Slideshow. Each photo slowly zooms in or out and drifts
    // (alternating, so it doesn't feel repetitive), with crossfades between.
    fun slideshow(photos: List<Clip>, perPhotoMs: Long = 3_000, canvas: CanvasFormat = CanvasFormat.VERTICAL): Timeline {
        val clips = photos.mapIndexed { i, p ->
            val zoomIn = i % 2 == 0
            val dx = if (i % 3 == 0) 0.06f else -0.06f
            p.copy(
                id = id(),
                trimStartMs = 0, trimEndMs = if (p.kind == MediaKind.IMAGE) perPhotoMs else p.trimEndMs.coerceAtMost(p.trimStartMs + perPhotoMs),
                transform = p.transform.copy(fill = true),
                keyframes = listOf(
                    Keyframe(0, if (zoomIn) 0f else dx, 0f, if (zoomIn) 1f else 1.15f),
                    Keyframe(perPhotoMs, if (zoomIn) dx else 0f, 0f, if (zoomIn) 1.15f else 1f),
                ),
                transitionIn = if (i == 0) Transition() else Transition(TransitionType.CROSSFADE, 600),
            )
        }
        return Timeline(videoClips = clips, canvas = canvas)
    }

    // STEP 3: Music video. Cut on every beat (or every 2nd/4th beat for slower
    // songs), taking the next part of each video in turn, until the song or
    // the footage runs out.
    fun beatSync(videos: List<Clip>, song: Clip, beatsMs: List<Long>, canvas: CanvasFormat = CanvasFormat.VERTICAL): Timeline {
        if (videos.isEmpty() || beatsMs.size < 2) return Timeline(videoClips = videos, audioClips = listOf(song), canvas = canvas)
        val interval = beatsMs[1] - beatsMs[0]
        val every = when {
            interval < 400 -> 4 // fast songs: cut every 4 beats
            interval < 700 -> 2
            else -> 1
        }
        val cuts = beatsMs.filterIndexed { i, _ -> i % every == 0 }.filter { it < song.durationMs }
        val cursors = LongArray(videos.size) { videos[it].trimStartMs }
        val out = mutableListOf<Clip>()
        // Start the music on the first beat so the first cut lands on it.
        val firstBeat = cuts.first()
        for (i in 0 until cuts.size - 1) {
            val len = cuts[i + 1] - cuts[i]
            val v = i % videos.size
            val src = videos[v]
            val from = if (src.kind == MediaKind.IMAGE) 0 else cursors[v]
            val to = from + len
            if (src.kind != MediaKind.IMAGE && to > src.trimEndMs) continue // this video is used up
            cursors[v] = to
            out += src.copy(id = id(), trimStartMs = from, trimEndMs = to, transform = src.transform.copy(fill = true))
            if (out.sumOf { it.durationMs } >= song.durationMs - firstBeat) break
        }
        val music = song.copy(id = id(), trimStartMs = song.trimStartMs + firstBeat, timelineOffsetMs = 0)
        return Timeline(videoClips = out.ifEmpty { videos }, audioClips = listOf(music), canvas = canvas)
    }

    // STEP 4: Promo: big animated title over the first clip, the clips with
    // fades, and an end card over the last clip.
    fun promo(videos: List<Clip>, title: String, endText: String, canvas: CanvasFormat = CanvasFormat.VERTICAL): Timeline {
        val clips = videos.mapIndexed { i, c ->
            c.copy(id = id(), transitionIn = if (i == 0) Transition() else Transition(TransitionType.FADE, 500), transform = c.transform.copy(fill = true))
        }
        val total = clips.sumOf { it.durationMs }
        val texts = listOf(
            animate(TextItem(id(), title, startMs = 0, durationMs = minOf(3_000, total), y = 0.15f, size = 0.11f, font = TextFont.BOLD), TextAnimation.POP),
            animate(TextItem(id(), endText, startMs = (total - 2_500).coerceAtLeast(0), durationMs = minOf(2_500, total), y = 0f, size = 0.08f, background = true), TextAnimation.SLIDE_UP),
        )
        return Timeline(videoClips = clips, canvas = canvas, texts = texts)
    }

    // STEP 5: Tutorial: a "Step N" title at the start of every clip, low on screen.
    fun tutorial(videos: List<Clip>, canvas: CanvasFormat = CanvasFormat.VERTICAL): Timeline {
        var t = 0L
        val texts = mutableListOf<TextItem>()
        val clips = videos.mapIndexed { i, c ->
            val clip = c.copy(id = id(), transitionIn = if (i == 0) Transition() else Transition(TransitionType.SLIDE, 400))
            texts += animate(
                TextItem(id(), "Step ${i + 1}", startMs = t, durationMs = minOf(2_500, clip.durationMs), x = -0.45f, y = -0.6f, size = 0.07f, background = true),
                TextAnimation.SLIDE_LEFT,
            )
            t += clip.durationMs
            clip
        }
        return Timeline(videoClips = clips, canvas = canvas, texts = texts)
    }
}
