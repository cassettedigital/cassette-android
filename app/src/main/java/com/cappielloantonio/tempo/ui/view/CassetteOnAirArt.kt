package com.cappielloantonio.tempo.ui.view

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.cappielloantonio.tempo.R
import digital.cassette.kit.CassetteKitTokens
import kotlin.math.roundToInt

/**
 * cassette: the picture square of a card in On now (RadioDial.module.css .onAirArt, the Mac's Radio page). The
 * station's mark fills it as a cover does, square inside the card, whose own corners round it, until what the
 * station is playing has a cover that has fully arrived; then the cover rises slowly from a touch large while
 * the mark glides down into the corner, small and rounded like an app icon (the kit's shape.station), ringed in
 * the card's colour, a beat behind it (the founder, 2026-11-07: "a nice, gentle transition in"; 2026-11-16: the
 * mark takes the card's corners).
 *
 * The mark and its ring keep their full size and shrink by scale, so the logo and the word art stay sharp and
 * whole on the way down.
 */
class CassetteOnAirArt @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density

    /** What's playing: the record or the show's picture. */
    val cover: ImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The station's logo. */
    val mark: ImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The station's name as word art, on its own colour, when it has no logo. */
    val markWord: TextView = TextView(context).apply {
        typeface = ResourcesCompat.getFont(context, R.font.barlow_condensed_extrabold) ?: Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(Color.parseColor("#FFF3EADB"))
        isAllCaps = true
        maxLines = 3
        gravity = Gravity.BOTTOM or Gravity.START
        val pad = (10 * density).roundToInt()
        setPadding(pad, pad, pad, pad)
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val ringFill = GradientDrawable().apply { setColor(ContextCompat.getColor(context, R.color.cs_raised)) }
    private val ring = FrameLayout(context)
    private val markBox = FrameLayout(context)
    private var markRadius = 0f
    private var glide = 0f
    private var settle = 0f
    private val running = mutableListOf<Animator>()

    /** The cover is up and the mark sits on its corner. */
    var isWorn = false
        private set

    init {
        clipChildren = true
        markBox.clipToOutline = true
        markBox.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, markRadius)
            }
        }
        markBox.addView(mark, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        markBox.addView(markWord, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        ring.background = ringFill
        ring.addView(markBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(cover, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ring, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        place()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        place()
    }

    /** A station with no logo: its name on its own colour. */
    fun showWord(name: String, color: Int) {
        mark.setImageDrawable(ColorDrawable(color))
        markWord.text = name
        markWord.visibility = View.VISIBLE
    }

    /** A logo is coming: the word art steps aside. */
    fun showLogo() {
        markWord.visibility = View.GONE
    }

    /** Back to the whole mark with nothing worn, for a card drawn again for another station. */
    fun reset() {
        stop()
        isWorn = false
        cover.setImageDrawable(null)
        cover.alpha = 0f
        cover.scaleX = 1f
        cover.scaleY = 1f
        glide = 0f
        settle = 0f
        place()
    }

    /** The cover has arrived: it rises under the mark, and the mark glides down into the corner. */
    fun wear(animated: Boolean) {
        if (isWorn) return
        isWorn = true
        stop()
        if (!animated) {
            cover.alpha = 1f
            cover.scaleX = 1f
            cover.scaleY = 1f
            glide = 1f
            settle = 1f
            place()
            return
        }
        cover.alpha = 0f
        cover.scaleX = 1.06f
        cover.scaleY = 1.06f
        run(ObjectAnimator.ofFloat(cover, View.ALPHA, 0f, 1f).apply {
            duration = 800
            interpolator = EASE
        })
        run(ObjectAnimator.ofPropertyValuesHolder(cover,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.06f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.06f, 1f)).apply {
            duration = 1400
            interpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
        })
        run(ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            startDelay = 200
            interpolator = PathInterpolator(0.65f, 0f, 0.35f, 1f)
            addUpdateListener { glide = it.animatedValue as Float; place() }
        })
        // its ring and shadow come last
        run(ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            startDelay = 700
            interpolator = EASE
            addUpdateListener { settle = it.animatedValue as Float; place() }
        })
    }

    /** What's playing has no picture any more: the mark takes the square back. */
    fun unwear(animated: Boolean) {
        if (!isWorn) return
        isWorn = false
        stop()
        if (!animated) {
            cover.alpha = 0f
            glide = 0f
            settle = 0f
            place()
            return
        }
        run(ObjectAnimator.ofFloat(cover, View.ALPHA, cover.alpha, 0f).apply { duration = 600 })
        val from = glide
        val fromSettle = settle
        run(ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 600
            interpolator = EASE
            addUpdateListener {
                val t = it.animatedValue as Float
                glide = from * t
                settle = fromSettle * t
                place()
            }
        })
    }

    private fun run(animator: Animator) {
        running.add(animator)
        animator.start()
    }

    private fun stop() {
        running.forEach { it.cancel() }
        running.clear()
        cover.animate().cancel()
    }

    /**
     * Lays the mark out for the glide (0, the whole square; 1, a 36dp badge 8dp in from the left and 8dp up from
     * the bottom) and the ring for the settle (its 2dp of the card's colour and its shadow).
     */
    private fun place() {
        val side = (if (width > 0) width else (112 * density).roundToInt()).toFloat()
        val badge = 36 * density
        val inset = 8 * density
        val ringWidth = 2 * density
        val ringSide = badge + 2 * ringWidth
        // the ring (with the mark inside it) shrinks from the whole square to the badge and its ring
        val ringScale = lerp(1f, ringSide / side, glide)
        ring.pivotX = 0f
        ring.pivotY = 0f
        ring.scaleX = ringScale
        ring.scaleY = ringScale
        ring.translationX = lerp(0f, inset - ringWidth, glide)
        ring.translationY = lerp(0f, side - inset - badge - ringWidth, glide)
        // the mark leaves the ring's 2dp showing around it
        val markScale = lerp(1f, badge / ringSide, glide)
        markBox.pivotX = side / 2f
        markBox.pivotY = side / 2f
        markBox.scaleX = markScale
        markBox.scaleY = markScale
        // corners as they show on screen: none while whole (the card rounds it), the station's shape as a badge
        markRadius = lerp(0f, CassetteKitTokens.Shape.station * badge, glide) / (ringScale * markScale)
        markBox.invalidateOutline()
        ringFill.cornerRadius = (CassetteKitTokens.Shape.station * badge + ringWidth) * side / ringSide
        ringFill.alpha = (settle * 255).roundToInt()
        ring.elevation = settle * 4 * density
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        /** CSS's `ease`. */
        val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
    }
}
