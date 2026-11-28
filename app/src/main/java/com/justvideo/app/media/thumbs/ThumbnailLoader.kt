package com.justvideo.app.media.thumbs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.justvideo.app.core.AppLog
import com.justvideo.app.data.model.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// =====================================================================
// ThumbnailLoader.kt
// Makes the small frame pictures shown on the timeline's video clips.
//
// How it stays fast and light:
// - Only frames the timeline currently needs are decoded (it tells us
//   via want()). Ones that scrolled off screen are skipped.
// - Frames are tiny (96 px) and the cache has a fixed size. The oldest
//   are thrown away, and it empties when Android is low on memory.
// - Video files are opened once and reused (opening is the slow part).
// - It uses "nearest keyframe" decoding, which is much faster than an
//   exact frame and looks the same at this size.
// =====================================================================

/** Which picture: a file at a (rounded) time. Photos always use timeMs = 0. */
data class ThumbKey(val uri: String, val kind: MediaKind, val timeMs: Long)

class ThumbnailLoader(private val context: Context) {

    private val sizePx = 96
    private val maxCached = 300 // 300 × 96×96 × 4 bytes ≈ 11 MB

    // STEP 1: Finished thumbnails. A Compose state map, so the timeline
    // redraws by itself when a new picture arrives.
    private val ready = mutableStateMapOf<ThumbKey, ImageBitmap>()
    private val order = LinkedHashMap<ThumbKey, Unit>(maxCached, 0.75f, true) // LRU bookkeeping

    // STEP 2: What the timeline wants right now, most important first.
    // Each call to want() replaces the list, so stale requests vanish.
    @Volatile private var wanted: List<ThumbKey> = emptyList()
    private val failed = HashSet<ThumbKey>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val retrievers = LinkedHashMap<String, MediaMetadataRetriever>(4, 0.75f, true)

    init {
        // STEP 3: One background worker decodes frames one at a time.
        // MediaMetadataRetriever isn't thread-safe, and one worker is fast
        // enough (about 20–50 ms per frame).
        scope.launch {
            for (signal in wake) {
                while (true) {
                    val next = wanted.firstOrNull { it !in ready && it !in failed } ?: break
                    val bmp = withContext(Dispatchers.IO) { decode(next) }
                    if (bmp == null) failed += next else put(next, bmp.asImageBitmap())
                }
            }
        }
    }

    /** The picture if it's ready (reading it from a Canvas makes that Canvas redraw when it arrives). */
    operator fun get(key: ThumbKey): ImageBitmap? = ready[key]

    /** Tell the loader which pictures are on screen, nearest the playhead first. */
    fun want(keys: List<ThumbKey>) {
        wanted = keys
        wake.trySend(Unit)
    }

    // STEP 4: Store a picture, removing the least recently used ones when full.
    private fun put(key: ThumbKey, img: ImageBitmap) {
        ready[key] = img
        order[key] = Unit
        while (order.size > maxCached) {
            val oldest = order.keys.first()
            order.remove(oldest)
            ready.remove(oldest)
        }
    }

    /** Android is low on memory: drop every cached picture (they reload as needed). */
    fun clear() {
        scope.launch {
            ready.clear(); order.clear(); failed.clear()
            withContext(Dispatchers.IO) { synchronized(retrievers) { retrievers.values.forEach { it.release() }; retrievers.clear() } }
        }
    }

    // STEP 5: Decoding (runs on the IO thread pool).
    private fun decode(key: ThumbKey): Bitmap? = try {
        when (key.kind) {
            MediaKind.IMAGE -> decodeImage(Uri.parse(key.uri))
            MediaKind.VIDEO -> decodeVideoFrame(key)
            MediaKind.AUDIO -> null
        }
    } catch (e: Exception) {
        AppLog.w(TAG, "Thumbnail failed for ${key.uri} @${key.timeMs}: ${e.message}")
        null
    }

    private fun decodeVideoFrame(key: ThumbKey): Bitmap? = synchronized(retrievers) {
        val r = retrievers.getOrPut(key.uri) {
            MediaMetadataRetriever().apply { setDataSource(context, Uri.parse(key.uri)) }
        }
        // Keep at most 3 files open.
        while (retrievers.size > 3) retrievers.remove(retrievers.keys.first())?.release()

        val us = key.timeMs * 1000
        val frame = if (Build.VERSION.SDK_INT >= 27) {
            r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx * 2, sizePx * 2)
        } else {
            r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
        frame?.let { centerSquare(it) }
    }

    // Photos: decode at a reduced size (inSampleSize), never full resolution.
    private fun decodeImage(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return centerSquare(bmp)
    }

    // STEP 6: Crop the middle square and shrink it to 96×96.
    private fun centerSquare(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val cropped = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, sizePx, sizePx, true)
        if (cropped !== src && cropped !== scaled) cropped.recycle()
        if (src !== scaled) src.recycle()
        return scaled
    }

    private companion object {
        const val TAG = "Thumbs"
    }
}
