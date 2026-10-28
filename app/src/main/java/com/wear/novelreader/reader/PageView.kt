package com.wear.novelreader.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View

class PageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var pageLayout: StaticLayout? = null

    var pageDescription: String = ""

    fun show(layout: Layout, lines: PageLines) {
        val start = layout.getLineStart(lines.startLine)
        val end = if (lines.endLine >= layout.lineCount) {
            layout.text.length
        } else {
            layout.getLineStart(lines.endLine)
        }
        val pageText = layout.text.subSequence(start, end)
        pageDescription = pageText.toString()
        contentDescription = pageDescription

        val paint = TextPaint(layout.paint)
        pageLayout = StaticLayout.Builder.obtain(pageText, 0, pageText.length, paint, layout.width)
            .setAlignment(layout.alignment)
            .setLineSpacing(layout.spacingAdd, layout.spacingMultiplier)
            .setIncludePad(false)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        invalidate()
    }

    fun clear() {
        pageLayout = null
        contentDescription = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layout = pageLayout ?: return
        val left = paddingLeft
        val top = paddingTop
        val right = width - paddingRight
        val bottom = height - paddingBottom
        if (right <= left || bottom <= top) return

        val clipBottom = minOf(bottom, top + layout.height)
        val save = canvas.save()
        canvas.clipRect(left, top, right, clipBottom)
        canvas.translate(left.toFloat(), top.toFloat())
        layout.draw(canvas)
        canvas.restoreToCount(save)
    }
}
