# Contributing to UndeadWallpaper 🧟‍♂️

UndeadWallpaper is developed and maintained as an independent open-source project.

The engine was forged with an uncompromising focus: **zero-bloat, battery-friendly, hardware-accelerated video rendering with 0% telemetry.** To preserve architectural integrity, ensure battery longevity, and prevent dependency bloat, development follows a strict set of principles.

---

## The Three Pillars of Survival

Every change, proposal, or contribution must strictly adhere to these three rules:

### 1. The UNIX Philosophy (Scope is Sacred)
UndeadWallpaper does one thing, and it does it well: it renders video seamlessly on the Android home screen.
* **We are not a file manager.**
* **We are not a gallery app.**
* **We do not download content from the web.**  
If a feature falls outside the core scope of managing and rendering live wallpapers, it belongs in another application.

### 2. Performance & Battery Life are King
This engine runs as an ongoing background service. Even micro-inefficiencies compound over 24 hours and drain battery.
* Any code introducing memory leaks, thread starvation, unnecessary wakelocks, or decoding hitches will not be merged.
* Features must be engineered for long-term stability, not quick screenshots.

### 3. UI/UX Adherence (The "Two-Tap" Rule)
The core interaction flow is sacred: **Pick a video, hit the button, done.**
* The primary dashboard remains clean and uncluttered.
* Advanced transformation knobs (zoom, pan, rotation, brightness) belong exclusively in the Video Settings Sheet, out of the way of everyday users.

---

## The AI Policy (Human Accountability)

AI tools are part of the modern developer workflow—just like compilers, profilers, and documentation. We embrace modern tooling when paired with a human brain.

However, **you are strictly responsible for every line of code you submit:**
* Blind copy-pasting, automated agent dumps, and unvetted AI outputs will be closed immediately.
* If you propose a change, you must be able to explain the underlying systems mechanics, how it affects memory allocation, and how it respects the Three Pillars.
* AI-generated architectural essays or speculative feature-bloat wishlists without working prototypes will be closed without engagement.

---

## The Power of GPLv3 (Copyleft Freedom)

UndeadWallpaper is licensed under the **GNU General Public License v3.0**.

If you need capabilities outside our core scope:
* **Fork the code:** You have the absolute legal right under the GPLv3 to fork this repository, experiment, and deploy your own variations.
* **Maintain your fork:** You take full responsibility for your fork's architecture, battery impact, and ongoing maintenance.
* **Respect the Copyleft:** Any derivative distribution must remain open source under the GPLv3, with complete matching source code provided.

---

## Deprecation & Binary Integrity

* **Latest Architecture Only:** Active development, issue tracking, and maintenance apply strictly to the latest release (`v1.4.7+`) targeting Android 12+ (`API 31+`).
* **Zero Legacy Backports:** Builds prior to the current release are permanently deprecated. Historical binaries are not maintained, supported, or mirrored.
* **Untrusted Repacks:** Official builds are distributed strictly through **Google Play** (or compiled directly from this source repository). Third-party mirrors, automated APK scrapers, or modified repacks with altered signatures are untrusted and unsupported.

---

## Submitting Changes

1. **Discuss First:** Before writing code, open an issue to discuss your proposal and ensure it aligns with the engine's architectural goals.
2. **Keep it Atomic:** One pull request = one specific fix or enhancement. Do not bundle UI refactors with engine modifications.
3. **Verify on Hardware:** Test your changes on physical Android hardware across different refresh rates and OS versions.


Keep the code clean, and let's keep this thing alive or undead.
