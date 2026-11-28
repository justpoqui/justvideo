package com.justvideo.app.media.effects

import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.SingleColorLut
import com.justvideo.app.core.AppLog
import com.justvideo.app.data.model.ColorAdjust
import com.justvideo.app.data.model.FilterType
import java.io.File

// =====================================================================
// ColorEffects.kt
// Phase 4 colour tools: brightness, contrast, saturation, filters and
// custom LUTs (".cube" files from apps like Lightroom/DaVinci).
// All run on the GPU through Media3's built-in effects.
// =====================================================================

@OptIn(UnstableApi::class)
object ColorEffects {

    // STEP 1: Build the effect list for a clip (empty when nothing is set).
    fun build(c: ColorAdjust): List<Effect> {
        if (c.isIdentity) return emptyList()
        val out = mutableListOf<Effect>()
        if (c.brightness != 0f) out += Brightness(c.brightness.coerceIn(-1f, 1f))
        if (c.contrast != 0f) out += Contrast(c.contrast.coerceIn(-1f, 1f))
        if (c.saturation != 1f) out += HslAdjustment.Builder().adjustSaturation(((c.saturation - 1f) * 100f).coerceIn(-100f, 100f)).build()
        when (c.filter) {
            FilterType.NONE -> Unit
            FilterType.MONO -> out += RgbFilter.createGrayscaleFilter()
            FilterType.LUT -> c.lutPath?.let { loadCube(it) }?.let { out += it }
            else -> out += presetMatrix(c.filter, c.filterStrength)
        }
        return out
    }

    // STEP 2: Filters as colour matrices, blended with "no change" by strength.
    private fun presetMatrix(f: FilterType, strength: Float): RgbMatrix {
        // Row-major 3×3 (r', g', b' from r, g, b).
        val m = when (f) {
            FilterType.WARM -> floatArrayOf(1.10f, 0.02f, 0f, 0f, 1.02f, 0f, 0f, 0f, 0.88f)
            FilterType.COOL -> floatArrayOf(0.90f, 0f, 0f, 0f, 1.0f, 0.02f, 0f, 0.04f, 1.12f)
            FilterType.VIVID -> floatArrayOf(1.25f, -0.15f, -0.10f, -0.10f, 1.25f, -0.15f, -0.10f, -0.15f, 1.25f)
            FilterType.VINTAGE -> floatArrayOf(0.39f, 0.77f, 0.19f, 0.35f, 0.69f, 0.17f, 0.27f, 0.53f, 0.13f) // sepia
            FilterType.CINEMA -> floatArrayOf(1.08f, 0f, -0.06f, -0.02f, 1.0f, 0.02f, -0.05f, 0.05f, 1.05f)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
        val s = strength.coerceIn(0f, 1f)
        val id = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val b = FloatArray(9) { id[it] + (m[it] - id[it]) * s }
        // Media3 wants a 4×4 column-major matrix.
        val col = floatArrayOf(
            b[0], b[3], b[6], 0f,
            b[1], b[4], b[7], 0f,
            b[2], b[5], b[8], 0f,
            0f, 0f, 0f, 1f,
        )
        return RgbMatrix { _, _ -> col }
    }

    // STEP 3: Read a ".cube" 3D LUT file into Media3's LUT format.
    // Format: optional "LUT_3D_SIZE N", then N³ lines "r g b" (0..1), red
    // changing fastest.
    fun loadCube(path: String): SingleColorLut? = try {
        var size = 0
        val values = ArrayList<FloatArray>()
        File(path).forEachLine { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") -> Unit
                line.startsWith("LUT_3D_SIZE") -> size = line.split(Regex("\\s+"))[1].toInt()
                line.first().isDigit() || line.first() == '-' || line.first() == '.' -> {
                    val p = line.split(Regex("\\s+"))
                    if (p.size >= 3) values += floatArrayOf(p[0].toFloat(), p[1].toFloat(), p[2].toFloat())
                }
            }
        }
        require(size in 2..128 && values.size == size * size * size) { "Not a valid 3D .cube LUT" }
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255 + 0.5f).toInt()
        val cube = Array(size) { Array(size) { IntArray(size) } }
        var i = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val v = values[i++]
            cube[r][g][b] = (0xFF shl 24) or (c(v[0]) shl 16) or (c(v[1]) shl 8) or c(v[2])
        }
        SingleColorLut.createFromCube(cube)
    } catch (e: Exception) {
        AppLog.w("Color", "LUT load failed: ${e.message}")
        null
    }
}
