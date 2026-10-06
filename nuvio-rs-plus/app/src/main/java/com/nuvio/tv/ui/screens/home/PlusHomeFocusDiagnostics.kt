package com.nuvio.tv.ui.screens.home

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.nuvio.tv.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plus-only, opt-in diagnostics for the Modern Home focus bug.
 *
 * Logging is disabled by default. UI-thread callers only enqueue an immutable event;
 * file I/O and rotation run on a dedicated single background thread so diagnostics
 * should not materially change focus timing.
 */
internal object PlusHomeFocusDiagnostics {
    private const val TAG = "NRSP_HOME_FOCUS"
    private const val PREFS_NAME = "nrsp_home_focus_diagnostics"
    private const val PREF_ENABLED = "enabled"
    private const val LOG_DIRECTORY = "diagnostics"
    private const val LOG_FILE = "nrsp-modern-home-focus.log"
    private const val MAX_LOG_BYTES = 512 * 1024L
    private const val RETAIN_LOG_BYTES = 256 * 1024

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nrsp-home-focus-log").apply { isDaemon = true }
    }
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var cachedEnabled: Boolean? = null

    fun isEnabled(context: Context): Boolean {
        cachedEnabled?.let { return it }
        return synchronized(this) {
            cachedEnabled ?: context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false)
                .also { cachedEnabled = it }
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        if (enabled) {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_ENABLED, true)
                .apply()
            cachedEnabled = true
            enqueue(
                appContext,
                event = "SESSION_START",
                fields = arrayOf(
                    "version" to BuildConfig.VERSION_NAME,
                    "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "sdk" to Build.VERSION.SDK_INT
                ),
                force = true
            )
        } else {
            enqueue(appContext, event = "SESSION_STOP", fields = emptyArray(), force = true)
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_ENABLED, false)
                .apply()
            cachedEnabled = false
        }
    }

    fun log(context: Context, event: String, vararg fields: Pair<String, Any?>) {
        if (!isEnabled(context)) return
        enqueue(context.applicationContext, event, fields, force = false)
    }

    suspend fun readLog(
        context: Context,
        maxLines: Int = 500,
        newestFirst: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        executor.submit<String> {
            val file = logFile(appContext)
            if (!file.exists() || file.length() == 0L) return@submit ""
            val selected = file.readLines(Charsets.UTF_8).takeLast(maxLines.coerceAtLeast(1))
            if (newestFirst) selected.asReversed().joinToString("\n") else selected.joinToString("\n")
        }.get(5, TimeUnit.SECONDS)
    }

    suspend fun clear(context: Context) {
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            executor.submit {
                val file = logFile(appContext)
                if (file.exists()) file.delete()
            }.get(5, TimeUnit.SECONDS)
        }
    }

    suspend fun exportToDownloads(context: Context): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException("Public Downloads export requires Android 10 or newer")
        }

        executor.submit<String> {
            val source = logFile(appContext)
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val displayName = "NuvioRSPlus-ModernHome-Focus-$timestamp.txt"
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/NuvioRSPlus"

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val resolver = appContext.contentResolver
            val uri = resolver.insert(collection, values)
                ?: error("Could not create Downloads entry")

            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    if (source.exists() && source.length() > 0L) {
                        source.inputStream().use { input -> input.copyTo(output) }
                    } else {
                        output.write(
                            "Nuvio RS Plus Modern Home focus diagnostics\nNo events recorded.\n"
                                .toByteArray(Charsets.UTF_8)
                        )
                    }
                } ?: error("Could not open exported log for writing")

                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null
                )
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }

            "$relativePath/$displayName"
        }.get(10, TimeUnit.SECONDS)
    }

    suspend fun createShareIntent(context: Context): Intent = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val shareFile = executor.submit<File> {
            val source = logFile(appContext)
            val directory = File(appContext.cacheDir, LOG_DIRECTORY).apply { mkdirs() }
            val target = File(directory, "NuvioRSPlus-ModernHome-Focus.txt")
            if (source.exists() && source.length() > 0L) {
                source.copyTo(target, overwrite = true)
            } else {
                target.writeText(
                    "Nuvio RS Plus Modern Home focus diagnostics\nNo events recorded.\n",
                    Charsets.UTF_8
                )
            }
            target
        }.get(5, TimeUnit.SECONDS)

        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            shareFile
        )
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Nuvio RS Plus Modern Home focus diagnostics")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Nuvio RS Plus Modern Home focus diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun enqueue(
        context: Context,
        event: String,
        fields: Array<out Pair<String, Any?>>,
        force: Boolean
    ) {
        if (!force && !isEnabled(context)) return
        val elapsedMs = SystemClock.elapsedRealtime()
        val fieldText = fields.joinToString(" ") { (key, value) ->
            "${sanitize(key)}=${sanitize(value)}"
        }
        val androidLine = buildString {
            append(event)
            if (fieldText.isNotBlank()) {
                append(' ')
                append(fieldText)
            }
        }
        Log.i(TAG, androidLine)

        executor.execute {
            runCatching {
                val line = buildString {
                    append(timestampFormat.format(Date()))
                    append(" elapsed=")
                    append(elapsedMs)
                    append(' ')
                    append(event)
                    if (fieldText.isNotBlank()) {
                        append(' ')
                        append(fieldText)
                    }
                }
                appendLine(context, line)
            }.onFailure {
                Log.w(TAG, "Persistent diagnostic log write failed", it)
            }
        }
    }

    private fun appendLine(context: Context, line: String) {
        val file = logFile(context)
        if (file.length() >= MAX_LOG_BYTES) rotate(file)
        file.appendText(line + "\n", Charsets.UTF_8)
    }

    private fun rotate(file: File) {
        val bytes = file.readBytes()
        val start = (bytes.size - RETAIN_LOG_BYTES).coerceAtLeast(0)
        val tail = bytes.copyOfRange(start, bytes.size).toString(Charsets.UTF_8)
        val lineSafeTail = if (start == 0) tail else tail.substringAfter('\n', tail)
        file.writeText(
            "--- log rotated; newest events retained ---\n" + lineSafeTail,
            Charsets.UTF_8
        )
    }

    private fun logFile(context: Context): File {
        val directory = File(context.filesDir, LOG_DIRECTORY).apply { mkdirs() }
        return File(directory, LOG_FILE)
    }

    private fun sanitize(value: Any?): String {
        val text = value?.toString() ?: "null"
        return text.replace('\n', ' ').replace('\r', ' ').take(180)
    }
}
