package com.cappielloantonio.tempo.util

import android.graphics.drawable.ColorDrawable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.View
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.ItemRadioPlayBinding

/**
 * A song row's quiet stand-in while its read is out: a square and two bars where its cover, title
 * and artist will be, drawn in the row's own parts so it is exactly as tall. The station page's
 * On air and Search's ON CASSETTE wait in it (BUG-1157), so a row that arrives takes its place and
 * nothing moves.
 */
object CassetteWaitingRow {
    @JvmStatic
    fun bind(row: ItemRadioPlayBinding) {
        val quiet = ContextCompat.getColor(row.root.context, R.color.cs_raised)
        Glide.with(row.root).clear(row.playCover)
        row.playCover.setImageDrawable(ColorDrawable(quiet))
        row.playTitle.text = bar(18, quiet)
        row.playArtist.text = bar(11, quiet)
        row.playWhen.text = ""
        row.root.setOnClickListener(null)
        row.root.setOnLongClickListener(null)
        row.root.isEnabled = false
        row.root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** Undoes `bind`'s quiet parts for a row that now shows a real song. */
    @JvmStatic
    fun release(row: ItemRadioPlayBinding) {
        row.root.isEnabled = true
        row.root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    /** A bar `width` figure spaces long, filled with `color`. */
    private fun bar(width: Int, color: Int): CharSequence {
        val s = SpannableString(" ".repeat(width))
        s.setSpan(BackgroundColorSpan(color), 0, s.length, 0)
        return s
    }
}
