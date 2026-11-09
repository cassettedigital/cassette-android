package com.cappielloantonio.tempo.helper.recyclerview

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** cassette: the gutter between covers in a lettered grid; the letters themselves span the row untouched. */
class CassetteLetteredGridSpacing(private val columns: Int, private val gutter: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val lp = view.layoutParams as? GridLayoutManager.LayoutParams ?: return
        if (lp.spanSize == columns) return
        val column = lp.spanIndex
        outRect.left = gutter * column / columns
        outRect.right = gutter - (column + 1) * gutter / columns
        outRect.bottom = gutter
    }
}
