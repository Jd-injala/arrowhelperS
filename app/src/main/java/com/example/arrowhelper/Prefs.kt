package com.example.arrowhelper

import android.content.Context
import android.graphics.RectF

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("arrow_helper", Context.MODE_PRIVATE)

    fun rows(c: Context) = sp(c).getInt("rows", 6)
    fun cols(c: Context) = sp(c).getInt("cols", 6)
    /** How different (0–765) a pixel must be from the cell background to count as arrow. */
    fun threshold(c: Context) = sp(c).getInt("threshold", 90)
    /** Pause between taps in Auto mode, so the game's animation can finish. */
    fun tapDelay(c: Context) = sp(c).getInt("tapDelay", 450).toLong()

    fun save(c: Context, rows: Int, cols: Int, threshold: Int, tapDelay: Int) {
        sp(c).edit().putInt("rows", rows).putInt("cols", cols)
            .putInt("threshold", threshold).putInt("tapDelay", tapDelay).apply()
    }

    /** Grid bounds in screen pixels, or null if not set yet. */
    fun gridRect(c: Context): RectF? {
        val s = sp(c)
        if (!s.contains("gl")) return null
        return RectF(s.getFloat("gl", 0f), s.getFloat("gt", 0f), s.getFloat("gr", 0f), s.getFloat("gb", 0f))
    }

    fun setGridRect(c: Context, r: RectF) {
        sp(c).edit().putFloat("gl", r.left).putFloat("gt", r.top)
            .putFloat("gr", r.right).putFloat("gb", r.bottom).apply()
    }
}
