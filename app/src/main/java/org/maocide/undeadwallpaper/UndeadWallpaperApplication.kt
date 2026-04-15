package org.maocide.undeadwallpaper

import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.utils.FileLogger

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate

class UndeadWallpaperApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Force dark mode for now
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        // Read preferences and initialize the FileLogger
        val prefs = PreferencesManager(this)
        FileLogger.setLoggingEnabled(prefs.isLoggingEnabled())
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
