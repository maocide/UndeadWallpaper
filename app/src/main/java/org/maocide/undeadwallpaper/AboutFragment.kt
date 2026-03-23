package org.maocide.undeadwallpaper

import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.maocide.undeadwallpaper.databinding.FragmentAboutBinding

/**
 * A simple [Fragment] subclass as the second destination in the navigation, the about page.
 */
class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private lateinit var preferencesManager: PreferencesManager

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        preferencesManager = PreferencesManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState) // Moved to top

        // Use ViewBinding to access TextViews and set MovementMethod
        binding.textviewGithub.movementMethod = LinkMovementMethod.getInstance()
        binding.textviewLicense.movementMethod = LinkMovementMethod.getInstance()

        setupDebugOptions()
    }

    private fun setupDebugOptions() {
        // Initialize the switch state based on preferences
        binding.switchEnableLogging.isChecked = preferencesManager.isLoggingEnabled()
        updateLogButtonsState()

        // Handle Switch Toggle
        binding.switchEnableLogging.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.saveLoggingEnabled(isChecked)
            FileLogger.setLoggingEnabled(isChecked)

            if (isChecked) {
                Toast.makeText(requireContext(), "Local file logging enabled", Toast.LENGTH_SHORT).show()
                // Ensure logger is initialized if this is the first time it's enabled manually
                FileLogger.init(requireContext().applicationContext)
            } else {
                Toast.makeText(requireContext(), "Local file logging disabled", Toast.LENGTH_SHORT).show()
            }
        }

        // Handle Share Button
        binding.buttonShareLog.setOnClickListener {
            val logFile = FileLogger.getLogFile()
            if (logFile != null && logFile.exists() && logFile.length() > 0) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    logFile
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(Intent.createChooser(intent, "Share Debug Log"))
            } else {
                Toast.makeText(context, "Log file not found or empty", Toast.LENGTH_SHORT).show()
                updateLogButtonsState() // Refresh state
            }
        }

        // Handle Clear Button
        binding.buttonClearLog.setOnClickListener {
            FileLogger.clearLog()
            Toast.makeText(context, "Log file cleared", Toast.LENGTH_SHORT).show()
            updateLogButtonsState()
        }
    }

    override fun onResume() {
        super.onResume()
        updateLogButtonsState()
    }

    private fun updateLogButtonsState() {
        val logFile = FileLogger.getLogFile()
        val hasLogs = logFile != null && logFile.exists() && logFile.length() > 0

        binding.buttonShareLog.isEnabled = hasLogs
        binding.buttonClearLog.isEnabled = hasLogs
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}