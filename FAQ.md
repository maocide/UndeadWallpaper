### 🧟‍♂️ The Horde's Survival Guide: Troubleshooting & FAQ

**Q: My wallpaper freezes, stops playing, or reverts to a static background. Why?**

**A:** Your phone’s OS is acting like a ruthless zombie hunter. Aggressive battery optimizations are silently killing the wallpaper service in the background.

* **The Fix:** UndeadWallpaper has a built-in "Allow Background Performance" card. Tap "Fix" and follow the instructions to jump into your settings.
* **Crucial Step:** Do _not_ just flip the main background toggle. You must actively tap into the app's battery settings and select **Unrestricted** (or "Don't Optimize"). Once you untether it, the warning card will disappear.

**Q: Can I set a different video for the Lock Screen and the Home Screen?**

**A:** **No.** Android’s native Live Wallpaper API has a fundamental architectural disconnect: while the system picker lets you manually assign services to Home or Lock, an app cannot programmatically target a specific screen or even query which screen a running `WallpaperService` is bound to (a gap that remains unfixed through Android 17).

* **Why Dual-Service Workarounds Fail:** Supporting both from one app requires hardcoding two duplicate `WallpaperService` stubs, alternating video bindings blindly, and dumping users into Android's confusing system picker to guess which service goes where—usually limited to one static clip per screen.
* **Hardware Decoder Exhaustion:** Running two concurrent video playlists inside one app process/VM collides directly over hardware `MediaCodec` decoders, triggering frame freezes and decoder starvation whenever the OS switches between Lock and Home. *(Two isolated app VMs handle dual hardware decoding cleaner than jamming duplicated services into one package.)* Until Android exposes a unified multi-target Live Wallpaper API, UndeadWallpaper remains focused on one deterministic, decoder-safe pipeline.

**Q: How do Undead Affects work? Why don\'t they trigger on some phones?**

**A:** Undead Affects is a high-performance kinetic physics engine calculated via real-time spring math and vsync Choreographer interpolation. Reactions (Shake, Hop, Bounce) are projected instantaneously through OpenGL matrix transforms at your display's native refresh rate—with synchronized haptics and zero video decoder overhead.

* **Launcher Touch Limitations:** Live wallpapers can only react to touches if your desktop launcher actively forwards window touch events through to the background wallpaper layer. Standard Android and customizable launchers (such as Nova, Niagara, or Smart Launcher) forward these touches seamlessly. However, certain aggressive proprietary OEM launchers (such as some MIUI/HyperOS, EMUI, or XOS builds) completely swallow all touch inputs at the desktop surface, blocking any live wallpaper from receiving tap events.

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
* **The Fix:** Convert the GIF into an `.mp4` clip first.

**Q: When I open my app drawer, the background blurs a static image of Zombillie instead of my video. Why?**

**A:** Your phone’s manufacturer is taking a lazy shortcut to save battery.

* **The Technical Reason:** Blurring live video in real-time requires constant GPU power. Aggressive custom interfaces (like Infinix's XOS or certain Xiaomi builds) refuse to do this. Instead, their launcher asks the OS for the app's default, hardcoded static thumbnail—our mascot, Zombillie—and just blurs that image instead.
* **The Fix:** Because Android requires that fallback thumbnail to be permanently baked into the app's installation file, third-party apps cannot dynamically change it to match your video. To get a true, live video blur, you either have to switch to a well-behaved custom launcher (like **Nova** or **Smart Launcher**) or just embrace your new zombie app drawer companion!