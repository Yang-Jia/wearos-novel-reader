package com.wear.novelreader

import android.app.Application
import com.wear.novelreader.data.LibraryRepository
import com.wear.novelreader.data.Prefs
import com.wear.novelreader.import.ImportManager
import java.io.File

class NovelApp : Application() {

    lateinit var repository: LibraryRepository
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var importManager: ImportManager
        private set

    override fun onCreate() {
        super.onCreate()
        repository = LibraryRepository(File(filesDir, "library"))
        prefs = Prefs(this)
        importManager = ImportManager(this, repository)
    }
}
