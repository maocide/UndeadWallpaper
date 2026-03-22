package org.maocide.undeadwallpaper

import android.app.Application
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UndeadWallpaperApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize the FileLogger
        FileLogger.init(this)

        // Setup the Global Crash Handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Log the fatal crash using FileLogger
                FileLogger.e("GlobalCrashHandler", "FATAL CRASH in thread: ${thread.name}", throwable)

                // Print stack trace to the file manually just to be safe
                val stackTrace = Log.getStackTraceString(throwable)
                FileLogger.e("GlobalCrashHandler", "Stack Trace: $stackTrace")

            } catch (e: Exception) {
                // If the logger itself fails, log to Logcat as a fallback
                Log.e("GlobalCrashHandler", "Failed to write fatal crash to file", e)
            } finally {
                // Always pass the crash to the default handler so the app can crash gracefully
                // and the OS can handle it (e.g., show an ANR dialog or close it)
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
