package org.maocide.undeadwallpaper.ui

import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.slider.Slider
import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.databinding.SheetVideoSettingsBinding
import org.maocide.undeadwallpaper.event.WallpaperEvent
import org.maocide.undeadwallpaper.event.WallpaperEventBus
import org.maocide.undeadwallpaper.model.ScalingMode
import org.maocide.undeadwallpaper.model.VideoSettings
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import org.maocide.undeadwallpaper.R
import org.maocide.undeadwallpaper.data.VideoFileManager
import org.maocide.undeadwallpaper.ui.preview.PreviewPeekController
import org.maocide.undeadwallpaper.utils.DisplayGeometryHelper
import org.maocide.undeadwallpaper.utils.preventDoubleInput
import org.maocide.undeadwallpaper.utils.setSafeOnClickListener
import java.io.File

class VideoSettingsSheet : BottomSheetDialogFragment() {

    private var _binding: SheetVideoSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var fileName: String
    private lateinit var metadata: String
    private var isUpdatingUi = false
    private var previewPeekController: PreviewPeekController? = null
    private var primaryColor: Int = 0
    private var secondaryColor: Int = 0
    private val settingsDispatcher = Dispatchers.IO.limitedParallelism(1)

    companion object {
        private const val ARG_FILE_NAME = "file_name"
        private const val ARG_METADATA = "metadata"

        fun newInstance(fileName: String, metadata: String): VideoSettingsSheet {
            val args = Bundle()
            args.putString(ARG_FILE_NAME, fileName)
            args.putString(ARG_METADATA, metadata)
            val fragment = VideoSettingsSheet()
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileName = arguments?.getString(ARG_FILE_NAME) ?: ""
        metadata = arguments?.getString(ARG_METADATA) ?: ""
        preferencesManager = PreferencesManager(requireContext())
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.window?.setHideOverlayWindows(true)
        dialog.setOnShowListener { dialogInterface ->
            val d = dialogInterface as BottomSheetDialog
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                sheet.requestLayout()

                val behavior = BottomSheetBehavior.from(sheet)

                // Use 'isFitToContents' to access the public setter
                behavior.isFitToContents = false

                // Hijack the "Half" state and set it to 82%
                behavior.halfExpandedRatio = 0.82f
                behavior.skipCollapsed = true

                // Open the sheet directly into our custom 82% state
                behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setHideOverlayWindows(true)
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

        val typedValue = TypedValue()
        val theme = requireContext().theme
        theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
        primaryColor = ContextCompat.getColor(requireContext(), typedValue.resourceId)

        theme.resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)
        secondaryColor = ContextCompat.getColor(requireContext(), typedValue.resourceId)

        val settings = preferencesManager.getVideoSettings(fileName)
        binding.videoFileName.text = settings.getEffectiveDisplayName()

        if (metadata.isNotEmpty()) {
            binding.videoMetadata.text = metadata
        }

        val peekController = PreviewPeekController(
            overlayContainer = binding.peekOverlayContainer,
            peekCard = binding.peekCard,
            peekThumbnail = binding.peekThumbnail,
            previewContainer = binding.previewFrameContainer,
            sourceThumbnail = binding.thumbnailPreview,
            parallaxBadge = binding.peekParallaxBadge,
            isParallaxEnabled = preferencesManager.isParallaxEnabled()
        )
        peekController.setup()
        previewPeekController = peekController

        setupAdaptivePreviewSize()

        // Load thumbnail using cached disk representation (self-heals if missing)
        lifecycleScope.launch(Dispatchers.IO) {
            val videoFileManager = VideoFileManager(requireContext())
            val videosDir =
                context?.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)?.let { File(it, "videos") }
            if (videosDir != null) {
                val file = File(videosDir, fileName)
                if (file.exists()) {
                    val thumbnail = videoFileManager.getOrGenerateThumbnail(file)
                    withContext(Dispatchers.Main) {
                        if (thumbnail != null && _binding != null) {
                            binding.thumbnailPreview.setImageBitmap(thumbnail)
                            binding.thumbnailPreview.animate()
                                .alpha(1f)
                                .setDuration(150L)
                                .start()
                            binding.thumbnailPreview.doOnLayout {
                                applyPreviewTransform()
                            }
                        }
                    }
                }
            }
        }

        syncUiState()
        setupListeners()
    }

    private fun setupAdaptivePreviewSize() {
        val metrics = resources.displayMetrics
        val isLandscape = metrics.widthPixels > metrics.heightPixels
        val targetHeightDp = if (isLandscape) 64 else 116
        val targetHeightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            targetHeightDp.toFloat(),
            metrics
        ).roundToInt()
        val screenRatio = DisplayGeometryHelper.getEffectiveAspectRatio(metrics, preferencesManager)
        val targetWidthPx = (targetHeightPx * screenRatio).roundToInt()

        binding.previewFrameContainer.updateLayoutParams<ViewGroup.LayoutParams> {
            width = targetWidthPx
            height = targetHeightPx
        }

        previewPeekController?.updateDimensions(metrics, preferencesManager)
    }

    // ============================================================================
    // FORENSIC GEOMETRY CONTRACT:
    // 2D Canvas equivalent of GLVideoRenderer.updateMatrix().
    // Translates OpenGL NDC center-origin math into Android View pixel matrix space.
    // If modifying projection or aspect math in GLVideoRenderer, keep both in sync!
    // ============================================================================
    private fun applyPreviewTransform() {
        val binding = _binding ?: return
        val bmp = (binding.thumbnailPreview.drawable as? BitmapDrawable)?.bitmap ?: return
        if (bmp.width <= 0 || bmp.height <= 0) return

        val checkedId = binding.scalingModeGroup.checkedChipId
        val scalingMode = when (checkedId) {
            binding.scalingModeFit.id -> ScalingMode.FIT
            binding.scalingModeStretch.id -> ScalingMode.STRETCH
            else -> ScalingMode.FILL
        }

        previewPeekController?.updateTransform(
            bmp = bmp,
            flipHorizontal = binding.chipFlipHorizontal.isChecked,
            flipVertical = binding.chipFlipVertical.isChecked,
            rotation = binding.rotationSlider.value,
            scalingMode = scalingMode,
            zoom = binding.zoomSlider.value,
            positionX = binding.positionXSlider.value,
            positionY = binding.positionYSlider.value,
            brightness = binding.brightnessSlider.value,
            contrast = binding.contrastSlider.value,
            saturation = binding.saturationSlider.value
        )
    }

    private fun syncUiState() {
        val settings = preferencesManager.getVideoSettings(fileName)
        syncUiStateWith(settings)
    }

    private fun syncUiStateWith(settings: VideoSettings) {
        isUpdatingUi = true
        try {
            val defaults = VideoSettings(fileName)

            binding.chipFlipHorizontal.isChecked = settings.flipHorizontal
            binding.chipFlipVertical.isChecked = settings.flipVertical

            // Mark mirror section as modified if any flip is enabled (default is false)
            binding.mirrorLabel.markIfModified(
                binding.mirrorIcon,
                settings.flipHorizontal || settings.flipVertical,
                false
            )

            when (settings.scalingMode) {
                ScalingMode.FIT -> binding.scalingModeGroup.check(binding.scalingModeFit.id)
                ScalingMode.FILL -> binding.scalingModeGroup.check(binding.scalingModeFill.id)
                ScalingMode.STRETCH -> binding.scalingModeGroup.check(binding.scalingModeStretch.id)
            }
            binding.scalingModeLabel.markIfModified(binding.scalingModeIcon, settings.scalingMode, defaults.scalingMode)

            binding.positionXSlider.setValueSafe(settings.positionX)
            binding.positionXLabel.markIfModified(binding.positionXIcon, settings.positionX, defaults.positionX)
            binding.positionXParallaxHint.visibility =
                if (preferencesManager.isParallaxEnabled()) View.VISIBLE else View.GONE

            binding.positionYSlider.setValueSafe(settings.positionY)
            binding.positionYLabel.markIfModified(binding.positionYIcon, settings.positionY, defaults.positionY)

            binding.zoomSlider.setValueSafe(settings.zoom)
            binding.zoomLabel.markIfModified(binding.zoomIcon, settings.zoom, defaults.zoom)

            binding.rotationSlider.setValueSafe(settings.rotation)
            binding.rotationLabel.markIfModified(binding.rotationIcon, settings.rotation, defaults.rotation)

            binding.brightnessSlider.setValueSafe(settings.brightness)
            binding.brightnessLabel.markIfModified(binding.brightnessIcon, settings.brightness, defaults.brightness)

            binding.contrastSlider.setValueSafe(settings.contrast)
            binding.contrastLabel.markIfModified(binding.contrastIcon, settings.contrast, defaults.contrast)

            binding.saturationSlider.setValueSafe(settings.saturation)
            binding.saturationLabel.markIfModified(binding.saturationIcon, settings.saturation, defaults.saturation)

        } finally {
            isUpdatingUi = false
        }
        applyPreviewTransform()
    }

    private fun updateSettings(updater: (VideoSettings) -> VideoSettings) {
        lifecycleScope.launch(settingsDispatcher) {
            withContext(NonCancellable) {
                preferencesManager.updateVideoSettings(fileName, updater)
                notifySettingsChanged()
            }
        }
    }

    private fun notifySettingsChanged() {
        WallpaperEventBus.emit(WallpaperEvent.VideoSettingsChanged(fileName))
    }

    private fun setupListeners() {
        val defaults = VideoSettings(fileName)

        fun setupSafeSlider(
            slider: Slider,
            label: TextView,
            icon: ImageView,
            defaultVal: Float,
            onLiveChange: ((Float) -> Unit)? = null,
            saveAction: (Float) -> Unit
        ) {
            slider.preventDoubleInput()
            icon.setSafeOnClickListener(debounceMs = 300L) {
                if (abs(slider.value - defaultVal) > 0.001f) {
                    isUpdatingUi = true
                    try {
                        slider.setValueSafe(defaultVal)
                        label.markIfModified(icon, defaultVal, defaultVal)
                    } finally {
                        isUpdatingUi = false
                    }
                    saveAction(defaultVal)
                    applyPreviewTransform()
                }
            }
            slider.addOnChangeListener { _, value, fromUser ->
                if (fromUser && !isUpdatingUi) {
                    onLiveChange?.invoke(value)
                }
            }
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}

                override fun onStopTrackingTouch(slider: Slider) {
                    if (isUpdatingUi) return
                    saveAction(slider.value)
                    label.markIfModified(icon, slider.value, defaultVal)
                }
            })
        }

        binding.chipTabPositioning.preventDoubleInput()
        binding.chipTabColor.preventDoubleInput()
        binding.settingsTabGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val isColorTab = checkedIds[0] == binding.chipTabColor.id
            binding.cardPositioning.visibility = if (isColorTab) View.GONE else View.VISIBLE
            binding.cardColor.visibility = if (isColorTab) View.VISIBLE else View.GONE
            binding.settingsScrollView.scrollTo(0, 0)
        }

        binding.scalingModeFit.preventDoubleInput()
        binding.scalingModeFill.preventDoubleInput()
        binding.scalingModeStretch.preventDoubleInput()

        binding.scalingModeIcon.setSafeOnClickListener(debounceMs = 300L) {
            if (binding.scalingModeGroup.checkedChipId != binding.scalingModeFill.id) {
                isUpdatingUi = true
                try {
                    binding.scalingModeGroup.check(binding.scalingModeFill.id)
                    binding.scalingModeLabel.markIfModified(
                        binding.scalingModeIcon,
                        defaults.scalingMode,
                        defaults.scalingMode
                    )
                } finally {
                    isUpdatingUi = false
                }
                updateSettings { it.copy(scalingMode = defaults.scalingMode) }
                applyPreviewTransform()
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
            binding.scalingModeLabel.markIfModified(binding.scalingModeIcon, newMode, defaults.scalingMode)
            applyPreviewTransform()
        }

        binding.chipFlipHorizontal.preventDoubleInput()
        binding.chipFlipHorizontal.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            updateSettings { it.copy(flipHorizontal = isChecked) }
            binding.mirrorLabel.markIfModified(
                binding.mirrorIcon,
                isChecked || binding.chipFlipVertical.isChecked,
                false
            )
            applyPreviewTransform()
        }

        binding.chipFlipVertical.preventDoubleInput()
        binding.chipFlipVertical.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            updateSettings { it.copy(flipVertical = isChecked) }
            binding.mirrorLabel.markIfModified(
                binding.mirrorIcon,
                binding.chipFlipHorizontal.isChecked || isChecked,
                false
            )
            applyPreviewTransform()
        }

        binding.mirrorIcon.setSafeOnClickListener(debounceMs = 300L) {
            if (binding.chipFlipHorizontal.isChecked || binding.chipFlipVertical.isChecked) {
                isUpdatingUi = true
                try {
                    binding.chipFlipHorizontal.isChecked = false
                    binding.chipFlipVertical.isChecked = false
                    binding.mirrorLabel.markIfModified(binding.mirrorIcon, current = false, default = false)
                } finally {
                    isUpdatingUi = false
                }
                updateSettings { it.copy(flipHorizontal = false, flipVertical = false) }
                applyPreviewTransform()
            }
        }

        setupSafeSlider(
            binding.positionXSlider,
            binding.positionXLabel,
            binding.positionXIcon,
            defaults.positionX,
            onLiveChange = { applyPreviewTransform() }
        ) { value -> updateSettings { it.copy(positionX = value) } }

        setupSafeSlider(
            binding.positionYSlider,
            binding.positionYLabel,
            binding.positionYIcon,
            defaults.positionY,
            onLiveChange = { applyPreviewTransform() }
        ) { value -> updateSettings { it.copy(positionY = value) } }

        setupSafeSlider(
            binding.zoomSlider,
            binding.zoomLabel,
            binding.zoomIcon,
            defaults.zoom,
            onLiveChange = { applyPreviewTransform() }
        ) { value -> updateSettings { it.copy(zoom = value) } }

        setupSafeSlider(
            binding.rotationSlider,
            binding.rotationLabel,
            binding.rotationIcon,
            defaults.rotation,
            onLiveChange = { applyPreviewTransform() }
        ) { value -> updateSettings { it.copy(rotation = value) } }

        setupSafeSlider(
            binding.brightnessSlider,
            binding.brightnessLabel,
            binding.brightnessIcon,
            defaults.brightness,
            onLiveChange = { applyPreviewTransform() }
        ) { value -> updateSettings { it.copy(brightness = value) } }

        setupSafeSlider(
            binding.contrastSlider,
            binding.contrastLabel,
            binding.contrastIcon,
            defaults.contrast,
            onLiveChange = { applyPreviewTransform() }
        ) { value -> updateSettings { it.copy(contrast = value) } }

        setupSafeSlider(
            binding.saturationSlider,
            binding.saturationLabel,
            binding.saturationIcon,
            defaults.saturation,
            onLiveChange = { applyPreviewTransform() }
        ) { value -> updateSettings { it.copy(saturation = value) } }

        binding.buttonResetAdvanced.setSafeOnClickListener(debounceMs = 250L) {
            val defaults = VideoSettings(fileName)
            syncUiStateWith(defaults)
            updateSettings { currentSettings ->
                VideoSettings(
                    fileName = fileName,
                    page = currentSettings.page,
                    durationMs = currentSettings.durationMs,
                    expectedFileSize = currentSettings.expectedFileSize,
                    displayName = currentSettings.displayName
                )
            }
        }
    }

    /**
     * Safely sets the value of a Material Slider, preventing crashes from out-of-range/step values.
     *
     * This extension function ensures that any value assigned to the slider is first clamped
     * to be within the slider's `valueFrom` and `valueTo` range. If the slider has a `stepSize`
     * defined, the function will also snap the clamped value to the nearest valid step.
     *
     * This is useful for programmatically setting slider values that might come from external
     * sources (like saved preferences) without causing an `IllegalArgumentException`.
     *
     * @param newValue The desired new value for the slider.
     */
    private fun Slider.setValueSafe(newValue: Float) {
        // Clamp: Ensure value is strictly between valueFrom and valueTo
        val clampedValue = newValue.coerceIn(valueFrom, valueTo)

        // Snap: If a stepSize is defined, ensure the value fits the step
        val finalValue = if (stepSize > 0) {
            // Calculate how many "steps" we are from the start
            val steps = ((clampedValue - valueFrom) / stepSize).roundToInt()
            // Reconstruct the value based on exact steps
            valueFrom + (steps * stepSize)
        } else {
            clampedValue
        }

        // Apply: Only now is it safe to set the value
        this.value = finalValue
    }

    private fun TextView.applyModifiedState(icon: ImageView, isModified: Boolean) {
        val targetColor = if (isModified) primaryColor else secondaryColor
        val targetAlpha = if (isModified) 1.0f else 0.5f

        if (icon.isClickable != isModified) {
            icon.isClickable = isModified
            icon.setColorFilter(targetColor)
            icon.alpha = targetAlpha
        }

        if (currentTextColor != targetColor) {
            setTextColor(targetColor)
        }

        val isBold = typeface?.isBold == true
        if (isBold != isModified) {
            setTypeface(null, if (isModified) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun TextView.markIfModified(icon: ImageView, current: Float, default: Float) {
        applyModifiedState(icon, abs(current - default) > 0.001f)
    }

    private fun TextView.markIfModified(icon: ImageView, current: Boolean, default: Boolean) {
        applyModifiedState(icon, current != default)
    }

    private fun TextView.markIfModified(icon: ImageView, current: ScalingMode, default: ScalingMode) {
        applyModifiedState(icon, current != default)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        previewPeekController?.cleanup()
        previewPeekController = null
        _binding = null
    }
}
