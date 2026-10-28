package com.wear.novelreader.transfer

import android.content.Context
import com.wear.novelreader.NovelApp
import com.wear.novelreader.R
import com.wear.novelreader.data.PublicBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object TransferController {

    private val _state = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = _state

    @Volatile
    private var server: TransferServer? = null
    private var successCount = 0
    private var lastUpload: String? = null
    private val importLock = Any()
    private val startMutex = Mutex()

    @Volatile
    private var stopping = false

    @Volatile
    private var starting = false

    @Volatile
    private var generation = 0L

    suspend fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext as? NovelApp ?: return
        startMutex.withLock {
            if (server != null) return@withLock
            val runGeneration = ++generation
            starting = true
            stopping = false
            _state.value = TransferState(
                starting = true,
                uploads = successCount,
                lastUpload = lastUpload
            )
            val uploadDir = File(app.cacheDir, "wifi-uploads")
            var created: TransferServer? = null
            try {
                val srv = TransferServer(
                    portHint = 8080,
                    uploadDir = uploadDir,
                    booksProvider = {
                        app.repository.listBooks().map { item ->
                            PublicBook(
                                title = item.meta.title,
                                author = item.meta.author,
                                chapters = item.meta.chapters.size,
                                percent = item.percent
                            )
                        }
                    },
                    uploadHandler = { file, name ->
                        synchronized(importLock) {
                            try {
                                val book = app.importManager.importFile(file, name) { stopping }
                                successCount++
                                lastUpload = book.title
                                _state.value = _state.value.copy(
                                    uploads = successCount,
                                    lastUpload = lastUpload
                                )
                                UploadResult(true, book.title, app.getString(R.string.wifi_imported, book.chapters.size))
                            } catch (e: Exception) {
                                UploadResult(false, null, e.message ?: app.getString(R.string.import_generic_failed))
                            }
                        }
                    },
                    onActiveUploadsChanged = { active ->
                        _state.value = _state.value.copy(activeUploads = active)
                    }
                )
                created = srv
                val port = withContext(Dispatchers.IO) {
                    runCatching { uploadDir.deleteRecursively() }
                    uploadDir.mkdirs()
                    srv.start()
                }
                if (stopping || runGeneration != generation) {
                    runCatching { srv.stop() }
                    return@withLock
                }
                server = srv
                val urls = withContext(Dispatchers.IO) { IpAddress.lanUrls(app, port) }
                if (stopping || runGeneration != generation) {
                    runCatching { srv.stop() }
                    server = null
                    return@withLock
                }
                _state.value = TransferState(
                    running = true,
                    port = port,
                    urls = urls,
                    uploads = successCount,
                    lastUpload = lastUpload
                )
            } catch (e: Exception) {
                runCatching { created?.stop() }
                server = null
                if (!stopping && runGeneration == generation) {
                    _state.value = TransferState(
                        error = e.message ?: "无法启动服务",
                        uploads = successCount,
                        lastUpload = lastUpload
                    )
                }
            } finally {
                starting = false
            }
        }
    }

    @Synchronized
    fun stop() {
        stopping = true
        generation++
        server?.stop()
        server = null
        successCount = 0
        lastUpload = null
        _state.value = TransferState()
    }

    fun isRunning(): Boolean = server != null
}
