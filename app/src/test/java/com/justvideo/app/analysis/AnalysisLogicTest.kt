package com.justvideo.app.analysis

import com.justvideo.app.analysis.audio.AudioAnalyzer
import com.justvideo.app.analysis.clipping.ClipFinder
import com.justvideo.app.analysis.model.Features
import com.justvideo.app.analysis.model.HighlightSettings
import com.justvideo.app.analysis.model.Segment
import com.justvideo.app.analysis.model.Signal
import com.justvideo.app.analysis.model.Transcript
import com.justvideo.app.analysis.model.Word
import com.justvideo.app.analysis.scoring.HighlightScorer
import com.justvideo.app.analysis.speech.SpeechSignals
import com.justvideo.app.analysis.speech.TranscriptParser
import com.justvideo.app.analysis.visual.VisualSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

// =====================================================================
// AnalysisLogicTest.kt
// Checks each Auto Highlights stage with synthetic data where we KNOW
// where the "best moment" is.
// =====================================================================

class AnalysisLogicTest {

    // STEP 1: Transcript parsing.
    @Test
    fun parsesTokensIntoWordsWithAbsoluteTimes() {
        val raw = "S\t0\t2000\t No way!\nT\t0\t400\t0.9\t No\nT\t400\t900\t0.8\t way\nT\t900\t1000\t0.9\t!\nS\t3000\t4000\t[BLANK_AUDIO]\n"
        val r = TranscriptParser.parse(raw, offsetMs = 60_000)
        assertEquals(listOf("No", "way!"), r.words.map { it.text })
        assertEquals(60_000L, r.words[0].startMs)
        assertEquals(1, r.segments.size) // [BLANK_AUDIO] is dropped
    }

    // STEP 2: Speech signals.
    @Test
    fun hypePhrasesQuestionsAndLaughterAreFound() {
        val t = Transcript(
            words = listOf(
                Word("Oh", 1_000, 1_200), Word("my", 1_200, 1_400), Word("god!", 1_400, 1_900),
                Word("Really?", 5_000, 5_600), Word("[Laughter]", 8_000, 9_500), Word("subscribe", 12_000, 12_500),
            ),
        )
        val s = SpeechSignals.from(t, 15, extraKeywords = listOf("subscribe"))
        assertTrue(s.hypeWords[1] > 0f)
        assertTrue(s.excitement[1] > 0f)
        assertTrue(s.question[5] > 0f)
        assertTrue(s.laughter[8] > 0f)
        assertTrue(s.hypeWords[12] > 0f) // your own keyword
        assertEquals(0f, s.hypeWords[3])
    }

    // STEP 3: Audio: quiet hum with a loud noisy burst at 30 s.
    @Test
    fun loudNoisyBurstIsDetected() {
        val a = AudioAnalyzer()
        val rnd = Random(1)
        val sr = 16_000
        val total = 60 * sr
        val chunk = FloatArray(sr)
        var t = 0
        while (t < total) {
            for (i in chunk.indices) {
                val n = t + i
                val sec = n / sr
                chunk[i] = if (sec in 30..31) (rnd.nextFloat() * 2 - 1) * 0.8f else 0.02f * sin(n * 0.05f)
            }
            a.consume(chunk); t += sr
        }
        val r = a.finish(60)
        assertTrue(r.loudness[30] > r.loudness[10] + 20)
        assertTrue(r.loudJump[30] > 10f)
        assertTrue(r.burst[30] > r.burst[10])
        assertEquals(600, r.energy100ms.size)
    }

    // STEP 4: Visual: identical frames = no motion; a black→white jump = scene cut.
    @Test
    fun motionAndSceneCuts() {
        val v = VisualSignals(10)
        val grey = ByteArray(64 * 36) { 60 }
        val white = ByteArray(64 * 36) { 250.toByte() }
        v.addFrame(0, grey, 0, 0f)
        v.addFrame(1_000, grey, 1, 0.1f)
        v.addFrame(2_000, white, 0, 0f)
        assertEquals(0f, v.motion[1], 0.001f)
        assertEquals(1f, v.sceneCut[2], 0f)
        assertEquals(1f, v.faces[1], 0f)
    }

    // STEP 5: End to end: a 5-minute video with an obvious moment at 120 s.
    private fun syntheticFeatures(seconds: Int, peak: Int): Features {
        fun arr(f: (Int) -> Float) = FloatArray(seconds) { f(it) }
        val near = { i: Int -> if (i in peak - 1..peak + 2) 1f else 0f }
        return Features(
            durationMs = seconds * 1000L,
            loudness = arr { -30f + near(it) * 20f + (it % 7) * 0.3f },
            loudJump = arr { near(it) * 15f },
            burst = arr { near(it) * 1f },
            musicDrop = arr { 0f },
            energy100ms = FloatArray(seconds * 10) { if (it % 50 == 0) -80f else -30f }, // a quiet instant every 5 s
            speechRate = arr { 2f + near(it) * 2f },
            excitement = arr { near(it) },
            question = arr { 0f },
            hypeWords = arr { near(it) },
            laughter = arr { 0f },
            motion = arr { 0.05f + near(it) * 0.3f },
            sceneCut = arr { 0f },
            faces = arr { 1f },
            closeUp = arr { 0.1f },
        )
    }

    @Test
    fun clipSurroundsThePeakWithSetupAndRightLength() {
        val f = syntheticFeatures(300, peak = 120)
        val settings = HighlightSettings(topN = 3)
        val score = HighlightScorer.score(f, settings)
        val clips = ClipFinder.find(score, f, Transcript(), settings)
        assertTrue(clips.isNotEmpty())
        val best = clips.first()
        assertTrue("peak inside", best.startMs < 120_000 && best.endMs > 121_000)
        assertTrue("setup before the peak", 120_500 - best.startMs in 1_500..4_500)
        assertTrue("length ${best.durationMs}", best.durationMs in settings.minClipMs..settings.maxClipMs + 1_000)
        assertTrue(best.reasons.any { it.signal == Signal.HYPE || it.signal == Signal.LOUD_JUMP || it.signal == Signal.BURST })
    }

    @Test
    fun clipsSnapToSentencesAndNeverOverlap() {
        val f = syntheticFeatures(300, peak = 120)
        val t = Transcript(
            segments = listOf(Segment("Watch this.", 116_400, 118_000), Segment("No way, let's go!", 118_200, 127_000)),
            words = listOf(Word("Watch", 116_400, 116_800), Word("this.", 116_800, 118_000), Word("go!", 126_400, 127_000)),
        )
        val settings = HighlightSettings(topN = 5)
        val clips = ClipFinder.find(HighlightScorer.score(f, settings), f, t, settings)
        val best = clips.first()
        assertEquals(116_250L, best.startMs) // snapped to "Watch this." (−150 ms padding)
        assertEquals(127_250L, best.endMs)   // ends after the sentence (+250 ms)
        // No two clips overlap much.
        for (a in clips) for (b in clips) if (a !== b) {
            val overlap = minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)
            assertFalse(overlap > 0.4 * minOf(a.durationMs, b.durationMs))
        }
    }

    @Test
    fun robustScaleIgnoresTheBaseline() {
        val x = FloatArray(100) { 5f }.also { it[50] = 50f }
        val n = HighlightScorer.robustPositive(x)
        assertEquals(0f, n[10], 0f)
        assertEquals(1f, n[50], 0f)
    }
}
