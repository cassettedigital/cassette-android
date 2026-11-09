package com.cappielloantonio.tempo.ui.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.LruCache
import android.view.View
import androidx.core.content.ContextCompat
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.glide.CustomGlideRequest
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * cassette: a record's glow behind a surface (a record page's header, the Resume
 * card, a search result), the Android twin of iOS's CoverGlowView +
 * CoverGlowRecipe. The glow is a tiny copy of the cover, blurred ONCE off the
 * main thread and cached, then drawn scaled up with filtering: a 32px texture
 * scaled up looks the same as a full-size blur and costs nothing per frame, so
 * it works on every Android version (the old Now Playing blur needed 12+).
 * A warm scrim of the page's own colour sits over it, and the glow fades out
 * at its foot instead of ending in a line (founder, 2026-10-21).
 */
class CoverGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Where and how strongly the glow sits, in the iPhone's numbers. */
    data class Recipe(
        val sizeFactor: Float,
        val topFactor: Float,
        val topOffsetDp: Float,
        val alpha: Float,
        /** (colour res, alpha, location) stops, top to bottom or leading to trailing. */
        val scrim: List<Triple<Int, Float, Float>>,
        val horizontal: Boolean,
        val bottomFade: Float = 0f,
    )

    companion object {
        /** Album, artist and playlist pages. */
        @JvmField
        val PAGE = Recipe(1.4f, 0f, -160f, 0.42f, listOf(
            Triple(R.color.cs_sunken, 0.4f, 0f),
            Triple(R.color.cs_bg, 0.62f, 0.45f),
            Triple(R.color.cs_bg, 1.0f, 1.0f),
        ), horizontal = false, bottomFade = 0.35f)

        /** The Home Resume card. */
        @JvmField
        val RESUME_CARD = Recipe(1.2f, -1.2f, 0f, 0.75f, listOf(
            Triple(R.color.cs_bg, 0.25f, 0f),
            Triple(R.color.cs_bg, 0.7f, 0.7f),
        ), horizontal = true)

        /** Search's top result. */
        @JvmField
        val TOP_RESULT = Recipe(1.2f, -0.8f, 0f, 0.8f, listOf(
            Triple(R.color.cs_bg, 0.2f, 0f),
            Triple(R.color.cs_bg, 0.7f, 0.75f),
        ), horizontal = true)

        private val tinyCache = LruCache<String, Bitmap>(64)
        private val blurrer = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())

        /** Downsample to 32px and blur, off the main thread. */
        private fun makeTiny(source: Bitmap): Bitmap {
            val longest = max(source.width, source.height).coerceAtLeast(1)
            val scale = 32f / longest
            val w = (source.width * scale).roundToInt().coerceAtLeast(1)
            val h = (source.height * scale).roundToInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(source, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
            boxBlur(small, 3)
            boxBlur(small, 3)
            boxBlur(small, 3)
            return small
        }

        /** One horizontal and one vertical box pass, edges clamped (no dark halo). */
        private fun boxBlur(bmp: Bitmap, radius: Int) {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val tmp = IntArray(w * h)
            pass(px, tmp, w, h, radius, true)
            pass(tmp, px, w, h, radius, false)
            bmp.setPixels(px, 0, w, 0, 0, w, h)
        }

        private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            for (line in 0 until lines) {
                for (i in 0 until len) {
                    var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                    for (k in -r..r) {
                        val j = (i + k).coerceIn(0, len - 1)
                        val p = if (horizontal) src[line * w + j] else src[j * w + line]
                        a += (p ushr 24) and 0xFF; rr += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
                        n++
                    }
                    val out = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
                    if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
                }
            }
        }
    }

    var recipe: Recipe = PAGE
        set(value) { field = value; invalidate() }

    private var tiny: Bitmap? = null
    private var coverKey: String? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val scrimPaint = Paint()
    private val glowRect = RectF()
    private val srcRect = Rect()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Lights the surface with this cover; null clears it (no cover = no glow). */
    fun setCover(coverArtId: String?, type: CustomGlideRequest.ResourceType) {
        if (coverArtId.isNullOrEmpty()) {
            coverKey = null; tiny = null; invalidate(); return
        }
        val builder = CustomGlideRequest.Builder.from(context, coverArtId, type)
        val key = builder.cacheKey() ?: coverArtId
        if (key == coverKey && tiny != null) return
        coverKey = key
        tinyCache.get(key)?.let { tiny = it; invalidate(); return }
        builder.buildBitmap(96).into(object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                val copy = resource.copy(Bitmap.Config.ARGB_8888, false) ?: return
                blurrer.execute {
                    val made = makeTiny(copy)
                    tinyCache.put(key, made)
                    main.post { if (coverKey == key) { tiny = made; invalidate() } }
                }
            }

            override fun onLoadCleared(placeholder: Drawable?) {}
        })
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val density = resources.displayMetrics.density

        tiny?.let { bmp ->
            val side = w * recipe.sizeFactor
            val top = h * recipe.topFactor + recipe.topOffsetDp * density
            glowRect.set((w - side) / 2f, top, (w + side) / 2f, top + side)
            srcRect.set(0, 0, bmp.width, bmp.height)
            val layer = canvas.saveLayer(glowRect, null)
            bitmapPaint.alpha = (recipe.alpha * 255).roundToInt()
            canvas.drawBitmap(bmp, srcRect, glowRect, bitmapPaint)
            if (recipe.bottomFade > 0f) {
                // ease the glow's foot out (a smoothstep), so neither end shows as a line
                val start = glowRect.top + glowRect.height() * (1f - recipe.bottomFade.coerceAtMost(1f))
                val ts = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
                val colors = IntArray(ts.size) { i ->
                    val t = ts[i]; val eased = t * t * (3 - 2 * t)
                    ((1f - eased) * 255).roundToInt() shl 24
                }
                val positions = FloatArray(ts.size + 1)
                val stops = IntArray(ts.size + 1)
                stops[0] = 0xFF shl 24; positions[0] = 0f
                val span = glowRect.bottom - glowRect.top
                for (i in ts.indices) {
                    stops[i + 1] = colors[i]
                    positions[i + 1] = ((start + (glowRect.bottom - start) * ts[i]) - glowRect.top) / span
                }
                fadePaint.shader = LinearGradient(0f, glowRect.top, 0f, glowRect.bottom, stops, positions, Shader.TileMode.CLAMP)
                canvas.drawRect(glowRect, fadePaint)
            }
            canvas.restoreToCount(layer)
        }

        val stops = recipe.scrim
        if (stops.isNotEmpty()) {
            val colors = IntArray(stops.size) { i ->
                val c = ContextCompat.getColor(context, stops[i].first)
                (c and 0x00FFFFFF) or ((stops[i].second * 255).roundToInt() shl 24)
            }
            val positions = FloatArray(stops.size) { stops[it].third }
            scrimPaint.shader = if (recipe.horizontal) {
                LinearGradient(0f, 0f, w, 0f, colors, positions, Shader.TileMode.CLAMP)
            } else {
                LinearGradient(0f, 0f, 0f, h, colors, positions, Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, w, h, scrimPaint)
        }
    }
}
