package com.wear.novelreader.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.wear.widget.BoxInsetLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WearStatusBar(context: Context) : LinearLayout(context) {

    private val clock = TextView(context).apply {
        text = "--:--"
        setTextColor(STATUS_TEXT_COLOR)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        includeFontPadding = false
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val battery = TextView(context).apply {
        text = "--"
        setTextColor(STATUS_TEXT_COLOR)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        includeFontPadding = false
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private var content: View? = null
    private var root: ViewGroup? = null
    private var receiverRegistered = false
    private var positionPosted = false
    private var overlayHidden = false
    private var lastBatteryPercent: Int? = null
    private var lastClockText: String? = null

    private val visibleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> updateBattery(intent)
                Intent.ACTION_TIME_TICK,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED -> updateClock()
            }
        }
    }

    private val visibleFilter = IntentFilter().apply {
        addAction(Intent.ACTION_BATTERY_CHANGED)
        addAction(Intent.ACTION_TIME_TICK)
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }

    private val contentLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        requestPosition()
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(2), dp(8), dp(2))
        background = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(PILL_COLOR)
        }
        isClickable = false
        isFocusable = false
        visibility = if (isRoundScreen()) View.VISIBLE else View.GONE
        addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(
            battery,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            }
        )
    }

    fun bind(rootView: ViewGroup, contentView: View) {
        root = rootView
        content = contentView
        contentView.addOnLayoutChangeListener(contentLayoutListener)
        requestPosition()
    }

    fun setOverlayHidden(hidden: Boolean) {
        overlayHidden = hidden
        visibility = if (hidden || !isRoundScreen()) View.GONE else View.VISIBLE
    }

    fun applyColors(textColor: Int, pillColor: Int) {
        clock.setTextColor(textColor)
        battery.setTextColor(textColor)
        background = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(pillColor)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateClock()
        updateBatteryFromManager()
        if (windowVisibility == View.VISIBLE) registerVisibleReceiver()
    }

    override fun onDetachedFromWindow() {
        unregisterVisibleReceiver()
        content?.removeOnLayoutChangeListener(contentLayoutListener)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            registerVisibleReceiver()
            updateClock()
        } else {
            unregisterVisibleReceiver()
        }
    }

    private fun registerVisibleReceiver() {
        if (receiverRegistered || !isRoundScreen()) return
        try {
            ContextCompat.registerReceiver(
                context,
                visibleReceiver,
                visibleFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun unregisterVisibleReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(visibleReceiver) }
        receiverRegistered = false
    }

    private fun updateClock() {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        val text = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
        if (lastClockText == text) return
        lastClockText = text
        clock.text = text
    }

    private fun updateBatteryFromManager() {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val percent = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        setBatteryPercent(percent)
    }

    private fun updateBattery(intent: Intent?) {
        intent ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            setBatteryPercent(level * 100 / scale)
        }
    }

    private fun setBatteryPercent(percent: Int) {
        if (lastBatteryPercent == percent) return
        lastBatteryPercent = percent
        battery.text = if (percent in 0..100) "$percent%" else "--"
    }

    private fun requestPosition() {
        if (positionPosted) return
        positionPosted = true
        post {
            positionPosted = false
            updatePosition()
        }
    }

    private fun updatePosition() {
        val rootView = root ?: return
        val contentView = content ?: return
        if (!isRoundScreen() || overlayHidden) {
            visibility = View.GONE
            return
        }
        if (height <= 0) {
            requestPosition()
            return
        }
        val available = contentView.top - rootView.paddingTop
        val gap = dp(2)
        val topMargin = available - height - gap
        if (topMargin < 0) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        val params = layoutParams as? BoxInsetLayout.LayoutParams ?: return
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        if (params.topMargin != topMargin) {
            params.topMargin = topMargin
            layoutParams = params
        }
    }

    private fun isRoundScreen(): Boolean {
        return resources.configuration.isScreenRound
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private val STATUS_TEXT_COLOR = Color.parseColor("#E6E6E6")
        private val PILL_COLOR = Color.parseColor("#66000000")

        fun attach(root: ViewGroup, content: View): WearStatusBar {
            val bar = WearStatusBar(root.context)
            val params = BoxInsetLayout.LayoutParams(
                BoxInsetLayout.LayoutParams.WRAP_CONTENT,
                BoxInsetLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            root.addView(bar, params)
            bar.bind(root, content)
            return bar
        }
    }
}


