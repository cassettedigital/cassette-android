package com.cappielloantonio.tempo.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.util.CassetteLetters

/**
 * cassette: the # to Z index down the right edge of the lettered album grid, in
 * quiet ink (the iPhone's section index; on Android the Contacts-style strip).
 * Touch or drag along it to jump; a letter with no records jumps to the next one.
 */
class CassetteLetterIndex @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    fun interface OnLetter {
        fun onLetter(letter: String)
    }

    var onLetter: OnLetter? = null
    private var last: String? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.cs_ink3)
        textAlign = Paint.Align.CENTER
        textSize = 11f * resources.displayMetrics.scaledDensity
        typeface = ResourcesCompat.getFont(context, R.font.barlow_condensed)
    }

    init {
        contentDescription = context.getString(R.string.cassette_letter_index)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val letters = CassetteLetters.LETTERS
        val step = (height - paddingTop - paddingBottom).toFloat() / letters.size
        letters.forEachIndexed { i, l ->
            canvas.drawText(l, width / 2f, paddingTop + step * (i + 0.75f), paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val letters = CassetteLetters.LETTERS
                val step = (height - paddingTop - paddingBottom).toFloat() / letters.size
                val i = ((event.y - paddingTop) / step).toInt().coerceIn(0, letters.size - 1)
                val letter = letters[i]
                if (letter != last) {
                    last = letter
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onLetter?.onLetter(letter)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                last = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
