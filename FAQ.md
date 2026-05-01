### 🧟‍♂️ The Horde's Survival Guide: Troubleshooting & FAQ

**Q: My wallpaper freezes, stops playing, or reverts to a static background. Why?**

**A:** Your phone’s OS is acting like a ruthless zombie hunter. Aggressive battery optimizations are silently killing the wallpaper service in the background.

* **The Fix:** UndeadWallpaper has a built-in "Allow Background Performance" card. Tap "Fix" and follow the instructions to jump into your settings.
* **Crucial Step:** Do _not_ just flip the main background toggle. You must actively tap into the app's battery settings and select **Unrestricted** (or "Don't Optimize"). Once you untether it, the warning card will disappear.

**Q: Can I set a different video for the Lock Screen and the Home Screen?**

**A:** Not currently, and this is due to Android OS limitations. Android's native engine, for a wallpaper running in the background, provide no stable way to detect if it is running in Home or Lock screen.

* Furthermore, manufacturers like **Asus (ROG UI)** are aggressively hardcoded to override third-party apps, forcing the "Both" behavior. Many custom ROMs lock down the lock screen to force you into their monetized theme stores. The app runs in a sandbox and cannot bypass this.

**Q: I have a Xiaomi/POCO/Redmi phone. The video applies to my Home Screen, but my Lock Screen is still static.**

**A:** Xiaomi's MIUI/HyperOS actively blocks third-party live wallpapers on the lock screen. To bypass this:

1. Open your phone's default **Themes** app.
2. Find any default **Live Wallpaper** and apply it to BOTH your Home and Lock screens.
3. Open UndeadWallpaper and apply your video. The OS will now allow the app to override the stock wallpaper.

**Q: Why do my status bar icons turn dark/gray instead of matching the wallpaper colors?**

**A:** UndeadWallpaper actively extracts Material You colors and sends a direct suggestion to your system. If your icons look wrong, your OS is ignoring the app.

* **Samsung Users:** The One UI 8.5 update broke compatibility and ignores standard Android color codes. Instead, it forcefully scans the screen and tries to guess the colors on its own. This still works flawlessly on Pixels, vanilla Android and older One UI versions, but for modern Samsung devices, it is an unfixable OS quirk for now.

**Q: The file picker doesn't show video thumbnails, or the picker is missing entirely!**

**A:** UndeadWallpaper strictly uses Android's native system file picker to guarantee security.

* If thumbnails are missing (grey icons), your specific custom ROM likely stripped out the system media indexer.
* If the file picker crashes or is completely missing, your custom ROM is missing core Android components. Try installing the official **Files by Google** app from the Play Store, or ensure you have properly flashed GApps.

**Q: Why doesn't the app support GIFs or other image sequences?**

**A:** Because a GIF is not really a video... it is an image sequence.

* **The Battery Killer:** Playing a GIF forces your phone's CPU to manually decode and draw every single frame continuously. Apps that allow this cause massive, silent battery drain.
* **The Video Advantage:** Actual video files (`.mp4`, `.mkv`) use hardware acceleration. Your phone has a dedicated chip just for decoding video, which is incredibly efficient and uses almost zero extra battery.
* **The Fix:** If you have a GIF you love, use a free online converter to change it into an `.mp4` file first. Your battery will thank you.
