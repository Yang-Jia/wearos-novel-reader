package com.wear.novelreader.ui

import android.content.ActivityNotFoundException
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wear.novelreader.NovelApp
import com.wear.novelreader.R
import com.wear.novelreader.data.BookItem
import com.wear.novelreader.databinding.ActivityMainBinding
import com.wear.novelreader.import.ImportCancelledException
import com.wear.novelreader.import.ImportManager
import com.wear.novelreader.reader.ReaderActivity
import com.wear.novelreader.transfer.WifiTransferActivity
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: NovelApp
    private lateinit var adapter: BookAdapter
    private val importUi by lazy { ImportUiController(this) }
    private var importing = false
    private var autoInboxChecked = false
    private var refreshJob: Job? = null
    private var importJob: Job? = null
    private var deleteJob: Job? = null
    private var cleanupJob: Job? = null
    private var finalizeDeleteJob: Job? = null
    private var deleteFinalizeWhenDone = false
    private var pendingTrash: File? = null
    private var pendingDeleteId: String? = null
    private var pendingDeleteTitle: String? = null
    private var importToken = 0
    private var lastBookCount = -1
    private var lastRenderedVersion = -1L

    private val openDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) startUriImport(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as NovelApp
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WearStatusBar.attach(binding.root, binding.contentLayout)
        binding.root.setOnTouchListener(EdgeBackTouchListener(this))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.overlayLayer.visibility == View.VISIBLE) {
                    hideOverlay()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        adapter = BookAdapter(
            onClick = { item -> openReader(item) },
            onLongClick = { item -> confirmDelete(item) }
        )
        binding.bookList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            itemAnimator = null
            adapter = this@MainActivity.adapter
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.repository.changes.collectLatest {
                    delay(150)
                    refreshBooks()
                }
            }
        }

        binding.importButton.setOnClickListener { showImportFallback() }
        binding.wifiButton.setOnClickListener {
            startActivity(Intent(this, WifiTransferActivity::class.java))
        }

        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshBooks()
        cleanupTrash()
        if (!autoInboxChecked) {
            autoInboxChecked = true
            autoImportInboxIfAny()
        }
    }

    override fun onStop() {
        super.onStop()
        refreshJob?.cancel()
        importToken++
        importJob?.cancel()
        importJob = null
        if (deleteJob?.isActive == true) {
            deleteFinalizeWhenDone = true
        } else {
            deleteFinalizeWhenDone = false
        }
        cleanupJob?.cancel()
        if (pendingTrash != null) {
            finalizePendingDelete()
        } else {
            finalizeDeleteJob?.cancel()
            finalizeDeleteJob = null
            hideOverlay()
            pendingDeleteId = null
            pendingDeleteTitle = null
        }
        importing = false
        importUi.dismiss()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            intent.removeExtra(Intent.EXTRA_STREAM)
            intent.data = null
            startUriImport(listOf(uri))
        }
    }

    private fun refreshBooks() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { app.repository.snapshot() }
            if (snapshot.version == lastRenderedVersion) return@launch
            lastRenderedVersion = snapshot.version
            val books = snapshot.books
            val countIncreased = lastBookCount >= 0 && books.size > lastBookCount
            val firstChanged = books.firstOrNull()?.meta?.id !=
                adapter.currentList.firstOrNull()?.meta?.id
            lastBookCount = books.size
            adapter.submitList(books) {
                if (countIncreased || firstChanged) binding.bookList.scrollToPosition(0)
            }
            binding.title.text = if (books.isEmpty()) {
                getString(R.string.main_title)
            } else {
                getString(R.string.main_title) + " · " + books.size
            }
            binding.emptyView.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
            binding.loading.visibility = View.GONE
        }
    }

    private fun openReader(item: BookItem) {
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, item.meta.id)
        )
    }

    private data class OverlayAction(
        val label: String,
        val danger: Boolean = false,
        val heightDp: Int = 48,
        val action: () -> Unit
    )

    private fun confirmDelete(item: BookItem) {
        showOverlay(
            getString(R.string.delete_book_title, item.meta.title),
            "",
            listOf(
                OverlayAction(getString(R.string.delete), danger = true) {
                    hideOverlay()
                    deleteJob?.cancel()
                    deleteFinalizeWhenDone = false
                    deleteJob = lifecycleScope.launch {
                        var trash: File? = null
                        try {
                            trash = withContext(Dispatchers.IO) {
                                app.repository.deleteBook(item.meta.id)
                            }
                            refreshBooks()
                            if (trash == null) {
                                Toast.makeText(
                                    this@MainActivity,
                                    R.string.delete_done,
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@launch
                            }
                            if (deleteFinalizeWhenDone ||
                                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                            ) {
                                withContext(Dispatchers.IO) {
                                    app.repository.cleanupTrash()
                                }
                                return@launch
                            }
                            showUndo(item, trash)
                        } finally {
                            deleteJob = null
                            if (deleteFinalizeWhenDone) {
                                deleteFinalizeWhenDone = false
                                withContext(Dispatchers.IO) {
                                    app.repository.cleanupTrash()
                                }
                            }
                        }
                    }
                },
                OverlayAction(getString(R.string.cancel)) { hideOverlay() }
            )
        )
    }

    private fun showUndo(item: BookItem, trash: File?) {
        pendingTrash = trash
        pendingDeleteId = item.meta.id
        pendingDeleteTitle = item.meta.title
        finalizeDeleteJob?.cancel()
        showOverlay(
            getString(R.string.delete_undo_title, item.meta.title),
            getString(R.string.delete_undo_message),
            listOf(
                OverlayAction(getString(R.string.delete_undo)) {
                    finalizeDeleteJob?.cancel()
                    finalizeDeleteJob = null
                    val id = pendingDeleteId
                    val staged = pendingTrash
                    pendingTrash = null
                    pendingDeleteId = null
                    pendingDeleteTitle = null
                    hideOverlay()
                    if (id != null) {
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                app.repository.restoreDeleted(id, staged)
                            }
                            refreshBooks()
                        }
                    }
                },
                OverlayAction(getString(R.string.close)) {
                    finalizePendingDelete()
                }
            )
        )
        finalizeDeleteJob = lifecycleScope.launch {
            delay(3000)
            finalizePendingDelete()
        }
    }

    private fun finalizePendingDelete() {
        finalizeDeleteJob?.cancel()
        finalizeDeleteJob = null
        pendingTrash = null
        pendingDeleteId = null
        pendingDeleteTitle = null
        hideOverlay()
        cleanupTrash()
    }

    private fun showOverlay(title: String, message: String, actions: List<OverlayAction>) {
        binding.overlayTitle.text = title
        binding.overlayMessage.text = message
        binding.overlayMessage.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        binding.overlayActions.removeAllViews()
        binding.overlayLayer.setOnClickListener { hideOverlay() }
        binding.overlayCard.setOnClickListener { }
        for (action in actions) {
            val button = Button(this).apply {
                text = action.label
                isAllCaps = false
                textSize = 10f
                minHeight = 0
                minWidth = 0
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(if (action.danger) R.color.danger else R.color.text_primary))
                setBackgroundResource(R.drawable.bg_menu_button)
                setOnClickListener {
                    action.action()
                }
            }
            binding.overlayActions.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(action.heightDp)
                ).apply { topMargin = dp(4) }
            )
        }
        binding.overlayLayer.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        binding.overlayLayer.visibility = View.GONE
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun cleanupTrash() {
        if (pendingTrash != null || deleteJob?.isActive == true) return
        cleanupJob?.cancel()
        cleanupJob = lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                app.repository.cleanupTrash { !isActive }
            }
        }
    }

    private fun showImportFallback() {
        showOverlay(
            getString(R.string.import_choose),
            "",
            listOf(
                OverlayAction(getString(R.string.import_from_picker)) {
                    hideOverlay()
                    try {
                        openDocuments.launch(
                            arrayOf(
                                "text/plain",
                                "application/epub+zip",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(this, R.string.import_picker_missing, Toast.LENGTH_LONG).show()
                    }
                },
                OverlayAction(getString(R.string.import_from_inbox)) {
                    hideOverlay()
                    val files = app.importManager.inboxDir.listFiles()
                        ?.filter { it.isFile && it.extension.lowercase() in setOf("txt", "epub") }
                        ?: emptyList()
                    if (files.isEmpty()) showInboxPath() else startInboxImport()
                },
                OverlayAction(getString(R.string.wifi_transfer)) {
                    hideOverlay()
                    startActivity(Intent(this, WifiTransferActivity::class.java))
                },
                OverlayAction(getString(R.string.close), heightDp = 32) {
                    hideOverlay()
                }
            )
        )
    }

    private fun showInboxPath() {
        val path = app.importManager.inboxDir.absolutePath
        AlertDialog.Builder(this)
            .setTitle(R.string.import_from_inbox)
            .setMessage(getString(R.string.import_inbox_path, path))
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    private fun autoImportInboxIfAny() {
        if (importing) return
        val files = app.importManager.inboxDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("txt", "epub") }
            ?: emptyList()
        if (files.isNotEmpty()) startInboxImport()
    }

    private fun startUriImport(uris: List<Uri>) {
        if (importing) return
        importing = true
        importUi.show(uris.size)
        val token = ++importToken
        val job = lifecycleScope.launch {
            try {
                val summary = app.importManager.importUris(uris) { index, total, name ->
                    runOnUiThread { importUi.update(index, total, name) }
                }
                if (token == importToken) finishImport(summary)
            } catch (_: ImportCancelledException) {
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                if (token == importToken) showError(e.message ?: "导入失败")
            } finally {
                if (token == importToken) {
                    importing = false
                    importJob = null
                    importUi.dismiss()
                }
            }
        }
        importJob = job
    }

    private fun startInboxImport() {
        if (importing) return
        importing = true
        importUi.show(1)
        val token = ++importToken
        val job = lifecycleScope.launch {
            try {
                val summary = app.importManager.importInbox { index, total, name ->
                    runOnUiThread { importUi.update(index, total, name) }
                }
                if (token == importToken) finishImport(summary)
            } catch (_: ImportCancelledException) {
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                if (token == importToken) showError(e.message ?: "导入失败")
            } finally {
                if (token == importToken) {
                    importing = false
                    importJob = null
                    importUi.dismiss()
                }
            }
        }
        importJob = job
    }

    private fun finishImport(summary: ImportManager.ImportSummary) {
        importUi.dismiss()
        refreshBooks()
        if (summary.success.isEmpty() && summary.failures.isEmpty()) {
            Toast.makeText(this, R.string.import_none, Toast.LENGTH_SHORT).show()
            return
        }
        val message = getString(
            R.string.import_done,
            summary.success.size,
            summary.failures.size
        )
        if (summary.failures.isEmpty()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } else {
            val details = summary.failures.take(5)
                .joinToString("\n") { "${it.name}：${it.reason}" }
            AlertDialog.Builder(this)
                .setTitle(message)
                .setMessage(details)
                .setPositiveButton(R.string.got_it, null)
                .show()
        }
    }


    private fun showError(message: String) {
        importUi.dismiss()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.import_failed, message))
            .setPositiveButton(R.string.got_it, null)
            .show()
    }
}










