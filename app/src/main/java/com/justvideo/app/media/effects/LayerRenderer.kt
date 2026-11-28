package com.justvideo.app.media.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import com.justvideo.app.data.model.OverlayItem
import com.justvideo.app.data.model.OverlayKind
import com.justvideo.app.data.model.TextFont
import com.justvideo.app.data.model.TextItem

// =====================================================================
// LayerRenderer.kt
// Draws text overlays and stickers into bitmaps, and loads overlay
// photos. The SAME pictures are used by the editor's live preview
// (Compose draws them so you can drag them) and by the export (Media3
// burns them into the video), so what you see is what you get.
// =====================================================================

object LayerRenderer {

    // STEP 1: Fonts (built into Android: no files to download).
    fun typeface(font: TextFont): Typeface = when (font) {
        TextFont.BOLD -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        TextFont.CLASSIC -> Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        TextFont.SERIF -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        TextFont.MONO -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        TextFont.HANDWRITTEN -> Typeface.create("casual", Typeface.NORMAL)
    }

    // STEP 2: A text item at a given canvas width. Multi-line text is
    // centred. Outline (or a background box) keeps it readable on any video.
    fun renderText(item: TextItem, canvasWidth: Int): Bitmap {
        val size = (item.size * canvasWidth).coerceAtLeast(8f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typeface(item.font); textSize = size; color = item.color
        }
        val stroke = Paint(fill).apply {
            style = Paint.Style.STROKE; strokeWidth = size * 0.12f; strokeJoin = Paint.Join.ROUND; color = Color.BLACK
        }
        val lines = item.text.ifBlank { " " }.split('\n')
        val widths = lines.map { fill.measureText(it) }
        val pad = size * 0.35f
        val lineH = size * 1.2f
        val w = ((widths.maxOrNull() ?: 1f) + pad * 2).toInt().coerceAtLeast(1)
        val h = (lineH * lines.size + pad * 2).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (item.background) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) }
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), size * 0.3f, size * 0.3f, bg)
        }
        lines.forEachIndexed { i, line ->
            val x = (w - widths[i]) / 2
            val y = pad + lineH * i + size * 0.95f
            if (!item.background) c.drawText(line, x, y, stroke)
            c.drawText(line, x, y, fill)
        }
        return bmp
    }

    // STEP 3: A sticker = one emoji, drawn big.
    fun renderSticker(emoji: String, pixelWidth: Int): Bitmap {
        val px = pixelWidth.coerceIn(16, 1024)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = px * 0.85f; textAlign = Paint.Align.CENTER }
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val fm = p.fontMetrics
        Canvas(bmp).drawText(emoji, px / 2f, px / 2f - (fm.ascent + fm.descent) / 2, p)
        return bmp
    }

    // STEP 4: A photo overlay, decoded at roughly the size it's shown.
    fun loadImage(context: Context, uri: String, pixelWidth: Int): Bitmap? = try {
        val r = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= pixelWidth) sample *= 2
        r.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    } catch (_: Exception) {
        null
    }

    /** The overlay's bitmap for a canvas width (null for video PiP, which is composited instead). */
    fun renderOverlay(context: Context, o: OverlayItem, canvasWidth: Int): Bitmap? = when (o.kind) {
        OverlayKind.STICKER -> renderSticker(o.source, (o.scale * canvasWidth).toInt())
        OverlayKind.IMAGE -> loadImage(context, o.source, (o.scale * canvasWidth).toInt())
        OverlayKind.VIDEO -> null
    }
}
