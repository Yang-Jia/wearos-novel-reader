package com.wear.novelreader.reader

import android.view.Window
import android.view.WindowManager

object ReaderWindowHelper {

    fun applyBrightness(window: Window, brightness: Float) {
        val attributes = window.attributes
        attributes.screenBrightness = if (brightness < 0f) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            brightness.coerceIn(0.1f, 1f)
        }
        window.attributes = attributes
    }

    fun applyKeepScreenOn(window: Window, enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
