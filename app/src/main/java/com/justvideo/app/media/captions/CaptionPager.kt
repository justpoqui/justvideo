package com.justvideo.app.media.captions

import kotlinx.serialization.Serializable

// =====================================================================
// CaptionPager.kt
// TikTok-style captions show a few words at a time ("pages") and light up
// the word being spoken. This groups timed words into pages and answers
// "what's on screen at time t?". Pure code, unit-tested.
// Shared by Auto Highlights exports and the editor's captions (Phase 3).
// =====================================================================

/** One timed word, in the TIMELINE's time (ms). */
@Serializable
data class CaptionWord(val text: String, val startMs: Long, val endMs: Long)

data class CaptionPage(val words: List<CaptionWord>) {
    val startMs get() = words.first().startMs
    val endMs get() = words.last().endMs
}

object CaptionPager {

    // STEP 1: Group words: at most [maxWords] per page, and a new page
    // after a sentence end or a pause longer than [pauseMs].
    fun pages(words: List<CaptionWord>, maxWords: Int = 4, pauseMs: Long = 600): List<CaptionPage> {
        val pages = mutableListOf<CaptionPage>()
        var current = mutableListOf<CaptionWord>()
        for (w in words.filter { it.text.isNotBlank() }) {
            val prev = current.lastOrNull()
            val breakHere = prev != null && (
                current.size >= maxWords ||
                    w.startMs - prev.endMs > pauseMs ||
                    prev.text.trimEnd().lastOrNull() in setOf('.', '!', '?')
                )
            if (breakHere) { pages += CaptionPage(current); current = mutableListOf() }
            current += w
        }
        if (current.isNotEmpty()) pages += CaptionPage(current)
        return pages
    }

    // STEP 2: What's showing at [timeMs]: the page and which word is lit
    // (-1 = between words). A page stays up to 400 ms after its last word,
    // so captions don't flicker during short pauses.
    fun at(pages: List<CaptionPage>, timeMs: Long, holdMs: Long = 400): Pair<CaptionPage, Int>? {
        val i = pages.indexOfLast { it.startMs <= timeMs }
        if (i < 0) return null
        val page = pages[i]
        val nextStart = pages.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
        if (timeMs > page.endMs + holdMs || timeMs >= nextStart) return null
        val lit = page.words.indexOfLast { it.startMs <= timeMs }
        return page to lit
    }
}
