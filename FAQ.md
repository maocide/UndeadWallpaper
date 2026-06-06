### 🧟‍♂️ The Horde's Survival Guide: Troubleshooting & FAQ

**Q: My wallpaper freezes, stops playing, or reverts to a static background. Why?**

**A:** Your phone’s OS is acting like a ruthless zombie hunter. Aggressive battery optimizations are silently killing the wallpaper service in the background.

* **The Fix:** UndeadWallpaper has a built-in "Allow Background Performance" card. Tap "Fix" and follow the instructions to jump into your settings.
* **Crucial Step:** Do _not_ just flip the main background toggle. You must actively tap into the app's battery settings and select **Unrestricted** (or "Don't Optimize"). Once you untether it, the warning card will disappear.

**Q: Can I set a different video for the Lock Screen and the Home Screen?**

**A:** No, and it is a deliberate architectural choice to protect your battery and sanity. Android’s native Live Wallpaper API is fundamentally disconnected. The system intent completely lacks destination flags (it cannot tell the app *where* it is rendering), and setting a new wallpaper from within an app natively unbinds or overrides the other screen.

* **The not so clean Workarounds:** To trick the system, an app has to register two entirely separate background services. Because the app-side intent can't handle both at once, you are forced to leave the app, dive into your system menus, and blindly guess which identical service to assign to which screen.
* **Resource Starvation:** Managing two separate services means keeping two independent video decoding instances alive in memory while simultaneously tracking two separate playlist queues. This doubles memory requirements, causes battery drain and begs the Android memory manager to assassinate the app in the background.
* **The Verdict:** It would actually take less effort to maintain two completely separate cloned packages of the app than to hack dual-services into a single clean engine. Until Android introduces a standardized, unified API built specifically for dual-stream live wallpapers, UndeadWallpaper will remain focused on a single, perfectly optimized, lightweight pipeline.

**Q: I enabled Home Screen Gestures (Double/Triple Tap), but nothing happens when I tap!**

**A:** If your taps are being ignored, one of these three system rules is blocking them from reaching the wallpaper:

* **The Lock Screen Blockade:** Gestures will *never* work on the lock screen. Android natively blocks live wallpapers from receiving touch inputs on the lock screen for security reasons. This is a hard, unbypassable OS rule.
* **Greedy Custom Launchers:** If you use a custom launcher (like **Nova**, **Smart Launcher**, **Niagara**, etc.), it is likely stealing your taps. For example, if your launcher has a "Double tap to turn off screen" feature enabled, it intercepts your fingers before the wallpaper ever feels them. You must disable the launcher's gesture in its own settings to let the taps pass through to the horde.
* **The Preview Screen:** Gestures are intentionally disabled while you are looking at the system's "Apply Wallpaper" preview screen to prevent hardware bugs on certain manufacturer interfaces (like Vivo, Oppo, or Xiaomi). Apply the wallpaper first, then tap your actual Home Screen.

**Q: I have a Xiaomi/POCO/Redmi phone. The video applies to my Home Screen, but my Lock Screen is still static.**

**A:** Xiaomi's MIUI/HyperOS actively blocks third-party live wallpapers on the lock screen. To bypass this:

1. Open your phone's default **Themes** app.
2. Find any default **Live Wallpaper** and apply it to BOTH your Home and Lock screens.
3. Open UndeadWallpaper and apply your video. The OS will now allow the app to override the stock wallpaper.

**Q: Why do my status bar icons turn dark/gray instead of matching the wallpaper colors?**

**A:** UndeadWallpaper actively extracts Material You colors and sends a direct suggestion to your system. If your icons look wrong, your OS is ignoring the app.

* **Samsung Users:** The One UI 8.5 update broke compatibility and ignores standard Android color codes. Instead, it forcefully scans the screen and tries to guess the colors on its own. This still works flawlessly on Pixels, vanilla Android, and older One UI versions, but for modern Samsung devices, it is an unfixable OS quirk for now.

**Q: The file picker doesn't show video thumbnails, or the picker is missing entirely!**

**A:** UndeadWallpaper strictly uses Android's native system file picker to guarantee security and privacy.

* If thumbnails are missing (grey icons), your specific custom ROM likely stripped out the system media indexer.
* If the file picker crashes or is completely missing, your custom ROM is missing core Android components. Try installing the official **Files by Google** app from the Play Store, or ensure you have properly flashed GApps.

**Q: Why doesn't the app support GIFs or other image sequences?**

**A:** Because a GIF is not really a video... it is an image sequence.

* **The Battery Killer:** Playing a GIF forces your phone's CPU to manually decode and draw every single frame continuously. Apps that allow this cause massive, silent battery drain.
* **The Video Advantage:** Actual video files (`.mp4`, `.mkv`) use native hardware acceleration. Your phone has a dedicated silicon chip just for decoding video, which is incredibly efficient and uses almost zero extra battery (perfect for handheld gaming devices and power users!).
* **The Fix:** If you have a GIF you love, use a free online converter to change it into an `.mp4` file first. Your battery will thank you.

**Q: When I open my app drawer, the background blurs a static image of Zombillie instead of my video. Why?**

**A:** Your phone’s manufacturer is taking a lazy shortcut to save battery.

* **The Technical Reason:** Blurring live video in real-time requires constant GPU power. Aggressive custom interfaces (like Infinix's XOS or certain Xiaomi builds) refuse to do this. Instead, their launcher asks the OS for the app's default, hardcoded static thumbnail—our mascot, Zombillie—and just blurs that image instead.
* **The Fix:** Because Android requires that fallback thumbnail to be permanently baked into the app's installation file, third-party apps cannot dynamically change it to match your video. To get a true, live video blur, you either have to switch to a well-behaved custom launcher (like **Nova** or **Smart Launcher**) or just embrace your new zombie app drawer companion!