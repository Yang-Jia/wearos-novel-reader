package com.wear.novelreader.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.wear.novelreader.R

class ImportUiController(private val activity: Activity) {

    private var dialog: AlertDialog? = null
    private var progress: ProgressBar? = null
    private var label: TextView? = null

    fun show(total: Int) {
        if (dialog != null) return
        val density = activity.resources.displayMetrics.density
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(),
                (8 * density).toInt(),
                (16 * density).toInt(),
                0
            )
        }
        val progressLabel = TextView(activity).apply {
            setTextColor(activity.getColor(R.color.text_primary))
            textSize = 12f
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        val progressBar = ProgressBar(
            activity,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = total.coerceAtLeast(1)
            progress = 0
            progressTintList = ColorStateList.valueOf(activity.getColor(R.color.accent))
        }
        container.addView(progressLabel)
        container.addView(progressBar)
        label = progressLabel
        progress = progressBar
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.importing_title)
            .setView(container)
            .setCancelable(false)
            .create()
            .also { it.show() }
        update(0, total, "")
    }

    fun update(index: Int, total: Int, name: String) {
        progress?.max = total.coerceAtLeast(1)
        progress?.progress = index
        label?.text = activity.getString(
            R.string.importing_progress,
            index.coerceAtMost(total),
            total,
            name
        )
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
        progress = null
        label = null
    }
}
