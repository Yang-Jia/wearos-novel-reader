package com.wear.novelreader.reader

import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wear.novelreader.databinding.ItemChapterScrollBinding

class ChapterScrollAdapter(
    private val textProvider: (Int) -> String?,
    private val onRequestChapter: (Int) -> Unit,
    private val onTap: () -> Unit
) : RecyclerView.Adapter<ChapterScrollAdapter.Holder>() {

    var chapterCount = 0
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    private var textSizeSp = 16f
    private var lineSpacing = 1.2f
    private var textColor = 0

    fun setStyle(textSizeSp: Float, lineSpacing: Float, textColor: Int) {
        if (this.textSizeSp == textSizeSp &&
            this.lineSpacing == lineSpacing &&
            this.textColor == textColor
        ) {
            return
        }
        this.textSizeSp = textSizeSp
        this.lineSpacing = lineSpacing
        this.textColor = textColor
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = chapterCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemChapterScrollBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val text = textProvider(position)
        holder.bind(text, textSizeSp, lineSpacing, textColor)
        if (text == null) {
            onRequestChapter(position)
        }
        holder.binding.root.setOnClickListener { onTap() }
    }

    inner class Holder(val binding: ItemChapterScrollBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(text: String?, sizeSp: Float, spacing: Float, color: Int) {
            binding.chapterPlaceholder.visibility = if (text == null) View.VISIBLE else View.GONE
            binding.chapterText.visibility = if (text == null) View.GONE else View.VISIBLE
            if (text == null) {
                binding.chapterText.text = ""
                return
            }
            binding.chapterText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            binding.chapterText.setTextColor(color)
            binding.chapterText.setLineSpacing(0f, spacing)
            binding.chapterText.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            if (binding.chapterText.text.toString() != text) {
                binding.chapterText.text = text
            }
        }
    }
}
