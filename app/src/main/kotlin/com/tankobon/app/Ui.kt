package com.tankobon.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The look: a DS-era training book. Ruled paper, pale-blue glossy bands, chunky rounded
 * buttons with dark outlines, bold black type.
 */
object Ui {
    val PAPER = Color.parseColor("#FBFBF7")
    val RULE = Color.parseColor("#DDE7F0")
    val INK = Color.parseColor("#2E3238")
    val SOFT = Color.parseColor("#6E7680")
    val LINE = Color.parseColor("#3B4048")
    val SKY = Color.parseColor("#D7E8F7")
    val SKY_DEEP = Color.parseColor("#9CC3E6")
    val BLUE = Color.parseColor("#2F6FD1")
    val BLUE_DARK = Color.parseColor("#2257A8")
    val ORANGE = Color.parseColor("#F39A2E")
    val ORANGE_DARK = Color.parseColor("#D97C12")

    fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
    fun dp(ctx: Context, v: Int) = dp(ctx, v.toFloat()).toInt()

    /** Ruled exercise-book paper. */
    class Paper(private val spacing: Float, private val margin: Float) : Drawable() {
        private val rule = Paint().apply { color = RULE; strokeWidth = 2f }
        private val red = Paint().apply { color = Color.parseColor("#F2C9C4"); strokeWidth = 2f }
        override fun draw(c: Canvas) {
            c.drawColor(PAPER)
            var y = spacing
            while (y < bounds.height()) { c.drawLine(0f, y, bounds.width().toFloat(), y, rule); y += spacing }
            if (margin > 0) c.drawLine(margin, 0f, margin, bounds.height().toFloat(), red)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
    }

    fun paper(ctx: Context, margin: Boolean = false) = Paper(dp(ctx, 34f), if (margin) dp(ctx, 26f) else 0f)

    private fun rounded(ctx: Context, top: Int, bottom: Int, stroke: Int, radius: Float, width: Float = 2.5f) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = dp(ctx, radius); setStroke(dp(ctx, width).toInt(), stroke)
        }

    /** Glossy pale-blue band: screen titles. */
    fun band(ctx: Context, title: String, sub: String? = null) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, Color.WHITE, SKY_DEEP, LINE, 16f)
        setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
        addView(text(ctx, title, 22f, INK, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (sub != null) addView(text(ctx, sub, 14f, INK))
    }

    enum class Kind { PLAIN, BLUE, ORANGE }

    /** A chunky outlined button that darkens when pressed or focused. */
    fun button(ctx: Context, label: String, kind: Kind = Kind.PLAIN, size: Float = 18f, onClick: () -> Unit) = Button(ctx).apply {
        text = label; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null; elevation = 0f
        minHeight = dp(ctx, 56); minimumHeight = dp(ctx, 56)
        setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
        val (up, down, fg) = when (kind) {
            Kind.PLAIN -> Triple(rounded(ctx, Color.WHITE, Color.parseColor("#E4E8EE"), LINE, 14f),
                rounded(ctx, SKY, SKY_DEEP, LINE, 14f), INK)
            Kind.BLUE -> Triple(rounded(ctx, Color.parseColor("#4D8BE6"), BLUE, BLUE_DARK, 14f),
                rounded(ctx, BLUE, BLUE_DARK, LINE, 14f), Color.WHITE)
            Kind.ORANGE -> Triple(rounded(ctx, Color.parseColor("#FFB65C"), ORANGE, ORANGE_DARK, 14f),
                rounded(ctx, ORANGE, ORANGE_DARK, LINE, 14f), Color.WHITE)
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), down)
            addState(intArrayOf(android.R.attr.state_focused), down)
            addState(intArrayOf(), up)
        }
        setTextColor(fg)
        setOnClickListener { onClick() }
    }

    fun text(ctx: Context, s: String, size: Float, color: Int = INK, bold: Boolean = false) = TextView(ctx).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** White card with an outline: covers on the shelf, rows in lists. */
    fun card(ctx: Context, selected: Boolean = false) =
        rounded(ctx, if (selected) SKY else Color.WHITE, if (selected) SKY_DEEP else Color.WHITE, if (selected) BLUE else LINE, 12f,
            if (selected) 3.5f else 2f)

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    fun row(ctx: Context, gapDp: Int, vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(ctx, gapDp)
            })
        }
    }

    fun gap(ctx: Context, h: Int) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(ctx, h)) }
}

/**
 * Lays its one child out turned by [degrees] (0, 90, -90 or 180), with width and height
 * swapped for quarter turns. Touches are turned with it, so buttons still work.
 */
class Turned(ctx: Context) : FrameLayout(ctx) {
    var degrees = 0
        set(v) { if (field != v) { field = v; requestLayout() } }

    private val quarter get() = degrees == 90 || degrees == -90

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec); val h = MeasureSpec.getSize(heightSpec)
        setMeasuredDimension(w, h)
        val (cw, ch) = if (quarter) h to w else w to h
        getChildAt(0)?.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val c = getChildAt(0) ?: return
        val w = r - l; val h = b - t
        c.layout(0, 0, c.measuredWidth, c.measuredHeight)
        c.pivotX = 0f; c.pivotY = 0f
        c.rotation = degrees.toFloat()
        when (degrees) {
            90 -> { c.translationX = w.toFloat(); c.translationY = 0f }
            -90 -> { c.translationX = 0f; c.translationY = h.toFloat() }
            180 -> { c.translationX = w.toFloat(); c.translationY = h.toFloat() }
            else -> { c.translationX = 0f; c.translationY = 0f }
        }
    }
}
