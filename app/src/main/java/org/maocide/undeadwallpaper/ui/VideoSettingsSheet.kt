package org.maocide.undeadwallpaper.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.slider.Slider
import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.databinding.SheetVideoSettingsBinding
import org.maocide.undeadwallpaper.model.ScalingMode
import org.maocide.undeadwallpaper.model.VideoSettings
import org.maocide.undeadwallpaper.service.UndeadWallpaperService
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import org.maocide.undeadwallpaper.R
import org.maocide.undeadwallpaper.data.VideoFileManager
import java.io.File

class VideoSettingsSheet : BottomSheetDialogFragment() {

    private var _binding: SheetVideoSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var fileName: String
    private var isUpdatingUi = false

    companion object {
        private const val ARG_FILE_NAME = "file_name"

        fun newInstance(fileName: String): VideoSettingsSheet {
            val args = Bundle()
            args.putString(ARG_FILE_NAME, fileName)
            val fragment = VideoSettingsSheet()
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileName = arguments?.getString(ARG_FILE_NAME) ?: ""
        preferencesManager = PreferencesManager(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetVideoSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.videoFileName.text = fileName

        // Load thumbnail
        lifecycleScope.launch(Dispatchers.IO) {
            val videoFileManager = VideoFileManager(requireContext())
            val videosDir = context?.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)?.let { File(it, "videos") }
            if (videosDir != null) {
                val file = File(videosDir, fileName)
                if (file.exists()) {
                    val thumbnail = videoFileManager.createVideoThumbnail(file.path)
                    withContext(Dispatchers.Main) {
                        if (thumbnail != null) {
                            binding.thumbnailPreview.setImageBitmap(thumbnail)
                        }
                    }
                }
            }
        }

        syncUiState()
        setupListeners()
    }

    private fun syncUiState() {
        isUpdatingUi = true
        try {
            val settings = preferencesManager.getVideoSettings(fileName)
            val defaults = VideoSettings(fileName)

            when (settings.scalingMode) {
                ScalingMode.FIT -> binding.scalingModeGroup.check(binding.scalingModeFit.id)
                ScalingMode.FILL -> binding.scalingModeGroup.check(binding.scalingModeFill.id)
                ScalingMode.STRETCH -> binding.scalingModeGroup.check(binding.scalingModeStretch.id)
            }
            binding.scalingModeLabel.markIfModified(settings.scalingMode, defaults.scalingMode)

            binding.positionXSlider.setValueSafe(settings.positionX)
            binding.positionXLabel.markIfModified(settings.positionX, defaults.positionX)

            binding.positionYSlider.setValueSafe(settings.positionY)
            binding.positionYLabel.markIfModified(settings.positionY, defaults.positionY)

            binding.zoomSlider.setValueSafe(settings.zoom)
            binding.zoomLabel.markIfModified(settings.zoom, defaults.zoom)

            binding.rotationSlider.setValueSafe(settings.rotation)
            binding.rotationLabel.markIfModified(settings.rotation, defaults.rotation)

            binding.brightnessSlider.setValueSafe(settings.brightness)
            binding.brightnessLabel.markIfModified(settings.brightness, defaults.brightness)

            binding.speedSlider.setValueSafe(settings.speed)
            binding.speedLabel.markIfModified(settings.speed, defaults.speed)

            binding.volumeSlider.setValueSafe(settings.volume)
            binding.volumeLabel.markIfModified(settings.volume, defaults.volume)

        } finally {
            isUpdatingUi = false
        }
    }

    private fun updateSettings(updater: (VideoSettings) -> VideoSettings) {
        preferencesManager.updateVideoSettings(fileName, updater)
        notifySettingsChanged()
    }

    private fun notifySettingsChanged() {
        val intent = Intent(UndeadWallpaperService.ACTION_VIDEO_SETTINGS_CHANGED).apply {
            setPackage(requireContext().packageName)
        }
        requireContext().applicationContext.sendBroadcast(intent)
    }

    private fun setupListeners() {
        val defaults = VideoSettings(fileName)

        fun setupSafeSlider(slider: Slider, label: TextView, defaultVal: Float, saveAction: (Float) -> Unit) {
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}

                override fun onStopTrackingTouch(slider: Slider) {
                    if (isUpdatingUi) return
                    saveAction(slider.value)
                    label.markIfModified(slider.value, defaultVal)
                }
            })

            // Lock the scroll view while touching the slider
            slider.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        // NestedScrollView, should stop scrolling
                        view.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        // Give back control to scroll view
                        view.parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
                // Return false to let the slider process touch event
                false
            }
        }


        binding.scalingModeGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty() || isUpdatingUi) return@setOnCheckedStateChangeListener
            val newMode = when (checkedIds[0]) {
                binding.scalingModeFit.id -> ScalingMode.FIT
                binding.scalingModeStretch.id -> ScalingMode.STRETCH
                else -> ScalingMode.FILL
            }
            updateSettings { it.copy(scalingMode = newMode) }
            binding.scalingModeLabel.markIfModified(newMode, defaults.scalingMode)
        }

        setupSafeSlider(binding.positionXSlider, binding.positionXLabel, defaults.positionX) { value -> updateSettings { it.copy(positionX = value) } }
        setupSafeSlider(binding.positionYSlider, binding.positionYLabel, defaults.positionY) { value -> updateSettings { it.copy(positionY = value) } }
        setupSafeSlider(binding.zoomSlider, binding.zoomLabel, defaults.zoom) { value -> updateSettings { it.copy(zoom = value) } }
        setupSafeSlider(binding.rotationSlider, binding.rotationLabel, defaults.rotation) { value -> updateSettings { it.copy(rotation = value) } }
        setupSafeSlider(binding.brightnessSlider, binding.brightnessLabel, defaults.brightness) { value -> updateSettings { it.copy(brightness = value) } }
        setupSafeSlider(binding.speedSlider, binding.speedLabel, defaults.speed) { value -> updateSettings { it.copy(speed = value) } }
        setupSafeSlider(binding.volumeSlider, binding.volumeLabel, defaults.volume) { value -> updateSettings { it.copy(volume = value) } }

        binding.buttonResetAdvanced.setOnClickListener {
            updateSettings { VideoSettings(fileName) }
            syncUiState()
        }
    }

    private fun Slider.setValueSafe(newValue: Float) {
        val clampedValue = newValue.coerceIn(valueFrom, valueTo)
        val finalValue = if (stepSize > 0) {
            val steps = ((clampedValue - valueFrom) / stepSize).roundToInt()
            valueFrom + (steps * stepSize)
        } else {
            clampedValue
        }
        this.value = finalValue
    }

    private fun TextView.markIfModified(current: Float, default: Float) {
        if (current != default) {
            val typedValue = TypedValue()
            context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
            this.setTextColor(ContextCompat.getColor(context, typedValue.resourceId))
            this.setTypeface(null, android.graphics.Typeface.BOLD)
        } else {
            val typedValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)
            this.setTextColor(ContextCompat.getColor(context, typedValue.resourceId))
            this.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }

    private fun TextView.markIfModified(current: ScalingMode, default: ScalingMode) {
        if (current != default) {
            val typedValue = TypedValue()
            context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
            this.setTextColor(ContextCompat.getColor(context, typedValue.resourceId))
            this.setTypeface(null, android.graphics.Typeface.BOLD)
        } else {
            val typedValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)
            this.setTextColor(ContextCompat.getColor(context, typedValue.resourceId))
            this.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
