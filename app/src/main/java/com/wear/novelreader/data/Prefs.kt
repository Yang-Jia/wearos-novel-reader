package com.wear.novelreader.data

import android.content.Context
import android.graphics.Color

enum class ReadingMode { PAGE, SCROLL }

enum class ReaderTheme(
    val bg: Int,
    val text: Int,
    val secondary: Int,
    val surface: Int
) {
    DARK(
        Color.parseColor("#000000"),
        Color.parseColor("#D6D6D6"),
        Color.parseColor("#8A8A8A"),
        Color.parseColor("#171717")
    ),
    GRAY(
        Color.parseColor("#000000"),
        Color.parseColor("#C9C9C9"),
        Color.parseColor("#7F7F7F"),
        Color.parseColor("#262626")
    ),
    SEPIA(
        Color.parseColor("#F2E8D5"),
        Color.parseColor("#3B2F23"),
        Color.parseColor("#7A6A55"),
        Color.parseColor("#E8DCC8")
    );

    fun next(): ReaderTheme = entries[(ordinal + 1) % entries.size]
}

data class ReaderSettings(
    val mode: ReadingMode = ReadingMode.PAGE,
    val fontSizeSp: Int = 14,
    val lineSpacing: Float = 1.2f,
    val theme: ReaderTheme = ReaderTheme.DARK,
    val brightness: Float = -1f,
    val keepScreenOn: Boolean = true,
    val reverseTaps: Boolean = false,
    val fontColor: Int? = null
) {
    val textColor: Int
        get() = fontColor ?: theme.text
}

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)

    fun load(): ReaderSettings {
        val mode = try {
            ReadingMode.valueOf(sp.getString("mode", ReadingMode.PAGE.name)!!)
        } catch (_: Exception) {
            ReadingMode.PAGE
        }
        val theme = try {
            ReaderTheme.valueOf(sp.getString("theme", ReaderTheme.DARK.name)!!)
        } catch (_: Exception) {
            ReaderTheme.DARK
        }
        val brightnessRaw = sp.getFloat("brightness", -1f)
        val brightness = if (brightnessRaw < 0f) -1f else brightnessRaw.coerceIn(0.1f, 1f)
        val fontColor = if (sp.contains("fontColor")) sp.getInt("fontColor", theme.text) else null
        return ReaderSettings(
            mode = mode,
            fontSizeSp = sp.getInt("fontSize", 14).coerceIn(12, 28),
            lineSpacing = sp.getFloat("lineSpacing", 1.2f).coerceIn(1.1f, 1.9f),
            theme = theme,
            brightness = brightness,
            keepScreenOn = sp.getBoolean("keepScreenOn", true),
            reverseTaps = sp.getBoolean("reverseTaps", false),
            fontColor = fontColor
        )
    }

    fun save(settings: ReaderSettings) {
        val editor = sp.edit()
            .putString("mode", settings.mode.name)
            .putInt("fontSize", settings.fontSizeSp)
            .putFloat("lineSpacing", settings.lineSpacing)
            .putString("theme", settings.theme.name)
            .putFloat("brightness", settings.brightness)
            .putBoolean("keepScreenOn", settings.keepScreenOn)
            .putBoolean("reverseTaps", settings.reverseTaps)
        if (settings.fontColor == null) {
            editor.remove("fontColor")
        } else {
            editor.putInt("fontColor", settings.fontColor)
        }
        editor.apply()
    }
}


