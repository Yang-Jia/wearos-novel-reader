package com.wear.novelreader.reader

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wear.novelreader.NovelApp
import com.wear.novelreader.R
import com.wear.novelreader.data.BookMeta
import com.wear.novelreader.data.Chapter
import com.wear.novelreader.databinding.ActivityChapterJumpBinding
import com.wear.novelreader.databinding.ItemChapterJumpBinding
import com.wear.novelreader.ui.EdgeBackTouchListener
import com.wear.novelreader.ui.WearStatusBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChapterJumpActivity : ComponentActivity() {

    private lateinit var binding: ActivityChapterJumpBinding
    private lateinit var book: BookMeta
    private lateinit var levels: List<Int>
    private val path = mutableListOf<ChapterRange>()
    private var confirming = false
    private var confirmNumber = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as NovelApp
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        binding = ActivityChapterJumpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WearStatusBar.attach(binding.root, binding.contentLayout)
        binding.root.setOnTouchListener(EdgeBackTouchListener(this))

        binding.breadcrumbScroll.visibility = View.GONE
        binding.jumpList.visibility = View.GONE
        binding.stepLabel.text = getString(R.string.loading)
        binding.backButton.setOnClickListener { navigateBack() }
        binding.jumpList.layoutManager = LinearLayoutManager(this)
        binding.jumpList.itemAnimator = null
        binding.confirmContainer.setOnClickListener { }
        binding.confirmCard.setOnClickListener { }
        binding.confirmJump.setOnClickListener {
            if (!confirming) return@setOnClickListener
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_CHAPTER_INDEX, confirmNumber - 1)
            )
            finish()
        }
        binding.confirmReselect.setOnClickListener {
            confirming = false
            binding.confirmContainer.visibility = View.GONE
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
        })
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                bookId?.let { app.repository.getBook(it) }
            }
            if (loaded == null || loaded.chapters.isEmpty()) {
                finish()
                return@launch
            }
            book = loaded
            levels = ChapterJumpPlan.levels(book.chapters.size)
            binding.breadcrumbScroll.visibility = View.VISIBLE
            binding.jumpList.visibility = View.VISIBLE
            render()
        }
    }

    private fun navigateBack() {
        if (confirming) {
            confirming = false
            binding.confirmContainer.visibility = View.GONE
            return
        }
        if (path.isNotEmpty()) {
            path.removeAt(path.lastIndex)
            render()
        } else {
            finish()
        }
    }

    private fun render() {
        val step = levels[path.size]
        val start = path.lastOrNull()?.start ?: 1
        val end = path.lastOrNull()?.end ?: book.chapters.size
        renderBreadcrumb()
        binding.stepLabel.text = if (step == 1) {
            getString(R.string.chapter_jump_step_chapter)
        } else {
            getString(R.string.chapter_jump_step_range, step)
        }

        val entries = if (step == 1) {
            (start..end).map { number ->
                val chapter = book.chapters[number - 1]
                JumpEntry(
                    range = ChapterRange(number, number),
                    number = number,
                    title = chapterTitle(chapter, number),
                    meta = getString(R.string.chapter_jump_read),
                    leaf = true
                )
            }
        } else {
            val nextStep = levels[path.size + 1]
            ChapterJumpPlan.ranges(start, end, step).map { range ->
                JumpEntry(
                    range = range,
                    number = 0,
                    title = getString(R.string.chapter_jump_range, range.start, range.end),
                    meta = getString(
                        R.string.chapter_jump_meta,
                        ChapterJumpPlan.childCount(range, nextStep)
                    ),
                    leaf = false
                )
            }
        }
        binding.jumpList.adapter = JumpAdapter(entries) { entry ->
            if (entry.leaf) {
                confirmNumber = entry.number
                confirming = true
                binding.confirmChapter.text = entry.title
                binding.confirmContainer.visibility = View.VISIBLE
            } else {
                path.add(entry.range)
                render()
            }
        }
        binding.jumpList.scrollToPosition(0)
    }

    private fun renderBreadcrumb() {
        binding.breadcrumb.removeAllViews()
        addCrumb(getString(R.string.chapter_jump_all), path.isEmpty()) {
            path.clear()
            render()
        }
        path.forEachIndexed { index, range ->
            addSeparator()
            addCrumb(
                getString(R.string.chapter_jump_short_range, range.start, range.end),
                index == path.lastIndex
            ) {
                while (path.size > index + 1) {
                    path.removeAt(path.lastIndex)
                }
                render()
            }
        }
        binding.breadcrumbScroll.post {
            binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT)
        }
    }

    private fun addCrumb(label: String, active: Boolean, onClick: () -> Unit) {
        val text = TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(getColor(if (active) R.color.accent else R.color.text_secondary))
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(2))
            isClickable = true
            setOnClickListener { onClick() }
        }
        binding.breadcrumb.addView(text)
    }

    private fun addSeparator() {
        val text = TextView(this).apply {
            text = getString(R.string.chapter_jump_arrow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(1), 0, dp(1), 0)
        }
        binding.breadcrumb.addView(text)
    }

    private fun chapterTitle(chapter: Chapter, number: Int): String {
        return chapter.title.ifBlank { getString(R.string.chapter_jump_fallback, number) }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private data class JumpEntry(
        val range: ChapterRange,
        val number: Int,
        val title: String,
        val meta: String,
        val leaf: Boolean
    )

    private class JumpAdapter(
        private val entries: List<JumpEntry>,
        private val onClick: (JumpEntry) -> Unit
    ) : RecyclerView.Adapter<JumpAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(
                ItemChapterJumpBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(entries[position])
        }

        override fun getItemCount(): Int = entries.size

        inner class Holder(private val binding: ItemChapterJumpBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(entry: JumpEntry) {
                binding.jumpTitle.text = entry.title
                binding.jumpTitle.ellipsize = null
                binding.jumpTitle.maxLines = Int.MAX_VALUE
                binding.jumpTitle.isSingleLine = false
                binding.jumpMeta.text = entry.meta
                binding.jumpMeta.visibility = if (TextUtils.isEmpty(entry.meta)) View.GONE else View.VISIBLE
                binding.jumpArrow.text = binding.root.context.getString(R.string.chapter_jump_arrow)
                binding.root.setOnClickListener { onClick(entry) }
            }
        }
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CHAPTER_INDEX = "chapter_index"
    }
}
