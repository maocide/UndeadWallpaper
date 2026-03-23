package org.maocide.undeadwallpaper

import android.content.Context
import android.util.Log
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
     * Helper method to write a formatted string to the log file.
     */
    @Synchronized
    private fun writeToFile(level: String, tag: String, msg: String, tr: Throwable? = null) {
        if (!isInitialized || logFile == null || !isLoggingEnabled) return

        try {
            val timestamp = dateFormat.format(Date())
            val sb = StringBuilder()
            sb.append("[$timestamp] $level/$tag: $msg\n")

            if (tr != null) {
                sb.append(Log.getStackTraceString(tr))
                sb.append("\n")
            }

            // Append to the file
            val outputStream = FileOutputStream(logFile, true)
            val writer = OutputStreamWriter(outputStream)
            writer.append(sb.toString())
            writer.close()
        } catch (e: Exception) {
            // Fallback to standard log if file writing fails
            Log.e("FileLogger", "Failed to write to log file", e)
        }
    }

    /**
     * Debug log
     */
    fun d(tag: String, msg: String, tr: Throwable? = null) {
        Log.d(tag, msg, tr)
        writeToFile("D", tag, msg, tr)
    }

    /**
     * Info log
     */
    fun i(tag: String, msg: String, tr: Throwable? = null) {
        Log.i(tag, msg, tr)
        writeToFile("I", tag, msg, tr)
    }

    /**
     * Warning log
     */
    fun w(tag: String, msg: String, tr: Throwable? = null) {
        Log.w(tag, msg, tr)
        writeToFile("W", tag, msg, tr)
    }

    /**
     * Error log
     */
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        writeToFile("E", tag, msg, tr)
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
