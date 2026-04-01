package org.maocide.undeadwallpaper.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.slider.Slider
import org.maocide.undeadwallpaper.R
import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.databinding.BottomSheetVideoSettingsBinding
import org.maocide.undeadwallpaper.model.ScalingMode
import org.maocide.undeadwallpaper.model.StartTime
import org.maocide.undeadwallpaper.model.VideoSettings
import org.maocide.undeadwallpaper.service.UndeadWallpaperService
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import org.maocide.undeadwallpaper.data.VideoFileManager
import java.io.File

class VideoSettingsBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetVideoSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var fileName: String
    private var isUpdatingUi = false

    companion object {
        private const val ARG_FILE_NAME = "file_name"

        fun newInstance(fileName: String): VideoSettingsBottomSheetFragment {
            val args = Bundle()
            args.putString(ARG_FILE_NAME, fileName)
            val fragment = VideoSettingsBottomSheetFragment()
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
        _binding = BottomSheetVideoSettingsBinding.inflate(inflater, container, false)
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

            when (settings.scalingMode) {
                ScalingMode.FIT -> binding.scalingModeGroup.check(binding.scalingModeFit.id)
                ScalingMode.FILL -> binding.scalingModeGroup.check(binding.scalingModeFill.id)
                ScalingMode.STRETCH -> binding.scalingModeGroup.check(binding.scalingModeStretch.id)
            }

            when (settings.startTime) {
                StartTime.RESUME -> binding.startTimeGroup.check(binding.startTimeResume.id)
                StartTime.RESTART -> binding.startTimeGroup.check(binding.startTimeRestart.id)
                StartTime.RANDOM -> binding.startTimeGroup.check(binding.startTimeRandom.id)
            }

            binding.positionXSlider.setValueSafe(settings.positionX)
            binding.positionYSlider.setValueSafe(settings.positionY)
            binding.zoomSlider.setValueSafe(settings.zoom)
            binding.rotationSlider.setValueSafe(settings.rotation)
            binding.brightnessSlider.setValueSafe(settings.brightness)
            binding.speedSlider.setValueSafe(settings.speed)
            binding.volumeSlider.setValueSafe(settings.volume)
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
        fun setupSafeSlider(slider: Slider, saveAction: (Float) -> Unit) {
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}

                override fun onStopTrackingTouch(slider: Slider) {
                    if (isUpdatingUi) return
                    saveAction(slider.value)
                }
            })
        }

        binding.scalingModeGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty() || isUpdatingUi) return@setOnCheckedStateChangeListener
            val newMode = when (checkedIds[0]) {
                binding.scalingModeFit.id -> ScalingMode.FIT
                binding.scalingModeStretch.id -> ScalingMode.STRETCH
                else -> ScalingMode.FILL
            }
            updateSettings { it.copy(scalingMode = newMode) }
        }

        binding.startTimeGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty() || isUpdatingUi) return@setOnCheckedStateChangeListener
            val newMode = when (checkedIds[0]) {
                binding.startTimeRestart.id -> StartTime.RESTART
                binding.startTimeRandom.id -> StartTime.RANDOM
                else -> StartTime.RESUME
            }
            updateSettings { it.copy(startTime = newMode) }
        }

        setupSafeSlider(binding.positionXSlider) { value -> updateSettings { it.copy(positionX = value) } }
        setupSafeSlider(binding.positionYSlider) { value -> updateSettings { it.copy(positionY = value) } }
        setupSafeSlider(binding.zoomSlider) { value -> updateSettings { it.copy(zoom = value) } }
        setupSafeSlider(binding.rotationSlider) { value -> updateSettings { it.copy(rotation = value) } }
        setupSafeSlider(binding.brightnessSlider) { value -> updateSettings { it.copy(brightness = value) } }
        setupSafeSlider(binding.speedSlider) { value -> updateSettings { it.copy(speed = value) } }
        setupSafeSlider(binding.volumeSlider) { value -> updateSettings { it.copy(volume = value) } }

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
