package com.wear.novelreader.reader

import android.os.Handler
import android.os.Looper
import com.wear.novelreader.data.BookProgress
import com.wear.novelreader.data.LibraryRepository

class ReaderProgressController(
    private val repository: LibraryRepository,
    private val position: () -> Pair<String, BookProgress>?
) {

    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveNow() }

    fun schedule() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    fun flush() {
        handler.removeCallbacks(saveRunnable)
        saveNow()
        repository.flushWrites()
    }

    fun cancel() {
        handler.removeCallbacks(saveRunnable)
    }

    private fun saveNow() {
        val (bookId, progress) = position() ?: return
        repository.saveProgress(bookId, progress)
    }

    private companion object {
        const val SAVE_DELAY_MS = 2500L
    }
}
