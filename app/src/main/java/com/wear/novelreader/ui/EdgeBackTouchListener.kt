package com.wear.novelreader.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import kotlin.math.abs

class EdgeBackTouchListener(private val activity: ComponentActivity) : View.OnTouchListener {

    private var tracking = false
    private var horizontal = false
    private var downX = 0f
    private var downY = 0f

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val config = ViewConfiguration.get(activity)
        val edgeWidth = maxOf(
            config.scaledEdgeSlop.toFloat(),
            20f * activity.resources.displayMetrics.density
        )
        val threshold = config.scaledTouchSlop * 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.pointerCount == 1 && event.rawX <= edgeWidth) {
                    tracking = true
                    horizontal = false
                    downX = event.rawX
                    downY = event.rawY
                    return true
                }
                tracking = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (dx > threshold && dx > abs(dy)) {
                    horizontal = true
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                tracking = false
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (horizontal || (dx > threshold && dx > abs(dy))) {
                    activity.onBackPressedDispatcher.onBackPressed()
                }
                horizontal = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (tracking) {
                    tracking = false
                    horizontal = false
                    return true
                }
            }
        }
        return false
    }
}
