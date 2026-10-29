package com.wear.novelreader.reader

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.GridLayout
import androidx.activity.ComponentActivity
import com.wear.novelreader.NovelApp
import com.wear.novelreader.R
import com.wear.novelreader.databinding.ActivityFontColorBinding
import com.wear.novelreader.ui.EdgeBackTouchListener
import com.wear.novelreader.ui.WearStatusBar

class FontColorActivity : ComponentActivity() {

    private lateinit var binding: ActivityFontColorBinding
    private var selectedColor = 0
    private val swatches = mutableListOf<Pair<ColorChoice, Button>>()

    private val choices = listOf(
        ColorChoice(0, R.string.font_color_default, R.string.font_color_default_short, true),
        ColorChoice(Color.WHITE, R.string.font_color_white, R.string.font_color_white_short),
        ColorChoice(Color.parseColor("#F2E6D0"), R.string.font_color_warm, R.string.font_color_warm_short),
        ColorChoice(Color.parseColor("#FFD54F"), R.string.font_color_yellow, R.string.font_color_yellow_short),
        ColorChoice(Color.parseColor("#A5D6A7"), R.string.font_color_green, R.string.font_color_green_short),
        ColorChoice(Color.parseColor("#80DEEA"), R.string.font_color_cyan, R.string.font_color_cyan_short),
        ColorChoice(Color.parseColor("#90CAF9"), R.string.font_color_blue, R.string.font_color_blue_short),
        ColorChoice(Color.parseColor("#F48FB1"), R.string.font_color_pink, R.string.font_color_pink_short),
        ColorChoice(Color.parseColor("#BDBDBD"), R.string.font_color_gray, R.string.font_color_gray_short)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as NovelApp
        val settings = app.prefs.load()
        val theme = settings.theme
        window.setBackgroundDrawable(ColorDrawable(theme.bg))
        binding = ActivityFontColorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ReaderWindowHelper.applyKeepScreenOn(window, true)
        ReaderWindowHelper.applyBrightness(window, settings.brightness)
        WearStatusBar.attach(binding.root, binding.contentLayout).applyColors(
            theme.text,
            Color.argb(
                0x66,
                Color.red(theme.surface),
                Color.green(theme.surface),
                Color.blue(theme.surface)
            )
        )
        binding.root.setOnTouchListener(EdgeBackTouchListener(this))

        binding.backButton.imageTintList = ColorStateList.valueOf(theme.text)
        binding.backButton.setOnClickListener { finish() }
        binding.title.setTextColor(theme.text)
        binding.previewHint.setTextColor(theme.secondary)
        binding.doneButton.setTextColor(theme.text)
        binding.doneButton.backgroundTintList = ColorStateList.valueOf(theme.surface)
        binding.previewLine.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            settings.fontSizeSp.toFloat()
        )
        binding.previewLine.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(theme.bg)
            setStroke(
                dp(1),
                Color.argb(
                    0x66,
                    Color.red(theme.text),
                    Color.green(theme.text),
                    Color.blue(theme.text)
                )
            )
        }

        selectedColor = settings.fontColor ?: 0
        buildSwatches(theme.surface, theme.text)
        updatePreview(theme.text)

        binding.doneButton.setOnClickListener {
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_FONT_COLOR, selectedColor)
            )
            finish()
        }
    }

    private fun buildSwatches(surface: Int, defaultText: Int) {
        choices.forEach { choice ->
            val button = Button(this).apply {
                isAllCaps = false
                textSize = 10f
                gravity = Gravity.CENTER
                minWidth = 0
                minHeight = 0
                stateListAnimator = null
                backgroundTintList = null
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    selectedColor = if (choice.followsTheme) 0 else choice.color
                    updatePreview(defaultText)
                    refreshSwatches(surface, defaultText)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(28)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            binding.colorGrid.addView(button, params)
            swatches += choice to button
        }
        refreshSwatches(surface, defaultText)
    }

    private fun refreshSwatches(surface: Int, defaultText: Int) {
        swatches.forEach { (choice, button) ->
            val selected = if (choice.followsTheme) {
                selectedColor == 0
            } else {
                selectedColor == choice.color
            }
            val fill = if (choice.followsTheme) surface else choice.color
            val textColor = if (choice.followsTheme) {
                defaultText
            } else {
                contrastColor(fill)
            }
            button.text = getString(choice.shortLabelRes)
            button.setTextColor(textColor)
            button.contentDescription = if (selected) {
                getString(R.string.font_color_selected, getString(choice.labelRes))
            } else {
                getString(choice.labelRes)
            }
            button.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
                if (selected) {
                    setStroke(dp(2), contrastColor(fill))
                }
            }
            button.scaleX = if (selected) 0.9f else 1f
            button.scaleY = if (selected) 0.9f else 1f
        }
        binding.previewHint.text = getString(
            R.string.font_color_preview_current,
            colorName()
        )
    }

    private fun updatePreview(defaultText: Int) {
        binding.previewLine.setTextColor(
            if (selectedColor == 0) defaultText else selectedColor
        )
    }

    private fun colorName(): String {
        if (selectedColor == 0) {
            return getString(R.string.font_color_default)
        }
        return choices.firstOrNull { !it.followsTheme && it.color == selectedColor }
            ?.let { getString(it.labelRes) }
            ?: getString(R.string.font_color_custom)
    }

    private fun contrastColor(color: Int): Int {
        return if (Color.luminance(color) > 0.5f) Color.BLACK else Color.WHITE
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private data class ColorChoice(
        val color: Int,
        val labelRes: Int,
        val shortLabelRes: Int,
        val followsTheme: Boolean = false
    )

    companion object {
        const val EXTRA_FONT_COLOR = "font_color"
    }
}
