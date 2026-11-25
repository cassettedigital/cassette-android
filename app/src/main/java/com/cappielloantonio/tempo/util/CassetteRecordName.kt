package com.cappielloantonio.tempo.util

import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import com.cappielloantonio.tempo.R
import java.util.WeakHashMap

/**
 * A record head's name (album, playlist, artist, genre, station): R2-head.long-name, the founder's
 * pick on 2026-11-24 ("B. Grows up"), as the iPhone's heads draw it (design wave 6).
 *
 * The name sits on the foot of a room three of its lines tall, so the artist line, the facts and the
 * keys under it stay in one place from record to record and extra lines climb. A name that needs more
 * than three lines steps down to the section heading's size (the kit's shout) and, past that room,
 * is cut with an ellipsis on its last line; the whole name stays the view's description for TalkBack.
 * It was three lines growing downward, so the keys moved with the name.
 */
object CassetteRecordName {
    /** Each name view's own size from its style, before any step down. */
    private val baseSizes = WeakHashMap<TextView, Float>()

    @JvmStatic
    fun fit(view: TextView, name: CharSequence?) {
        val text = name ?: ""
        view.text = text
        view.contentDescription = text
        view.gravity = Gravity.BOTTOM or Gravity.START
        view.ellipsize = TextUtils.TruncateAt.END
        val base = baseSizes.getOrPut(view) { view.textSize }
        if (view.width > 0) apply(view, text, base) else view.post { apply(view, text, base) }
    }

    private fun apply(view: TextView, text: CharSequence, base: Float) {
        val width = view.width - view.totalPaddingLeft - view.totalPaddingRight
        if (width <= 0) return
        val shown = view.transformationMethod?.getTransformation(text, view) ?: text
        val room = height(view, "A\nA\nA", base, width)
        val step = view.resources.getDimension(R.dimen.kit_type_shout)
        if (height(view, shown, base, width) <= room + 1) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, base)
            view.maxLines = 3
        } else {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, step)
            view.maxLines = maxOf(1, room / height(view, "A", step, width))
        }
        val want = room + view.paddingTop + view.paddingBottom
        val params = view.layoutParams
        if (params != null && params.height != want) {
            params.height = want
            view.layoutParams = params
        }
    }

    /** The height `text` takes at `size` in this view's own paint, spacing and breaking. */
    private fun height(view: TextView, text: CharSequence, size: Float, width: Int): Int {
        val paint = TextPaint(view.paint).apply { textSize = size }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setLineSpacing(view.lineSpacingExtra, view.lineSpacingMultiplier)
            .setIncludePad(view.includeFontPadding)
            .setBreakStrategy(view.breakStrategy)
            .build()
            .height
    }
}
