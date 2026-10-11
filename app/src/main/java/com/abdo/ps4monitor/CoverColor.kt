package com.abdo.ps4monitor
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** The dominant colour of a game's cover (icon0.png of the PKG), used to tint cards and notifications. No extra library. */
object CoverColor {
    private val cache = ConcurrentHashMap<String, Int>()

    /** ARGB colour, or null while the cover is not available yet. Disk access: call off the main thread (or use [load]). */
    fun forDownload(d: Download): Int? {
        if (!d.iconReady) return null
        cache[d.id]?.let { return it }
        val bmp = PkgStore.bitmap("d:${d.id}", "icon0.png", 48) ?: return null
        val c = dominant(bmp)
        cache[d.id] = c
        return c
    }
    suspend fun load(d: Download): Int? = withContext(Dispatchers.IO) { forDownload(d) }
    fun forget(id: String) { cache.remove(id) }

    /**
     * Colours of a notification card, all derived from one cover colour: a dark tonal [surface] of the same hue (white text is always readable on it),
     * a vivid [accent] of that hue (progress fill, percentage, icons) lifted until it has >= 4.5:1 contrast on the surface, plus [track] / [button] /
     * [dim] variants. [track] is an ARGB tint applied with SRC_IN over a 40 %-alpha white layer (see res/drawable/notif_progress.xml), so its alpha is 0.6.
     */
    class Palette(val accent: Int, val surface: Int, val track: Int, val button: Int, val dim: Int)
    private val palettes = ConcurrentHashMap<Int, Palette>()
    fun palette(base: Int): Palette = palettes.getOrPut(base) { buildPalette(base) }

    private fun buildPalette(base: Int): Palette {
        val hsl = FloatArray(3); ColorUtils.colorToHSL(base, hsl)
        val h = hsl[0]; val s = hsl[1]
        val surface = ColorUtils.HSLToColor(floatArrayOf(h, s.coerceIn(0.28f, 0.55f), 0.17f))
        val sat = s.coerceIn(0.55f, 0.92f)
        var l = 0.62f
        var accent = ColorUtils.HSLToColor(floatArrayOf(h, sat, l))
        while (ColorUtils.calculateContrast(accent, surface) < 4.5 && l < 0.88f) { l += 0.03f; accent = ColorUtils.HSLToColor(floatArrayOf(h, sat, l)) }
        return Palette(
            accent = accent, surface = surface,
            track = ColorUtils.setAlphaComponent(accent, 0x99),
            button = ColorUtils.setAlphaComponent(accent, 0x38),
            dim = ColorUtils.blendARGB(surface, accent, 0.45f))
    }

    /** Picks the most colourful hue of the picture, then keeps it readable (not too pale, not too dark). */
    fun dominant(bmp: Bitmap): Int {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val bins = 12
        val wsum = DoubleArray(bins); val r = DoubleArray(bins); val g = DoubleArray(bins); val b = DoubleArray(bins)
        var ar = 0.0; var ag = 0.0; var ab = 0.0; var an = 0
        val hsv = FloatArray(3)
        for (p in px) {
            if (Color.alpha(p) < 128) continue
            ar += Color.red(p); ag += Color.green(p); ab += Color.blue(p); an++
            Color.colorToHSV(p, hsv)
            if (hsv[2] < 0.2f || hsv[1] < 0.25f) continue          // ignore near-black and grey pixels
            val wgt = (hsv[1] * hsv[2]).toDouble()
            // bins are centred on 0, 30, 60 ... degrees, so a red cover (hue ~350..10) is ONE bin instead of two half-weight ones
            val bin = ((((hsv[0] + 180f / bins) % 360f) / 360f) * bins).toInt().coerceIn(0, bins - 1)
            wsum[bin] += wgt; r[bin] += Color.red(p) * wgt; g[bin] += Color.green(p) * wgt; b[bin] += Color.blue(p) * wgt
        }
        var best = -1
        for (i in 0 until bins) if (best < 0 || wsum[i] > wsum[best]) best = i
        val base = when {
            best >= 0 && wsum[best] > 0.0 -> Color.rgb((r[best] / wsum[best]).toInt(), (g[best] / wsum[best]).toInt(), (b[best] / wsum[best]).toInt())
            an > 0 -> Color.rgb((ar / an).toInt(), (ag / an).toInt(), (ab / an).toInt())
            else -> 0xFF3F51B5.toInt()
        }
        Color.colorToHSV(base, hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.9f)
        hsv[2] = hsv[2].coerceIn(0.40f, 0.70f)
        return Color.HSVToColor(hsv)
    }
}
