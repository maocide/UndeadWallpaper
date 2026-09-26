package org.maocide.undeadwallpaper.ui

import org.maocide.undeadwallpaper.databinding.ActivityMainBinding

import org.maocide.undeadwallpaper.R

import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.event.WallpaperEvent
import org.maocide.undeadwallpaper.event.WallpaperEventBus
import org.maocide.undeadwallpaper.service.UndeadWallpaperService

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.view.KeyEvent
import android.view.MotionEvent
import org.maocide.undeadwallpaper.utils.FileLogger
import org.maocide.undeadwallpaper.utils.SafeKeyDebouncer
import org.maocide.undeadwallpaper.utils.setSafeOnClickListener
import java.io.File
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.google.android.material.snackbar.Snackbar
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var preferencesManager: PreferencesManager

    private val sharedViewModel: SettingsViewModel by viewModels()
    private val keyDebouncer = SafeKeyDebouncer(debounceMs = 300L)

    private lateinit var tombstoneController: TombstoneController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }

        setSupportActionBar(binding.toolbar)

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        preferencesManager = PreferencesManager(this)

        tombstoneController = TombstoneController(
            fab = binding.fabSetWallpaper,
            preferencesManager = preferencesManager
        ).apply {
            setup()
        }

        binding.fabSetWallpaper.setSafeOnClickListener(debounceMs = 300L) { view ->
            lifecycleScope.launch {
                val videoUri = getValidWallpaperUri()

                if (videoUri != null) {
                    preferencesManager.saveActiveVideoUri(videoUri.toString())
                    WallpaperEventBus.emit(WallpaperEvent.VideoUriChanged)

                    if (tombstoneController.isBroken) {
                        Snackbar.make(
                            view,
                            getString(R.string.resurrecting_wallpaper_message),
                            Snackbar.LENGTH_SHORT
                        ).setAnchorView(R.id.fab_set_wallpaper).show()

                        tombstoneController.resurrect {
                            launchWallpaperPicker()
                        }
                    } else {
                        Snackbar.make(
                            view,
                            getString(R.string.activating_wallpaper_message),
                            Snackbar.LENGTH_SHORT
                        ).setAnchorView(R.id.fab_set_wallpaper).show()

                        launchWallpaperPicker()
                    }
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.select_video_first_message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Handles manual video interaction from playlist curation,
     * checking against the Unlucky 13 threshold to trigger the break animation.
     */
    fun onManualVideoInteraction() {
        tombstoneController.onManualTap()
    }

    private fun launchWallpaperPicker() {
        val intent = Intent(
            WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER
        )
        intent.putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this@MainActivity, UndeadWallpaperService::class.java)
        )
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                this@MainActivity,
                getString(R.string.error_device_not_supported),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private suspend fun getValidWallpaperUri(): Uri? = withContext(Dispatchers.IO) {
        val uri = sharedViewModel.selectedVideoUri
            ?: preferencesManager.getActiveVideoUri()?.toUri()
        val path = uri?.path ?: return@withContext null
        val file = File(path)
        if (file.exists() && file.isFile) uri else null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return when (item.itemId) {
            R.id.action_faq -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data =
                            "https://github.com/maocide/UndeadWallpaper/blob/master/FAQ.md".toUri()
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    FileLogger.e("MainActivity", "Failed to open FAQ browser link", e)

                    Toast.makeText(this, getString(R.string.error_no_browser), Toast.LENGTH_SHORT).show()
                }
                true
            }

            R.id.action_battery_optimization -> {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    FileLogger.e("MainActivity", "Failed to open app info settings", e)
                    Toast.makeText(this, "Unable to open settings", Toast.LENGTH_SHORT).show()
                }
                true
            }

            R.id.action_settings -> {
                if (navController.currentDestination?.id != R.id.SecondFragment) {
                    navController.navigate(R.id.action_FirstFragment_to_SecondFragment)
                }
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Suppress touch input while window is obscured by system overlays
        if ((ev.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Drop throttled action keys (Back, Enter, Dpad Center, Media) or machine-gun repeats
        if (keyDebouncer.shouldDropKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        keyDebouncer.reset()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}