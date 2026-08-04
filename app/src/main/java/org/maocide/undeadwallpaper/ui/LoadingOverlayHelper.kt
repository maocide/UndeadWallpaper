package org.maocide.undeadwallpaper.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.maocide.undeadwallpaper.R

/**
 * A Flutter-style UI Helper for displaying an indeterminate progress overlay.
 * Uses structured concurrency to bind the overlay's lifecycle to the background task.
 */
suspend fun Fragment.withLoadingOverlay(
    message: String,
    block: suspend () -> Unit
) = coroutineScope {
    val rootView = requireActivity().findViewById<ViewGroup>(android.R.id.content) ?: return@coroutineScope block()
    
    val overlayView = LayoutInflater.from(requireContext()).inflate(R.layout.layout_loading_overlay, rootView, false)
    
    // Set message
    overlayView.findViewById<TextView>(R.id.tv_loading_message)?.text = message
    
    // Launch the child job
    val taskJob = launch { block() }
    
    // Bind cancel button to the child job
    overlayView.findViewById<Button>(R.id.btn_cancel_loading)?.setOnClickListener {
        taskJob.cancel()
    }
    
    // Add to window
    rootView.addView(overlayView)
    
    try {
        taskJob.join() // Suspend until the block finishes or is cancelled
    } finally {
        rootView.removeView(overlayView)
    }
}
