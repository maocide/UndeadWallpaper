package org.maocide.undeadwallpaper

import android.net.Uri
import androidx.lifecycle.ViewModel

class SettingsViewModel : ViewModel() {
    // The preview still needs a URI, but selection is tracked by item ID now.
    var selectedItemId: String? = null
    var selectedVideoUri: Uri? = null
}
