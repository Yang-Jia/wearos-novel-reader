package com.wear.novelreader.reader

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import com.wear.novelreader.NovelApp
import com.wear.novelreader.R
import com.wear.novelreader.data.BookMeta
import com.wear.novelreader.data.Chapter
import com.wear.novelreader.databinding.ActivityChapterListBinding
import com.wear.novelreader.databinding.ItemChapterBinding
import com.wear.novelreader.ui.EdgeBackTouchListener
import com.wear.novelreader.ui.WearStatusBar
import androidx.recyclerview.widget.LinearLayoutManager

class ChapterListActivity : ComponentActivity() {

    private lateinit var binding: ActivityChapterListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as NovelApp
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        val book = bookId?.let { app.repository.getBook(it) }
        if (book == null) {
            finish()
            return
        }
        binding = ActivityChapterListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WearStatusBar.attach(binding.root, binding.contentLayout)
        binding.root.setOnTouchListener(EdgeBackTouchListener(this))

        val current = intent.getIntExtra(EXTRA_CURRENT, 0)
            .coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0))
        val progress = app.repository.loadProgress(book.id)
        val passedOffset = intent.getIntExtra(EXTRA_OFFSET, -1)
        val currentOffset = when {
            passedOffset >= 0 -> passedOffset
            progress.chapterIndex == current -> progress.charOffset
            else -> 0
        }
        binding.title.text = "${getString(R.string.chapter_list)} · ${book.chapters.size}"
        binding.backButton.setOnClickListener { finish() }
        val chapterAdapter = ChapterAdapter(book, current, currentOffset) { index ->
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_CHAPTER, index)
            )
            finish()
        }
        binding.chapterList.apply {
            layoutManager = LinearLayoutManager(this@ChapterListActivity)
            itemAnimator = null
            this.adapter = chapterAdapter
        }
        binding.chapterList.scrollToPosition(current)
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CURRENT = "current_chapter"
        const val EXTRA_OFFSET = "char_offset"
        const val EXTRA_CHAPTER = "chapter"
    }
}

private class ChapterAdapter(
    private val book: BookMeta,
    private val current: Int,
    private val currentOffset: Int,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<ChapterAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemChapterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(book.chapters[position], position)
    }

    override fun getItemCount(): Int = book.chapters.size

    inner class Holder(private val binding: ItemChapterBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(chapter: Chapter, position: Int) {
            val context = binding.root.context
            binding.chapterName.text = chapter.title
            val chapterPercent = when {
                position < current -> 100
                position > current -> 0
                else -> if (chapter.chars <= 0) {
                    0
                } else {
                    (currentOffset * 100 / chapter.chars).coerceIn(0, 100)
                }
            }
            binding.chapterProgress.text =
                context.getString(R.string.chapter_progress, chapterPercent)
            val out = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
            binding.chapterName.setBackgroundResource(out.resourceId)
            if (position == current) {
                binding.chapterName.setTextColor(context.getColor(R.color.accent))
                binding.chapterName.setTypeface(null, Typeface.BOLD)
            } else {
                binding.chapterName.setTextColor(context.getColor(R.color.text_primary))
                binding.chapterName.setTypeface(null, Typeface.NORMAL)
            }
            binding.root.setOnClickListener { onClick(position) }
        }
    }
}






