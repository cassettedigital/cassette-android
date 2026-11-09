package com.cappielloantonio.tempo.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.cappielloantonio.tempo.glide.CustomGlideRequest
import digital.cassette.kit.CassetteKitTokens
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * cassette: the record's own colour, the Android twin of iOS's `CoverTint`
 * (AmperfyKit/CassetteTokens.swift). A cover is read once, small, off the main
 * thread; the strongest hue becomes the record's colour, lifted until it reads
 * 4.5:1 on the darkest card it sits on (or darkened on the light paper). The
 * artist line, track numbers, the playing row, the scrubbers and the answers on
 * a record's page speak in it. Never orange: a cover that can't be read, or
 * hasn't been yet, is the warm neutral.
 *
 * The maths is CoverTintMath below, ported line for line so a record reads the
 * same colour here as on the iPhone and the web mock.
 */
data class CoverTint(val baseRGB: Int) {
    val glowRGB: Int = CoverTintMath.brighten(baseRGB, 0.42)
    val textRGB: Int = CoverTintMath.readable(baseRGB)
    val textLightRGB: Int = CoverTintMath.readableOnLight(baseRGB)

    /** The record's colour as text, following the screen's light or dark. */
    fun text(context: Context): Int = opaque(if (isNight(context)) textRGB else textLightRGB)

    fun glow(): Int = opaque(glowRGB)

    fun base(): Int = opaque(baseRGB)

    /** Now Playing's room: the cover's hue at one steady strength. */
    fun room(): Int = opaque(CoverTintMath.room(baseRGB))

    /** The glow mixed toward black: a cover's drop shadow (0.4 detail hero, 0.45 Resume). */
    fun shadow(strength: Double): Int = opaque(CoverTintMath.mix(glowRGB, 0x000000, strength.coerceIn(0.0, 1.0)))

    /** Ink mixed with the glow: a kicker over a lit card (0.75 Resume, 0.70 Now Playing). */
    fun kickerInk(context: Context, inkShare: Double = 0.75): Int {
        val ink = if (isNight(context)) CoverTintMath.INK else (CassetteKitTokens.ColorLight.textPrimary and 0xFFFFFF).toInt()
        return opaque(CoverTintMath.mix(ink, glowRGB, inkShare.coerceIn(0.0, 1.0)))
    }

    /** A Search genre tile's paper: the cover's hue at one shared pale tone. */
    fun tileFill(context: Context): Int = tileTone(context, 0.38 to 0.85, 0.30 to 0.20)
    fun tileInk(context: Context): Int = tileTone(context, 0.55 to 0.22, 0.55 to 0.86)
    fun tileCount(context: Context): Int = tileTone(context, 0.35 to 0.38, 0.30 to 0.68)

    private fun tileTone(context: Context, light: Pair<Double, Double>, dark: Pair<Double, Double>): Int {
        val (h, s, _) = CoverTintMath.hsl(baseRGB)
        val grey = s < 0.08
        val hue = if (grey) 0.09 else h
        val soften = if (grey) 0.5 else 1.0
        val tone = if (isNight(context)) dark else light
        return opaque(CoverTintMath.hslToRGB(hue, tone.first * soften, tone.second))
    }

    fun interface Callback {
        fun onTint(tint: CoverTint)
    }

    companion object {
        /** The only fallback: a warm neutral. Never orange. */
        @JvmField
        val NEUTRAL = CoverTint(0x6B5A44)

        private val cache = LruCache<String, CoverTint>(512)
        private val reader = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())
        private val playing = MutableLiveData(NEUTRAL)

        /**
         * The colour of the record playing now: the playing row's bars and both
         * scrubbers speak in it. Set by Now Playing and the mini player.
         */
        @JvmStatic
        fun nowPlaying(): LiveData<CoverTint> = playing

        @JvmStatic
        fun setNowPlaying(tint: CoverTint?) {
            val next = tint ?: NEUTRAL
            if (playing.value != next) playing.postValue(next)
        }

        @JvmStatic
        fun isNight(context: Context): Boolean =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        private fun opaque(rgb: Int): Int = rgb or 0xFF000000.toInt()

        @JvmStatic
        fun cached(key: String?): CoverTint? = key?.let { cache.get(it) }

        /** Reads a bitmap already in hand. Pure; safe off the main thread. */
        @JvmStatic
        fun extract(bitmap: Bitmap): CoverTint =
            CoverTintMath.coverColor(bitmap)?.let { CoverTint(it) } ?: NEUTRAL

        /**
         * The tint of a cover, from the cache or read once (a 64px decode of the
         * same file or address every surface shows, so one record has one colour
         * everywhere). [callback] runs on the main thread; it may run twice when
         * nothing is cached: never, since the neutral is not sent first. A cover
         * that cannot be loaded keeps the caller's neutral.
         */
        @JvmStatic
        fun resolve(context: Context, coverArtId: String?, type: CustomGlideRequest.ResourceType, callback: Callback) {
            if (coverArtId.isNullOrEmpty()) return
            val builder = CustomGlideRequest.Builder.from(context, coverArtId, type)
            val key = builder.cacheKey() ?: coverArtId
            cache.get(key)?.let { callback.onTint(it); return }
            builder.buildBitmap(64).into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    val copy = resource.copy(Bitmap.Config.ARGB_8888, false) ?: return
                    reader.execute {
                        val tint = extract(copy)
                        cache.put(key, tint)
                        main.post { callback.onTint(tint) }
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
        }
    }
}

/**
 * The mock's colour functions (home-taste/color.ts, phone-taste/Tinted.tsx),
 * ported from iOS's CoverTintMath. Channels 0..255; colours 0xRRGGBB.
 */
object CoverTintMath {
    /** What a lifted colour must read on: the elevated card, the lightest dark surface. */
    private val READ_ON = (CassetteKitTokens.Color.surfaceElevated and 0xFFFFFF).toInt()
    /** The darkest light-paper surface a tint sits on. */
    private val LIGHT_PAPER = (CassetteKitTokens.ColorLight.surfaceSunken and 0xFFFFFF).toInt()
    val INK = (CassetteKitTokens.Color.textPrimary and 0xFFFFFF).toInt()

    data class Hsl(val h: Double, val s: Double, val l: Double)

    fun rgbToHsl(r0: Double, g0: Double, b0: Double): Hsl {
        val r = r0 / 255
        val g = g0 / 255
        val b = b0 / 255
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val l = (mx + mn) / 2
        if (mx == mn) return Hsl(0.0, 0.0, l)
        val d = mx - mn
        val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        return Hsl(h / 6, s, l)
    }

    fun hslToRGB(h: Double, s: Double, l: Double): Int {
        fun channel(n: Double): Int {
            val k = (n + h * 12) % 12
            val a = s * min(l, 1 - l)
            val c = l - a * max(-1.0, minOf(k - 3, 9 - k, 1.0))
            return Math.round(c * 255).toInt().coerceIn(0, 255)
        }
        return (channel(0.0) shl 16) or (channel(8.0) shl 8) or channel(4.0)
    }

    private fun comp(rgb: Int) = Triple(((rgb shr 16) and 0xFF).toDouble(), ((rgb shr 8) and 0xFF).toDouble(), (rgb and 0xFF).toDouble())

    fun hsl(rgb: Int): Hsl {
        val (r, g, b) = comp(rgb)
        return rgbToHsl(r, g, b)
    }

    fun readableOnLight(base: Int): Int {
        val (h, s0, l0) = hsl(base)
        val s = if (s0 < 0.1) s0 else max(s0, 0.5)
        var l = min(l0, 0.45)
        while (l >= 0.08) {
            val c = hslToRGB(h, s, l)
            if (contrast(c, LIGHT_PAPER) >= 4.5) return c
            l -= 0.02
        }
        return hslToRGB(h, s, 0.08)
    }

    fun brighten(rgb: Int, l: Double): Int {
        val (h, s, l0) = hsl(rgb)
        return hslToRGB(h, if (s < 0.1) s else max(s, 0.5), max(l0, l))
    }

    fun luminance(rgb: Int): Double {
        val (r, g, b) = comp(rgb)
        fun lin(v: Double): Double {
            val x = v / 255
            return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun room(base: Int): Int {
        val (h, s, _) = hsl(base)
        if (s < 0.08) return hslToRGB(0.09, 0.25, 0.45)
        return hslToRGB(h, min(max(s, 0.4), 0.65), 0.45)
    }

    fun readable(base: Int): Int {
        var l = 0.66
        while (l <= 0.92) {
            val c = brighten(base, l)
            if (contrast(c, READ_ON) >= 4.5) return c
            l += 0.02
        }
        return brighten(base, 0.92)
    }

    /** `color-mix(in srgb, a share, b)`. */
    fun mix(a: Int, b: Int, share: Double): Int {
        val (ar, ag, ab) = comp(a)
        val (br, bg, bb) = comp(b)
        fun m(x: Double, y: Double) = Math.round(x * share + y * (1 - share)).toInt().coerceIn(0, 255)
        return (m(ar, br) shl 16) or (m(ag, bg) shl 8) or m(ab, bb)
    }

    /** `coverColor()` over a 24x24 redraw. null only when nothing can be read. */
    fun coverColor(image: Bitmap): Int? {
        val size = 24
        val small = Bitmap.createScaledBitmap(image, size, size, true)
        val pixels = IntArray(size * size)
        small.getPixels(pixels, 0, size, 0, 0, size, size)
        val bw = DoubleArray(12); val br = DoubleArray(12); val bg = DoubleArray(12); val bb = DoubleArray(12)
        var aw = 0.0; var ar = 0.0; var ag = 0.0; var ab = 0.0
        for (p in pixels) {
            // getPixels hands back straight (un-premultiplied) colour, as a canvas does.
            val r = ((p shr 16) and 0xFF).toDouble()
            val g = ((p shr 8) and 0xFF).toDouble()
            val b = (p and 0xFF).toDouble()
            aw += 1; ar += r; ag += g; ab += b
            val (h, s, l) = rgbToHsl(r, g, b)
            if (s < 0.2 || l < 0.1 || l > 0.9) continue
            val w = s * (1 - abs(l - 0.5))
            val bin = Math.floor(h * 12).toInt() % 12
            bw[bin] += w; br[bin] += r * w; bg[bin] += g * w; bb[bin] += b * w
        }
        // the FIRST bin with the strictly greatest weight
        var best = 0
        for (i in 1 until 12) if (bw[i] > bw[best]) best = i
        val useBin = bw[best] > 8
        val w = if (useBin) bw[best] else aw
        if (w <= 0) return null
        val r = (if (useBin) br[best] else ar) / w
        val g = (if (useBin) bg[best] else ag) / w
        val b = (if (useBin) bb[best] else ab) / w
        val (h, s0, l0) = rgbToHsl(r, g, b)
        val s = if (s0 > 0.12) max(s0, 0.55) else s0 * 0.5
        val l = min(0.55, max(0.28, l0))
        return hslToRGB(h, s, l)
    }
}
