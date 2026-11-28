package com.justvideo.app.analysis.clipping

import com.justvideo.app.analysis.model.Features
import com.justvideo.app.analysis.model.Highlight
import com.justvideo.app.analysis.model.HighlightSettings
import com.justvideo.app.analysis.model.Reason
import com.justvideo.app.analysis.model.Signal
import com.justvideo.app.analysis.model.Transcript
import com.justvideo.app.analysis.scoring.ScoreResult
import kotlin.math.abs

// =====================================================================
// ClipFinder.kt
// Turns the per-second score into ready-to-post clips:
// 1. find score PEAKS (best moments), at least one clip-length apart
// 2. build a window: start [leadIn] BEFORE the peak (setup), then extend
//    past the peak while the moment is still strong (payoff)
// 3. SNAP the start/end to sentence boundaries, otherwise to the quietest
//    instant nearby, and never cut in the middle of a word
// 4. merge overlapping windows and drop near-duplicates
// 5. rank and return the top N, each with a "why it was picked"
// Pure code, unit-tested.
// =====================================================================

object ClipFinder {

    fun find(
        score: ScoreResult,
        features: Features,
        transcript: Transcript,
        settings: HighlightSettings,
        idFactory: (Int) -> String = { "h$it" },
    ): List<Highlight> {
        val s = score.score
        val durationMs = features.durationMs
        if (s.isEmpty() || durationMs < settings.minClipMs) return emptyList()

        // STEP 1: Peaks: local maxima above the video's average, best first.
        val mean = s.average().toFloat()
        val peaks = (s.indices).filter { i ->
            s[i] >= mean && (i == 0 || s[i] >= s[i - 1]) && (i == s.lastIndex || s[i] >= s[i + 1])
        }.sortedByDescending { s[it] }

        // Keep peaks at least one minimum clip length apart (non-max suppression).
        val minGapSec = (settings.minClipMs / 1000).toInt()
        val chosen = mutableListOf<Int>()
        for (p in peaks) {
            if (chosen.none { abs(it - p) < minGapSec }) chosen += p
            if (chosen.size >= settings.topN * 3) break
        }

        // STEP 2–3: A snapped window around each peak.
        val windows = chosen.map { p -> window(p, s, features, transcript, settings, durationMs) }

        // STEP 4: Merge overlaps (keep the better one) and drop duplicates.
        val kept = mutableListOf<Window>()
        for (w in windows.sortedByDescending { it.score }) {
            val clash = kept.any { k ->
                val overlap = minOf(k.end, w.end) - maxOf(k.start, w.start)
                overlap > 0.4 * minOf(k.end - k.start, w.end - w.start) || abs(k.start - w.start) < 3_000
            }
            if (!clash) kept += w
            if (kept.size >= settings.topN) break
        }

        // STEP 5: Final clips, best first.
        return kept.mapIndexed { i, w ->
            Highlight(
                id = idFactory(i),
                startMs = w.start,
                endMs = w.end,
                peakMs = w.peakSec * 1000L + 500,
                score = (w.score * 100).toInt().coerceIn(0, 100),
                reasons = reasons(score, w.peakSec),
                snippet = transcript.textBetween(w.start, w.end).take(160),
            )
        }
    }

    private class Window(val start: Long, val end: Long, val peakSec: Int, val score: Float)

    private fun window(
        peak: Int, s: FloatArray, f: Features, t: Transcript, cfg: HighlightSettings, durationMs: Long,
    ): Window {
        val peakMs = peak * 1000L + 500
        var start = peakMs - cfg.leadInMs
        // Payoff: extend the end while the score stays above half the peak.
        var endSec = peak
        while (endSec + 1 < s.size && s[endSec + 1] >= s[peak] * 0.5f && (endSec + 2) * 1000L - start <= cfg.maxClipMs) endSec++
        var end = maxOf((endSec + 1) * 1000L + 1_000, start + cfg.minClipMs)

        // Snap the start: a sentence start just before (preferred), else a quiet instant.
        start = snapStart(start, t, f)
        end = snapEnd(end, t, f)

        // Keep the length within your limits.
        if (end - start > cfg.maxClipMs) end = lastWordEndBefore(t, start + cfg.maxClipMs) ?: (start + cfg.maxClipMs)
        if (end - start < cfg.minClipMs) end = start + cfg.minClipMs
        // Never cut mid-word at the end (finish the word, if that stays within +1 s).
        t.words.firstOrNull { it.startMs < end && it.endMs > end }?.let { w -> if (w.endMs - end < 1_000) end = w.endMs + 100 }

        // Stay inside the video.
        if (start < 0) { end -= start; start = 0 }
        if (end > durationMs) { start = maxOf(0, start - (end - durationMs)); end = durationMs }

        // Window score: average of its best 5 seconds (rewards sustained moments).
        val inside = ((start / 1000).toInt() until ((end + 999) / 1000).toInt().coerceAtMost(s.size)).map { s[it] }.sortedDescending()
        val score = inside.take(5).average().toFloat().takeIf { !it.isNaN() } ?: s[peak]
        return Window(start, end, peak, score)
    }

    // Prefer the latest sentence beginning up to 2 s BEFORE (keeps the
    // setup), else one up to 1 s after, else the quietest 100 ms nearby.
    // If that would cut a word, move to the word's start.
    private fun snapStart(start: Long, t: Transcript, f: Features): Long {
        (t.segments.filter { it.startMs in (start - 2_000)..start }.maxByOrNull { it.startMs }
            ?: t.segments.filter { it.startMs in start..(start + 1_000) }.minByOrNull { it.startMs })
            ?.let { return (it.startMs - 150).coerceAtLeast(0) }
        var s = quietest(f, start - 1_500, start + 500) ?: start
        t.words.firstOrNull { it.startMs < s && it.endMs > s }?.let { s = it.startMs - 100 }
        return s.coerceAtLeast(0)
    }

    // Prefer the first sentence end up to 2 s AFTER (keeps the payoff),
    // else one up to 1 s before, else the quietest instant nearby.
    private fun snapEnd(end: Long, t: Transcript, f: Features): Long {
        (t.segments.filter { it.endMs in end..(end + 2_000) }.minByOrNull { it.endMs }
            ?: t.segments.filter { it.endMs in (end - 1_000)..end }.maxByOrNull { it.endMs })
            ?.let { return it.endMs + 250 }
        var e = quietest(f, end - 500, end + 1_500) ?: end
        t.words.firstOrNull { it.startMs < e && it.endMs > e }?.let { e = it.endMs + 100 }
        return e
    }

    /** The quietest 100 ms frame between two times (null if there's no audio data). */
    internal fun quietest(f: Features, fromMs: Long, toMs: Long): Long? {
        val e = f.energy100ms
        if (e.isEmpty()) return null
        val a = (fromMs / 100).toInt().coerceIn(0, e.lastIndex)
        val b = (toMs / 100).toInt().coerceIn(0, e.lastIndex)
        if (b <= a) return null
        var best = a
        for (i in a..b) if (e[i] < e[best]) best = i
        return best * 100L + 50
    }

    private fun lastWordEndBefore(t: Transcript, limit: Long): Long? =
        t.words.lastOrNull { it.endMs <= limit }?.endMs?.plus(100)

    // STEP 6: Why: the 3 strongest signals around the peak (±2 s).
    internal fun reasons(score: ScoreResult, peak: Int): List<Reason> =
        score.contribution.map { (signal, c) ->
            var best = 0f
            for (i in peak - 2..peak + 2) if (i in c.indices) best = maxOf(best, c[i])
            Reason(signal, best)
        }.filter { it.strength >= 0.15f }.sortedByDescending { it.strength }.take(3)
            .ifEmpty { listOf(Reason(Signal.LOUDNESS, 0f)) }
}
