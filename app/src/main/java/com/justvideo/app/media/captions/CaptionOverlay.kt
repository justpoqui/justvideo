package com.justvideo.app.media.captions

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.StaticOverlaySettings

// =====================================================================
// CaptionOverlay.kt
// Draws captions INTO the video (burned in) during export/preview, using
// Media3's overlay effect. Bold white words with a black outline, and the
// word being spoken in colour, like TikTok/CapCut auto-captions.
//
// Speed: the caption picture is redrawn only when the page or the lit
// word changes (a few times a second, not 30), and Media3 only re-uploads
// it to the GPU when it changes.
// =====================================================================

@OptIn(UnstableApi::class)
class CaptionOverlay(
    words: List<CaptionWord>,
    private val videoWidth: Int,
    /** Highlight colour of the spoken word (ARGB). */
    private val accent: Int = Color.rgb(255, 214, 10),
    /** Vertical position: -1 = bottom, 0 = middle, 1 = top. */
    private val anchorY: Float = -0.45f,
    /** Item-relative offset: captions for an item that starts later on the timeline. */
    private val timeOffsetMs: Long = 0,
) : BitmapOverlay() {

    private val pages = CaptionPager.pages(words)
    private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private var lastKey: Pair<Int, Int>? = null
    private var lastBitmap: Bitmap = empty

    // STEP 1: Text style: size scales with the video width (~6.5%).
    private val textSize = videoWidth * 0.065f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = this@CaptionOverlay.textSize
        color = Color.WHITE
    }
    private val stroke = Paint(fill).apply {
        style = Paint.Style.STROKE
        strokeWidth = textSize * 0.14f
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }

    // STEP 2: Media3 asks for the picture at each frame's time.
    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val t = presentationTimeUs / 1000 + timeOffsetMs
        val now = CaptionPager.at(pages, t) ?: return empty.also { lastKey = null; lastBitmap = empty }
        val key = pages.indexOf(now.first) to now.second
        if (key == lastKey) return lastBitmap
        lastKey = key
        lastBitmap = render(now.first, now.second)
        return lastBitmap
    }

    // STEP 3: Where it goes: horizontally centred, at [anchorY].
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0f, anchorY).build()

    // STEP 4: Draw one page: words in a row (wrapped to 2 lines if too wide),
    // UPPER CASE, the lit word in the accent colour.
    private fun render(page: CaptionPage, lit: Int): Bitmap {
        val maxWidth = videoWidth * 0.86f
        val texts = page.words.map { it.text.trim().uppercase() }
        val space = fill.measureText(" ")
        val lines = mutableListOf<MutableList<Int>>(mutableListOf())
        var lineW = 0f
        texts.forEachIndexed { i, s ->
            val w = fill.measureText(s)
            if (lines.last().isNotEmpty() && lineW + space + w > maxWidth) { lines += mutableListOf<Int>(); lineW = 0f }
            lineW += (if (lines.last().isEmpty()) 0f else space) + w
            lines.last() += i
        }
        val lineH = textSize * 1.25f
        val pad = stroke.strokeWidth * 2
        val widths = lines.map { l -> l.sumOf { fill.measureText(texts[it]).toDouble() }.toFloat() + space * (l.size - 1) }
        val bw = (widths.maxOrNull() ?: 1f) + pad * 2
        val bh = lineH * lines.size + pad * 2
        val bmp = Bitmap.createBitmap(bw.toInt().coerceAtLeast(1), bh.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        lines.forEachIndexed { li, line ->
            var x = (bw - widths[li]) / 2
            val y = pad + lineH * li + textSize
            for (i in line) {
                c.drawText(texts[i], x, y, stroke)
                fill.color = if (i == lit) accent else Color.WHITE
                c.drawText(texts[i], x, y, fill)
                x += fill.measureText(texts[i]) + space
            }
        }
        return bmp
    }
}
