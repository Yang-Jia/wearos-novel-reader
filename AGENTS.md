# AGENTS.md

## Project

- Single-module Android Wear OS app: `:app`, package `com.wear.novelreader`.
- Plain XML + ViewBinding; no Compose, Room, DI, Navigation, WorkManager, CI,
  formatter, or separate typecheck target.
- Entrypoints: `NovelApp` creates `LibraryRepository`, `Prefs`, `ImportManager`.
  Activities: Main (`ui`), Reader/ChapterList/FontColor (`reader`), Wi-Fi (`transfer`).
- User-facing text and `README.md` are Chinese. Preserve UTF-8 without BOM; Windows
  PowerShell may display valid Chinese as mojibake.
- `README.md` is the detailed behavior reference. Trust code/config over stale prose.

## Commands

Run from the repository root with JDK 17:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Single test class/method:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wear.novelreader.TxtImporterTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.wear.novelreader.TxtImporterTest.stopsParsingWhenCancelled"
```

- Lint has `abortOnError = false`; success does not mean clean. Inspect
  `app/build/reports/lint-results-debug.txt`.
- Tests are JVM-only (no instrumentation); currently 36 tests.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
  When delivering, copy it to root `wear-novel-reader-debug.apk`.
- Install:
  `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools-2\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk`
  (platform-tools is under `platform-tools-2` on this machine).

## Repository quirks

- Checkout path contains Chinese characters. Keep `android.overridePathCheck=true`;
  the test task copies non-ASCII classpath entries to a temp directory.
- Do not edit `app/build/`, `.gradle/`, or generated binding classes.
- No `opencode.json`, CI, pre-commit hook, formatter, or lint autofix workflow.

## Architecture invariants

- `LibraryRepository` is file-backed (JSON under `filesDir/library`), with sorted
  snapshot/version cache and root-mtime invalidation. Do not reintroduce per-resume
  directory scans. Progress JSON uses a daemon single-thread write queue plus atomic
  replacement; never write progress on the main thread or delete the old file first.
- Delete flow uses `.trash-*`: `deleteBook` stages, `MainActivity` finalizes on
  undo-window end/onStop. Only show undo when staging succeeded; do not silently clear
  pending trash. `cleanupTrash` must not run while a delete/undo is pending.
- Reader (`ReaderActivity`) has paged `PageView`/`StaticLayout` and scrolling
  `RecyclerView`/`ChapterScrollAdapter` modes. Scroll mode keeps one item per chapter,
  a 5-chapter `LruCache`, preloads current ±2, and preserves `chapterIndex + charOffset`.
  Keep chapter loads/preload/crown callbacks lifecycle-bound; reload interrupted loads
  on resume. Page mode still uses `loadChapter`.
- Reader font color is a body-only override (`fontColor` pref; `null` = follow theme).
  `FontColorActivity` returns `0` for follow-theme. Theme/menu/status/page-info colors
  stay theme-based. Screen-on is controlled by the reader menu toggle.
- ChapterList receives current chapter and char offset from Reader; keep that offset
  for the current chapter progress rather than reloading possibly stale saved progress.
- Import workdirs are `.tmp-*`; cancellation or failure must delete the current workdir.
  Do not wrap `OutOfMemoryError` as `IOException`.
- Wi-Fi uses `TransferService` only while `WifiTransferActivity` is visible. Start/stop
  is serialized with a mutex/generation; leaving stops the service and cancels imports/
  uploads. QR generation is local ZXing.
- All screens use `BoxInsetLayout` + `layout_boxedEdges` for round-watch safe areas.
  Status bar time/battery receivers register only while visible; no `TextClock` or
  always-registered receivers.
- App is foreground-only: no new services, receivers, alarms, scheduled jobs, timers,
  or long-lived background workers. The repository progress writer is process-bound
  and only serializes immediate writes.
- Manifest intentionally removes AndroidX Startup/ProfileInstaller, sets
  `allowBackup=false`, and keeps `tools:node="remove"` / `tools:ignore="MissingClass"`.
  Current permissions: network/Wi-Fi/foreground-service/notification.

## Editing conventions

- Match plain Kotlin/XML/ViewBinding style; avoid adding a dependency or framework for
  a small change.
- Use file edit tools for Chinese strings/XML; avoid shell redirection for encoding.
- Preserve the foreground-only lifecycle and page/scroll mode consistency when editing
  Reader, Wi-Fi, or Main.
- After changes run the full test/build/lint command and inspect lint. For device
  behavior, install and verify on the watch. Update `README.md` for user-visible changes.
