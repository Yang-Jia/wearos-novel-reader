package com.wear.novelreader.reader

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.text.LineBreaker
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wear.novelreader.NovelApp
import com.wear.novelreader.R
import com.wear.novelreader.data.BookMeta
import com.wear.novelreader.data.BookProgress
import com.wear.novelreader.data.ReaderSettings
import com.wear.novelreader.data.ReadingMode
import com.wear.novelreader.ui.WearStatusBar
import com.wear.novelreader.databinding.ActivityReaderBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.abs

class ReaderActivity : ComponentActivity() {

    private lateinit var binding: ActivityReaderBinding
    private lateinit var app: NovelApp
    private lateinit var book: BookMeta
    private lateinit var statusBar: WearStatusBar
    private var settings = ReaderSettings()

    private var chapterIndex = 0
    private var currentText = ""
    private var pendingOffset = 0
    private var charOffset = 0
    private var returnChapterIndex = -1
    private var returnCharOffset = 0
    private var chapterLayout: StaticLayout? = null
    private var pages: List<PageLines> = emptyList()
    private var pageIndex = 0
    private var loadToken = 0
    private var loadJob: Job? = null
    private var reloadOnResume = false
    private var menuVisible = false
    private var renderScheduled = false
    private var scrollRenderToken = 0
    private val chapterCache = LruCache<Int, String>(CHAPTER_CACHE_SIZE)
    private var preloadJob: Job? = null
    private val chapterLoadJobs = ConcurrentHashMap<Int, Job>()
    private lateinit var chapterLayoutManager: LinearLayoutManager
    private lateinit var chapterAdapter: ChapterScrollAdapter

    private lateinit var gestureDetector: GestureDetector
    private lateinit var outerGestureDetector: GestureDetector
    private var edgeTracking = false
    private var edgeHorizontal = false
    private var edgeDownX = 0f
    private var edgeDownY = 0f
    private var blankTracking = false
    private var blankFromLeftEdge = false
    private var blankHorizontal = false
    private var blankVertical = false
    private var blankDownX = 0f
    private var blankDownY = 0f
    private var blankLastY = 0f
    private var blankVelocityTracker: VelocityTracker? = null
    private var rotaryTargetY = 0f
    private var rotaryAnimating = false
    private val rotaryFrame = object : Runnable {
        override fun run() {
            if (!rotaryAnimating) return
            val scroll = binding.chapterScroll
            val current = scroll.computeVerticalScrollOffset().toFloat()
            val diff = rotaryTargetY - current
            if (abs(diff) < 0.75f) {
                val delta = (rotaryTargetY.roundToInt() - scroll.computeVerticalScrollOffset())
                if (delta != 0) scroll.scrollBy(0, delta)
                rotaryAnimating = false
                return
            }
            val step = if (abs(diff) <= 1f) diff else diff * 0.35f
            var delta = (current + step).roundToInt() - scroll.computeVerticalScrollOffset()
            if (delta == 0) {
                delta = if (diff > 0f) 1 else -1
            }
            scroll.scrollBy(0, delta)
            scroll.postOnAnimation(this)
        }
    }
    private val scrollHandler = Handler(Looper.getMainLooper())
    private val scrollSaveRunnable = Runnable { onScrollSettled() }
    private lateinit var progressController: ReaderProgressController

    private val chapterListLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val index = result.data?.getIntExtra(ChapterListActivity.EXTRA_CHAPTER, -1) ?: -1
            if (index in book.chapters.indices) {
                hideMenu()
                returnChapterIndex = -1
                loadChapter(index, 0)
            }
        }
    }

    private val fontColorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val color = result.data?.getIntExtra(FontColorActivity.EXTRA_FONT_COLOR, 0) ?: 0
            val offset = currentOffset()
            settings = settings.copy(fontColor = if (color == 0) null else color)
            app.prefs.save(settings)
            rerender(offset)
            updateMenuLabels()
        }
    }

    private val chapterJumpLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val index = result.data?.getIntExtra(ChapterJumpActivity.EXTRA_CHAPTER_INDEX, -1) ?: -1
            if (index in book.chapters.indices) {
                hideMenu()
                returnChapterIndex = -1
                loadChapter(index, 0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as NovelApp
        val id = intent.getStringExtra(EXTRA_BOOK_ID)
        val loaded = id?.let { app.repository.getBook(it) }
        if (loaded == null) {
            Toast.makeText(this, getString(R.string.book_missing), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        book = loaded
        settings = app.prefs.load()
        window.setBackgroundDrawable(ColorDrawable(settings.theme.bg))
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        statusBar = WearStatusBar.attach(binding.root, binding.contentLayout)
        progressController = ReaderProgressController(app.repository) {
            if (currentText.isEmpty() || !::book.isInitialized) {
                null
            } else {
                book.id to BookProgress(chapterIndex, currentOffset())
            }
        }

        chapterLayoutManager = LinearLayoutManager(this)
        chapterAdapter = ChapterScrollAdapter(
            textProvider = { chapterCache.get(it) },
            onRequestChapter = { ensureChapterText(it) },
            onTap = {
                if (settings.mode == ReadingMode.SCROLL && !menuVisible) showMenu()
            }
        )
        chapterAdapter.chapterCount = book.chapters.size
        binding.chapterScroll.apply {
            layoutManager = chapterLayoutManager
            adapter = chapterAdapter
            itemAnimator = null
            setHasFixedSize(false)
        }

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleTap(e.x)
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (settings.mode != ReadingMode.PAGE || e1 == null || menuVisible) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > 56f * resources.displayMetrics.density && abs(dx) > abs(dy)) {
                    if (dx < 0) nextPage() else prevPage()
                    return true
                }
                return false
            }
        })

        outerGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleOuterTap(e.rawX)
                return true
            }
        })

        binding.pageView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
        binding.chapterScroll.setOnTouchListener { _, event ->
            if (settings.mode == ReadingMode.SCROLL && !menuVisible) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    cancelRotaryAnimation()
                }
            }
            false
        }
        binding.readerRoot.setOnTouchListener { _, event ->
            if (settings.mode == ReadingMode.SCROLL && !menuVisible) {
                handleBlankScrollTouch(event)
            } else if (handleEdgeBack(event)) {
                true
            } else if (settings.mode == ReadingMode.PAGE && !menuVisible) {
                outerGestureDetector.onTouchEvent(event)
                true
            } else {
                false
            }
        }
        binding.scrim.setOnTouchListener { _, event -> handleEdgeBack(event) }
        binding.scrim.setOnClickListener { hideMenu() }
        binding.menuClose.setOnClickListener { hideMenu() }

        binding.menuChapters.setOnClickListener {
            hideMenu()
            chapterListLauncher.launch(
                Intent(this, ChapterListActivity::class.java)
                    .putExtra(ChapterListActivity.EXTRA_BOOK_ID, book.id)
                    .putExtra(ChapterListActivity.EXTRA_CURRENT, chapterIndex)
                    .putExtra(ChapterListActivity.EXTRA_OFFSET, currentOffset())
            )
        }
        binding.menuFontMinus.setOnClickListener { changeFontSize(-1) }
        binding.menuFontPlus.setOnClickListener { changeFontSize(1) }
        binding.menuLineSpacing.setOnClickListener { cycleLineSpacing() }
        binding.menuMode.setOnClickListener { toggleMode() }
        binding.menuReverse.setOnClickListener { toggleReverseTaps() }
        binding.menuTheme.setOnClickListener { cycleTheme() }
        binding.menuFontColor.setOnClickListener {
            hideMenu()
            fontColorLauncher.launch(Intent(this, FontColorActivity::class.java))
        }
        binding.menuJump.setOnClickListener {
            hideMenu()
            chapterJumpLauncher.launch(
                Intent(this, ChapterJumpActivity::class.java)
                    .putExtra(ChapterJumpActivity.EXTRA_BOOK_ID, book.id)
            )
        }
        binding.menuKeepScreenOn.setOnClickListener { toggleKeepScreenOn() }
        binding.menuBrightnessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                settings = settings.copy(brightness = (progress / 100f).coerceIn(0.1f, 1f))
                app.prefs.save(settings)
                applyBrightness()
                updateMenuLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.menuBrightnessFollow.setOnClickListener {
            settings = settings.copy(brightness = -1f)
            app.prefs.save(settings)
            binding.menuBrightnessSeek.progress = 100
            applyBrightness()
            updateMenuLabels()
        }
        binding.menuPrevChapter.setOnClickListener {
            hideMenu()
            prevChapter()
        }
        binding.menuNextChapter.setOnClickListener {
            hideMenu()
            nextChapter()
        }

        binding.readingFrame.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val w = right - left
            val h = bottom - top
            if (w == oldRight - oldLeft && h == oldBottom - oldTop) return@addOnLayoutChangeListener
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            if (currentText.isNotEmpty() && settings.mode == ReadingMode.PAGE) {
                rerender(currentOffset())
            }
        }

        binding.chapterScroll.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (settings.mode == ReadingMode.SCROLL && !menuVisible) {
                    updateScrollPosition()
                    scrollHandler.removeCallbacks(scrollSaveRunnable)
                    scrollHandler.postDelayed(scrollSaveRunnable, 350L)
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE &&
                    settings.mode == ReadingMode.SCROLL && !menuVisible
                ) {
                    onScrollSettled()
                }
            }
        })
        binding.chapterScroll.setOnGenericMotionListener { _, event -> handleRotaryScroll(event) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (menuVisible) {
                    hideMenu()
                } else {
                    flushProgress()
                    finish()
                }
            }
        })

        applyTheme()
        applyBrightness()
        applyKeepScreenOn()

        val progress = app.repository.loadProgress(book.id)
        chapterIndex = progress.chapterIndex.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0))
        val startChapter = intent.getIntExtra(EXTRA_CHAPTER, -1)
        if (startChapter in book.chapters.indices) {
            chapterIndex = startChapter
            pendingOffset = 0
        } else {
            pendingOffset = progress.charOffset
        }
        loadChapter(chapterIndex, pendingOffset)
        updateMenuLabels()
    }

    override fun onResume() {
        super.onResume()
        applyBrightness()
        if (reloadOnResume) {
            reloadOnResume = false
            loadChapter(chapterIndex, pendingOffset)
        }
        schedulePreload()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (handleRotaryScroll(event)) return true
        return super.onGenericMotionEvent(event)
    }

    override fun onPause() {
        super.onPause()
        cancelRotaryAnimation()
        if (::progressController.isInitialized) {
            flushProgress()
        }
        scrollHandler.removeCallbacks(scrollSaveRunnable)
    }

    override fun onStop() {
        super.onStop()
        cancelRotaryAnimation()
        preloadJob?.cancel()
        preloadJob = null
        chapterLoadJobs.values.forEach { it.cancel() }
        chapterLoadJobs.clear()
        if (loadJob?.isActive == true) {
            reloadOnResume = true
        }
        loadJob?.cancel()
        loadJob = null
        if (::binding.isInitialized) {
            binding.loadingText.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        cancelRotaryAnimation()
        preloadJob?.cancel()
        chapterLoadJobs.values.forEach { it.cancel() }
        chapterLoadJobs.clear()
        loadJob?.cancel()
        chapterCache.evictAll()
        scrollHandler.removeCallbacks(scrollSaveRunnable)
        if (::progressController.isInitialized) {
            progressController.cancel()
        }
        super.onDestroy()
    }

    private fun loadChapter(index: Int, offset: Int) {
        cancelRotaryAnimation()
        if (index !in book.chapters.indices) {
            Toast.makeText(
                this,
                getString(if (index < 0) R.string.first_chapter else R.string.last_chapter),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val token = ++loadToken
        loadJob?.cancel()
        preloadJob?.cancel()
        preloadJob = null
        val textSource = chapterCache.get(index)
        if (textSource != null) {
            applyChapter(index, offset, textSource)
            return
        }
        binding.loadingText.visibility = View.VISIBLE
        binding.pageView.clear()
        val job = lifecycleScope.launch {
            val text = try {
                withContext(Dispatchers.IO) { app.repository.readChapter(book, index) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token == loadToken) {
                    binding.loadingText.visibility = View.GONE
                    Toast.makeText(
                        this@ReaderActivity,
                        getString(R.string.load_failed, e.message ?: ""),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            if (token != loadToken) return@launch
            chapterCache.put(index, text)
            chapterAdapter.notifyItemChanged(index)
            applyChapter(index, offset, text)
        }
        loadJob = job
    }

    private fun applyChapter(index: Int, offset: Int, text: String) {
        chapterIndex = index
        currentText = text
        chapterCache.put(index, text)
        chapterAdapter.notifyItemChanged(index)
        pendingOffset = if (offset == Int.MAX_VALUE) Int.MAX_VALUE else offset.coerceIn(0, text.length)
        charOffset = if (pendingOffset == Int.MAX_VALUE) text.length else pendingOffset
        pageIndex = 0
        chapterLayout = null
        pages = emptyList()
        updateMenuLabels()
        rerender(pendingOffset)
        scheduleProgressSave()
        schedulePreload()
    }

    private fun schedulePreload() {
        preloadJob?.cancel()
        if (!::book.isInitialized || currentText.isEmpty()) return
        val center = chapterIndex
        val targets = listOf(center + 1, center - 1, center + 2, center - 2)
            .filter {
                it in book.chapters.indices &&
                    chapterCache.get(it) == null &&
                    !chapterLoadJobs.containsKey(it)
            }
        if (targets.isEmpty()) return
        preloadJob = lifecycleScope.launch {
            for (index in targets) {
                if (!isActive) break
                if (chapterCache.get(index) != null || chapterLoadJobs.containsKey(index)) continue
                val text = try {
                    withContext(Dispatchers.IO) { app.repository.readChapter(book, index) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
                if (!isActive) break
                chapterCache.put(index, text)
                chapterAdapter.notifyItemChanged(index)
            }
        }
    }

    private fun ensureChapterText(index: Int) {
        if (!::book.isInitialized || index !in book.chapters.indices) return
        if (chapterCache.get(index) != null || chapterLoadJobs.containsKey(index)) return
        val job = lifecycleScope.launch {
            val text = try {
                withContext(Dispatchers.IO) { app.repository.readChapter(book, index) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            if (!isActive) return@launch
            chapterCache.put(index, text)
            chapterAdapter.notifyItemChanged(index)
        }
        chapterLoadJobs[index] = job
        job.invokeOnCompletion { chapterLoadJobs.remove(index) }
    }

    private fun rerender(offset: Int) {
        pendingOffset = if (offset == Int.MAX_VALUE) Int.MAX_VALUE else offset.coerceIn(0, currentText.length)
        if (settings.mode == ReadingMode.PAGE) {
            renderPage()
        } else {
            renderScroll(pendingOffset)
        }
    }

    private fun renderPage() {
        scrollRenderToken++
        val view = binding.pageView
        val width = view.width - view.paddingLeft - view.paddingRight
        val height = view.height - view.paddingTop - view.paddingBottom
        if (width <= 0 || height <= 0) {
            scheduleRender()
            return
        }
        binding.chapterScroll.visibility = View.GONE
        view.visibility = View.VISIBLE
        if (currentText.isEmpty()) {
            view.clear()
            chapterLayout = null
            pages = emptyList()
            binding.loadingText.visibility = View.GONE
            return
        }

        val paint = buildTextPaint()
        val layout = StaticLayout.Builder.obtain(currentText, 0, currentText.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, settings.lineSpacing)
            .setIncludePad(false)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        chapterLayout = layout
        pages = Pagination.compute(layout, height)
        if (pages.isEmpty()) {
            view.clear()
            binding.loadingText.visibility = View.GONE
            return
        }
        val target = if (pendingOffset >= Int.MAX_VALUE / 2) {
            pages.size - 1
        } else {
            pages.indexOfLast { layout.getLineStart(it.startLine) <= pendingOffset }
                .coerceAtLeast(0)
        }
        pageIndex = target.coerceIn(0, pages.size - 1)
        view.show(layout, pages[pageIndex])
        charOffset = layout.getLineStart(pages[pageIndex].startLine)
        updatePageInfo()
        binding.loadingText.visibility = View.GONE
    }

    private fun scheduleRender() {
        if (renderScheduled) return
        renderScheduled = true
        binding.readingFrame.post {
            renderScheduled = false
            if (binding.readingFrame.width > 0 && binding.readingFrame.height > 0) {
                rerender(pendingOffset)
            }
        }
    }

    private fun buildTextPaint(): TextPaint {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
        paint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            settings.fontSizeSp.toFloat(),
            resources.displayMetrics
        )
        paint.color = settings.textColor
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.isSubpixelText = true
        return paint
    }

    private fun handleRotaryScroll(event: MotionEvent): Boolean {
        if (menuVisible || settings.mode != ReadingMode.SCROLL) return false
        if (event.actionMasked != MotionEvent.ACTION_SCROLL) return false
        var delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (delta == 0f) {
            delta = event.getAxisValue(MotionEvent.AXIS_SCROLL)
        }
        if (delta == 0f) return false

        val scroll = binding.chapterScroll
        val stepPx = -delta * ROTARY_DP_PER_DETENT * resources.displayMetrics.density
        if (!rotaryAnimating) {
            rotaryTargetY = scroll.computeVerticalScrollOffset().toFloat()
        }
        val maxY = (
            scroll.computeVerticalScrollRange() -
                scroll.computeVerticalScrollExtent()
            ).coerceAtLeast(0).toFloat()
        rotaryTargetY = (rotaryTargetY + stepPx).coerceIn(0f, maxY)
        if (!rotaryAnimating) {
            rotaryAnimating = true
            scroll.postOnAnimation(rotaryFrame)
        }
        return true
    }

    private fun cancelRotaryAnimation() {
        if (!::binding.isInitialized) return
        rotaryAnimating = false
        binding.chapterScroll.removeCallbacks(rotaryFrame)
        rotaryTargetY = binding.chapterScroll.computeVerticalScrollOffset().toFloat()
    }

    private fun renderScroll(offset: Int) {
        val token = ++scrollRenderToken
        cancelRotaryAnimation()
        rotaryTargetY = 0f
        val target = if (offset == Int.MAX_VALUE) {
            Int.MAX_VALUE
        } else {
            offset.coerceIn(0, currentText.length)
        }
        pendingOffset = target
        chapterLayout = null
        pages = emptyList()
        binding.pageView.visibility = View.GONE
        binding.chapterScroll.visibility = View.VISIBLE
        chapterAdapter.setStyle(
            settings.fontSizeSp.toFloat(),
            settings.lineSpacing,
            settings.textColor
        )
        chapterAdapter.notifyItemChanged(chapterIndex)
        binding.loadingText.visibility = View.GONE
        scrollToChapterOffset(chapterIndex, target, token, 0)
    }

    private fun scrollToChapterOffset(index: Int, target: Int, token: Int, attempt: Int) {
        chapterLayoutManager.scrollToPositionWithOffset(index, 0)
        binding.chapterScroll.post {
            if (token != scrollRenderToken) return@post
            val holder = binding.chapterScroll
                .findViewHolderForAdapterPosition(index) as? ChapterScrollAdapter.Holder
            val textView = holder?.binding?.chapterText
            val layout = textView?.layout
            if (layout == null || textView.width <= 0) {
                if (attempt < 4) {
                    scrollToChapterOffset(index, target, token, attempt + 1)
                }
                return@post
            }
            val line = if (target >= Int.MAX_VALUE / 2) {
                (layout.lineCount - 1).coerceAtLeast(0)
            } else {
                layout.getLineForOffset(target.coerceIn(0, layout.text.length))
            }
            val y = textView.paddingTop + layout.getLineTop(line)
            chapterLayoutManager.scrollToPositionWithOffset(index, -y)
            charOffset = layout.getLineStart(line)
            updatePageInfo()
        }
    }

    private fun handleEdgeBack(event: MotionEvent): Boolean {
        val config = ViewConfiguration.get(this)
        val edgeWidth = maxOf(
            config.scaledEdgeSlop.toFloat(),
            20f * resources.displayMetrics.density
        )
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.pointerCount == 1 && event.rawX <= edgeWidth) {
                    edgeTracking = true
                    edgeHorizontal = false
                    edgeDownX = event.rawX
                    edgeDownY = event.rawY
                    return true
                }
                edgeTracking = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!edgeTracking) return false
                val dx = event.rawX - edgeDownX
                val dy = event.rawY - edgeDownY
                val threshold = config.scaledTouchSlop * 2f
                if (dx > threshold && dx > abs(dy)) {
                    edgeHorizontal = true
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!edgeTracking) return false
                edgeTracking = false
                val dx = event.rawX - edgeDownX
                val dy = event.rawY - edgeDownY
                val threshold = config.scaledTouchSlop * 2f
                if (edgeHorizontal || (dx > threshold && dx > abs(dy))) {
                    onBackPressedDispatcher.onBackPressed()
                } else if (abs(dx) <= config.scaledTouchSlop && abs(dy) <= config.scaledTouchSlop) {
                    if (menuVisible) {
                        onBackPressedDispatcher.onBackPressed()
                    } else if (settings.mode == ReadingMode.PAGE) {
                        tapLeft()
                    }
                }
                edgeHorizontal = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                edgeTracking = false
                edgeHorizontal = false
                return true
            }
        }
        return edgeTracking
    }

    private fun handleTap(x: Float) {
        if (menuVisible) return
        if (settings.mode == ReadingMode.PAGE) {
            val width = binding.pageView.width.toFloat().coerceAtLeast(1f)
            when {
                x < width * 0.30f -> tapLeft()
                x > width * 0.70f -> tapRight()
                else -> showMenu()
            }
        } else {
            showMenu()
        }
    }

    private fun handleBlankScrollTouch(event: MotionEvent): Boolean {
        val config = ViewConfiguration.get(this)
        val slop = config.scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.pointerCount != 1) return false
                cancelRotaryAnimation()
                blankTracking = true
                blankFromLeftEdge = event.rawX <= blankEdgeWidth(config)
                blankHorizontal = false
                blankVertical = false
                blankDownX = event.rawX
                blankDownY = event.rawY
                blankLastY = event.rawY
                blankVelocityTracker?.recycle()
                blankVelocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!blankTracking) return false
                blankVelocityTracker?.addMovement(event)
                val dx = event.rawX - blankDownX
                val dy = event.rawY - blankDownY
                if (!blankHorizontal && !blankVertical) {
                    if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        blankHorizontal = true
                    } else if (abs(dy) > slop) {
                        blankVertical = true
                    }
                }
                if (blankVertical && !blankHorizontal) {
                    val delta = (blankLastY - event.rawY).toInt()
                    if (delta != 0) {
                        binding.chapterScroll.scrollBy(0, delta)
                    }
                }
                blankLastY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!blankTracking) return false
                blankTracking = false
                blankVelocityTracker?.addMovement(event)
                val velocityY = blankVelocityTracker
                    ?.apply { computeCurrentVelocity(1000) }
                    ?.yVelocity
                    ?: 0f
                blankVelocityTracker?.recycle()
                blankVelocityTracker = null
                if (blankHorizontal && blankFromLeftEdge) {
                    onBackPressedDispatcher.onBackPressed()
                } else if (blankVertical &&
                    abs(velocityY) > config.scaledMinimumFlingVelocity.toFloat()
                ) {
                    binding.chapterScroll.fling(0, (-velocityY).toInt())
                }
                blankHorizontal = false
                blankVertical = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                blankTracking = false
                blankHorizontal = false
                blankVertical = false
                blankVelocityTracker?.recycle()
                blankVelocityTracker = null
                return true
            }
        }
        return blankTracking
    }

    private fun blankEdgeWidth(config: ViewConfiguration): Float {
        return maxOf(
            config.scaledEdgeSlop.toFloat(),
            20f * resources.displayMetrics.density
        )
    }

    private fun handleOuterTap(rawX: Float) {
        if (menuVisible || settings.mode != ReadingMode.PAGE) return
        val pageView = binding.pageView
        if (pageView.width <= 0 || pages.isEmpty()) return
        val location = IntArray(2)
        pageView.getLocationOnScreen(location)
        val left = location[0].toFloat()
        val right = (location[0] + pageView.width).toFloat()
        when {
            rawX < left -> tapLeft()
            rawX > right -> tapRight()
        }
    }

    private fun tapLeft() {
        if (settings.reverseTaps) nextPage() else prevPage()
    }

    private fun tapRight() {
        if (settings.reverseTaps) prevPage() else nextPage()
    }

    private fun prevPage() {
        if (settings.mode != ReadingMode.PAGE) {
            prevChapter()
            return
        }
        if (pages.isEmpty()) return
        if (pageIndex > 0) {
            pageIndex--
            showPage()
        } else if (chapterIndex > 0) {
            prevChapter()
        } else {
            Toast.makeText(this, R.string.first_page, Toast.LENGTH_SHORT).show()
        }
    }

    private fun nextPage() {
        if (settings.mode != ReadingMode.PAGE) {
            nextChapter()
            return
        }
        if (pages.isEmpty()) return
        if (pageIndex < pages.size - 1) {
            pageIndex++
            showPage()
        } else if (chapterIndex < book.chapters.size - 1) {
            nextChapter()
        } else {
            Toast.makeText(this, R.string.last_page, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPage() {
        val layout = chapterLayout ?: return
        val page = pages.getOrNull(pageIndex) ?: return
        binding.pageView.show(layout, page)
        charOffset = layout.getLineStart(page.startLine)
        updatePageInfo()
        scheduleProgressSave()
    }

    private fun prevChapter() {
        if (returnChapterIndex == chapterIndex - 1) {
            val index = returnChapterIndex
            val offset = returnCharOffset
            returnChapterIndex = -1
            loadChapter(index, offset)
            return
        }
        returnChapterIndex = -1
        if (chapterIndex > 0) {
            loadChapter(chapterIndex - 1, Int.MAX_VALUE)
        } else {
            Toast.makeText(this, R.string.first_chapter, Toast.LENGTH_SHORT).show()
        }
    }

    private fun nextChapter() {
        if (chapterIndex < book.chapters.size - 1) {
            returnChapterIndex = chapterIndex
            returnCharOffset = currentOffset()
            loadChapter(chapterIndex + 1, 0)
        } else {
            Toast.makeText(this, R.string.last_chapter, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateScrollPosition() {
        val position = chapterLayoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return
        val holder = binding.chapterScroll.findViewHolderForAdapterPosition(position)
            as? ChapterScrollAdapter.Holder ?: return
        val textView = holder.binding.chapterText
        val text = chapterCache.get(position) ?: return
        val layout = textView.layout ?: return
        val y = (-holder.itemView.top - textView.paddingTop).coerceAtLeast(0)
        val line = layout.getLineForVertical(y)
            .coerceIn(0, (layout.lineCount - 1).coerceAtLeast(0))
        val previousChapter = chapterIndex
        chapterIndex = position
        currentText = text
        charOffset = layout.getLineStart(line).coerceIn(0, text.length)
        pendingOffset = charOffset
        if (previousChapter != position) {
            schedulePreload()
        }
    }

    private fun offsetForScroll(): Int = charOffset

    private fun currentOffset(): Int {
        return if (settings.mode == ReadingMode.PAGE) {
            val layout = chapterLayout
            if (layout != null) {
                layout.getLineStart(pages.getOrNull(pageIndex)?.startLine ?: 0)
            } else {
                charOffset
            }
        } else {
            offsetForScroll()
        }
    }

    private fun onScrollSettled() {
        if (settings.mode != ReadingMode.SCROLL || currentText.isEmpty()) return
        updateScrollPosition()
        updatePageInfo()
        scheduleProgressSave()
    }

    private fun updatePageInfo() {
        if (!::book.isInitialized) return
        val text = if (settings.mode == ReadingMode.PAGE) {
            if (pages.isEmpty()) "" else getString(
                R.string.reader_page_info,
                pageIndex + 1,
                pages.size,
                book.percentAt(chapterIndex, charOffset)
            )
        } else {
            getString(
                R.string.reader_scroll_info,
                book.percentAt(chapterIndex, charOffset),
                chapterIndex + 1,
                book.chapters.size
            )
        }
        binding.pageInfo.text = text
        binding.pageInfo.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun scheduleProgressSave() {
        if (currentText.isEmpty() || !::book.isInitialized) return
        progressController.schedule()
    }

    private fun flushProgress() {
        progressController.flush()
    }

    private fun showMenu() {
        cancelRotaryAnimation()
        menuVisible = true
        updateMenuLabels()
        binding.scrim.visibility = View.VISIBLE
        binding.menuLayer.visibility = View.VISIBLE
    }

    private fun hideMenu() {
        menuVisible = false
        binding.scrim.visibility = View.GONE
        binding.menuLayer.visibility = View.GONE
    }

    private fun updateMenuLabels() {
        if (!::book.isInitialized) return
        val offset = currentOffset()
        binding.menuTitle.text = "${book.title} · ${book.percentAt(chapterIndex, offset)}%"
        val chapter = book.chapters.getOrNull(chapterIndex)
        val percent = book.percentAt(chapterIndex, if (currentText.isEmpty()) 0 else offset)
        binding.menuProgress.text = buildString {
            append(chapter?.title ?: "")
            append(" · ")
            append(percent)
            append("%")
        }
        binding.menuMode.text =
            "${getString(R.string.reading_mode)}：${if (settings.mode == ReadingMode.PAGE) getString(R.string.mode_page) else getString(R.string.mode_scroll)}"
        binding.menuTheme.text = "${getString(R.string.theme)}：${themeLabel()}"
        binding.menuKeepScreenOn.text = getString(
            if (settings.keepScreenOn) R.string.keep_screen_on_on else R.string.keep_screen_on_off
        )
        binding.menuReverse.text = getString(
            if (settings.reverseTaps) R.string.reverse_taps_on else R.string.reverse_taps_off
        )
        binding.menuLineSpacing.text =
            "${getString(R.string.line_spacing)}：${String.format(Locale.US, "%.2f", settings.lineSpacing)}"
        binding.menuFontPlus.text = getString(R.string.font_size_current, settings.fontSizeSp)
        val brightnessPercent = if (settings.brightness < 0f) {
            100
        } else {
            (settings.brightness * 100).roundToInt().coerceIn(10, 100)
        }
        binding.menuBrightnessLabel.text = if (settings.brightness < 0f) {
            getString(R.string.brightness_follow_system)
        } else {
            getString(R.string.brightness_percent, brightnessPercent)
        }
        binding.menuBrightnessSeek.progress = brightnessPercent
    }

    private fun themeLabel(): String = when (settings.theme) {
        com.wear.novelreader.data.ReaderTheme.DARK -> getString(R.string.theme_dark)
        com.wear.novelreader.data.ReaderTheme.GRAY -> getString(R.string.theme_gray)
        com.wear.novelreader.data.ReaderTheme.SEPIA -> getString(R.string.theme_sepia)
    }

    private fun changeFontSize(delta: Int) {
        val offset = currentOffset()
        val newSize = (settings.fontSizeSp + delta).coerceIn(12, 28)
        if (newSize == settings.fontSizeSp) return
        settings = settings.copy(fontSizeSp = newSize)
        app.prefs.save(settings)
        rerender(offset)
        updateMenuLabels()
    }

    private fun cycleLineSpacing() {
        val offset = currentOffset()
        val values = floatArrayOf(1.2f, 1.35f, 1.5f, 1.7f)
        val current = settings.lineSpacing
        val next = values.firstOrNull { it > current + 0.01f } ?: values.first()
        settings = settings.copy(lineSpacing = next)
        app.prefs.save(settings)
        rerender(offset)
        updateMenuLabels()
    }

    private fun toggleMode() {
        val offset = currentOffset()
        settings = settings.copy(
            mode = if (settings.mode == ReadingMode.PAGE) ReadingMode.SCROLL else ReadingMode.PAGE
        )
        app.prefs.save(settings)
        rerender(offset)
        updateMenuLabels()
    }

    private fun toggleReverseTaps() {
        settings = settings.copy(reverseTaps = !settings.reverseTaps)
        app.prefs.save(settings)
        updateMenuLabels()
    }

    private fun toggleKeepScreenOn() {
        settings = settings.copy(keepScreenOn = !settings.keepScreenOn)
        app.prefs.save(settings)
        applyKeepScreenOn()
        updateMenuLabels()
    }

    private fun cycleTheme() {
        val offset = currentOffset()
        settings = settings.copy(theme = settings.theme.next())
        app.prefs.save(settings)
        applyTheme()
        rerender(offset)
        updateMenuLabels()
    }

    private fun applyTheme() {
        val theme = settings.theme
        window.setBackgroundDrawable(ColorDrawable(theme.bg))
        binding.loadingText.setTextColor(theme.secondary)
        binding.menuCard.background = GradientDrawable().apply {
            cornerRadius = resources.displayMetrics.density * 20f
            setColor(theme.surface)
            setStroke(
                (resources.displayMetrics.density).toInt(),
                Color.argb(0x33, 255, 255, 255)
            )
        }
        tintMenuText(binding.menuCard, theme.text, theme.surface)
        binding.menuTitle.setTextColor(theme.text)
        binding.menuProgress.setTextColor(theme.secondary)
        statusBar.applyColors(
            theme.text,
            Color.argb(
                0x66,
                Color.red(theme.surface),
                Color.green(theme.surface),
                Color.blue(theme.surface)
            )
        )
    }

    private fun tintMenuText(view: View, color: Int, surface: Int) {
        if (view is android.widget.TextView) view.setTextColor(color)
        if (view is android.widget.Button) {
            view.backgroundTintList = ColorStateList.valueOf(surface)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) tintMenuText(view.getChildAt(i), color, surface)
        }
    }

    private fun applyBrightness() {
        ReaderWindowHelper.applyBrightness(window, settings.brightness)
    }

    private fun applyKeepScreenOn() {
        ReaderWindowHelper.applyKeepScreenOn(window, settings.keepScreenOn)
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CHAPTER = "chapter"
        private const val ROTARY_DP_PER_DETENT = 1f
        private const val CHAPTER_CACHE_SIZE = 5
    }
}















