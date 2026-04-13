package org.maocide.undeadwallpaper.utils

import android.content.Context
import android.util.Log
import org.maocide.undeadwallpaper.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A utility class that wraps standard android.util.Log calls and appends them
 * to a local file. This is useful for capturing a sequence of events leading
 * up to a crash.
 */
object FileLogger {

    private const val LOG_FILE_NAME = "undead_wallpaper_log.txt"
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var isInitialized = false
    private var isLoggingEnabled = false

    /**
     * Sets whether file logging should actively write to the file.
     */
    fun setLoggingEnabled(enabled: Boolean) {
        isLoggingEnabled = enabled
    }

    /**
     * Initializes the logger by setting up the file in the app's internal files directory.
     * This must be called before attempting to log to a file.
     */
    fun init(context: Context) {
        if (isInitialized) return

        // Use the filesDir so it's persistent across restarts and not easily cleared by the system
        val logsDir = File(context.filesDir, "logs")
        if (!logsDir.exists()) {
            logsDir.mkdirs()
        }

        logFile = File(logsDir, LOG_FILE_NAME)
        isInitialized = true

        // Log initialization
        i("FileLogger", "Logger initialized at ${logFile?.absolutePath}")
    }

    /**
     * Sanitizes strings to prevent leaking user file structures or sensitive URIs
     * into the log files.
     */
    private fun sanitize(msg: String?): String {
        if (msg == null) return ""
        if (BuildConfig.DEBUG) {
            return msg
        }

        var sanitized = msg

        // Example 1: Redact standard content URIs (e.g., content://media/external/video/media/123)
        // Replaces the specific ID/path with [URI_REDACTED]
        sanitized = sanitized.replace(Regex("content://[\\w\\.\\-\\/]+"), "[URI_REDACTED]")

        // Example 2: Redact absolute file paths (e.g., /storage/emulated/0/Movies/my_video.mp4)
        // Keeps the file extension if it exists, so you still know the format failing
        sanitized = sanitized.replace(Regex("/storage/[\\w\\-]+/[\\w\\.\\-\\/]+(\\.\\w+)?")) { matchResult ->
            val extension = matchResult.groups[1]?.value ?: ""
            "[STORAGE_PATH_REDACTED]$extension"
        }

        return sanitized
    }

    /**
     * Helper method to write a formatted string to the log file.
     */
    @Synchronized
    private fun writeToFile(level: String, tag: String, cleanMsg: String, cleanTr: String? = null) {
        if (!isInitialized || logFile == null || !isLoggingEnabled) return

        try {
            val timestamp = dateFormat.format(Date())
            val sb = StringBuilder()
            sb.append("[$timestamp] $level/$tag: $cleanMsg\n")

            if (cleanTr != null) {
                sb.append(cleanTr)
                sb.append("\n")
            }

            // Append to the file
            val outputStream = FileOutputStream(logFile, true)
            val writer = OutputStreamWriter(outputStream)
            writer.append(sb.toString())
            writer.close()
        } catch (e: Exception) {
            // Fallback to standard log if file writing fails
            if (BuildConfig.DEBUG) {
                Log.e("FileLogger", "Failed to write to log file", e)
            }
        }
    }

    /**
     * Debug log
     */
    fun d(tag: String, msg: String, tr: Throwable? = null) {
        val cleanMsg = sanitize(msg)
        val cleanTr = tr?.let { sanitize(Log.getStackTraceString(it)) }

        if (BuildConfig.DEBUG) {
            if (cleanTr != null) Log.d(tag, "$cleanMsg\n$cleanTr") else Log.d(tag, cleanMsg)
        }
        writeToFile("D", tag, cleanMsg, cleanTr)
    }

    /**
     * Info log
     */
    fun i(tag: String, msg: String, tr: Throwable? = null) {
        val cleanMsg = sanitize(msg)
        val cleanTr = tr?.let { sanitize(Log.getStackTraceString(it)) }

        if (BuildConfig.DEBUG) {
            if (cleanTr != null) Log.i(tag, "$cleanMsg\n$cleanTr") else Log.i(tag, cleanMsg)
        }
        writeToFile("I", tag, cleanMsg, cleanTr)
    }

    /**
     * Warning log
     */
    fun w(tag: String, msg: String, tr: Throwable? = null) {
        val cleanMsg = sanitize(msg)
        val cleanTr = tr?.let { sanitize(Log.getStackTraceString(it)) }

        if (BuildConfig.DEBUG) {
            if (cleanTr != null) Log.w(tag, "$cleanMsg\n$cleanTr") else Log.w(tag, cleanMsg)
        }
        writeToFile("W", tag, cleanMsg, cleanTr)
    }

    /**
     * Error log
     */
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val cleanMsg = sanitize(msg)
        val cleanTr = tr?.let { sanitize(Log.getStackTraceString(it)) }

        if (BuildConfig.DEBUG) {
            if (cleanTr != null) Log.e(tag, "$cleanMsg\n$cleanTr") else Log.e(tag, cleanMsg)
        }
        writeToFile("E", tag, cleanMsg, cleanTr)
    }

    /**
     * Returns the current log file.
     */
    fun getLogFile(): File? {
        return logFile
    }

    /**
     * Clears the current log file.
     */
    fun clearLog() {
        if (logFile?.exists() == true) {
            logFile?.delete()
            logFile?.createNewFile()
            i("FileLogger", "Log file cleared.")
        }
    }
}
