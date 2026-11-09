package com.cappielloantonio.tempo.util

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.cappielloantonio.tempo.R
import java.util.Locale

/**
 * cassette: the two voices of the October redesign. A Barlow Condensed
 * capitals "shout" followed by a serif italic "answer": ON REPEAT *lately*,
 * UP NEXT *album*, MORE BY *artist*. The shout carries the name, the answer
 * carries the reason, and nothing else is written under a heading (no reason
 * lines, founder 2026-10-28). The Android twin of iOS's CassetteHeading; the
 * answer face is Newsreader, the kit's stand-in for Apple's New York.
 */
object CassetteType {
    private var answerFace: Typeface? = null

    @JvmStatic
    fun answerTypeface(context: Context): Typeface? {
        if (answerFace == null) answerFace = ResourcesCompat.getFont(context, R.font.newsreader)
        return answerFace
    }

    /**
     * Sets [view] to SHOUT *answer*. The view's own text appearance is the shout
     * (Cassette.Role.Shout, PageTitle...); the answer is set in Newsreader italic
     * at [answerSp] in [answerColor]. A null or empty answer leaves the shout alone.
     */
    @JvmStatic
    @JvmOverloads
    fun twoVoice(view: TextView, shout: String, answer: String?, answerColor: Int, answerSp: Int = 0) {
        view.text = twoVoiceText(view.context, shout, answer, answerColor,
            if (answerSp > 0) answerSp else answerSizeFor(view))
    }

    @JvmStatic
    fun twoVoiceText(context: Context, shout: String, answer: String?, answerColor: Int, answerSp: Int): CharSequence {
        val out = SpannableStringBuilder(shout.uppercase(Locale.getDefault()))
        if (answer.isNullOrBlank()) return out
        out.append(' ')
        val start = out.length
        out.append(answer)
        val end = out.length
        answerTypeface(context)?.let { out.setSpan(FaceSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        out.setSpan(AbsoluteSizeSpan(answerSp, true), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(answerColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    /** The answer runs a touch smaller than a big shout, as on the iPhone (25 beside 26). */
    private fun answerSizeFor(view: TextView): Int {
        val sp = view.textSize / view.resources.displayMetrics.scaledDensity
        return when {
            sp >= 40 -> 30
            sp >= 30 -> 25
            sp >= 24 -> 25
            else -> 20
        }
    }

    /** A typeface swap inside a span, for minSdk 24 (TypefaceSpan(Typeface) is API 28). */
    private class FaceSpan(private val face: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) = apply(tp)
        override fun updateMeasureState(tp: TextPaint) = apply(tp)
        private fun apply(paint: Paint) {
            paint.typeface = face
            paint.isFakeBoldText = false
            paint.textSkewX = 0f
        }
    }
}
