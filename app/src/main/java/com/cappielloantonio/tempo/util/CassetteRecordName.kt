package com.cappielloantonio.tempo.util

import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import com.cappielloantonio.tempo.R
import java.util.WeakHashMap

/**
 * A record's name on its own page (album, playlist, artist): E-record-head, the founder's
 * pick on 2026-11-24 ("B. Grows up") and their 2026-11-27 amendment ("No matter how long an album
 * name is, we should always print it in full"), as the iPhone's heads draw it (design wave 6).
 *
 * The name sits on the foot of a room three of its lines tall, so the artist line, the facts and the
 * keys under it stay in one place from record to record and extra lines climb. A name that needs more
 * than three lines steps down to the section heading's size (the kit's shout), its floor. Past that it
 * wraps for every line it needs and the head grows: never an ellipsis on a record's own page. Shelves
 * and grids still cut a long name to their lines.
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
        // every line it needs, from the first frame
        view.maxLines = Int.MAX_VALUE
        view.ellipsize = null
        val base = baseSizes.getOrPut(view) { view.textSize }
        if (view.width > 0) apply(view, text, base) else view.post { apply(view, text, base) }
    }

    private fun apply(view: TextView, text: CharSequence, base: Float) {
        val width = view.width - view.totalPaddingLeft - view.totalPaddingRight
        if (width <= 0) return
        val shown = view.transformationMethod?.getTransformation(text, view) ?: text
        val room = height(view, "A\nA\nA", base, width)
        val fits = height(view, shown, base, width) <= room + 1
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, if (fits) base else view.resources.getDimension(R.dimen.kit_type_shout))
        // the room is a floor, not a box: the view measures its own lines, so a name that needs more
        // grows the head and is never cut, whatever this measure missed
        view.minHeight = room + view.paddingTop + view.paddingBottom
        val params = view.layoutParams
        if (params != null && params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
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
