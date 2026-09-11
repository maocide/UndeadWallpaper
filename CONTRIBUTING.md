# Contributing to UndeadWallpaper 🧟‍♂️

So, you want to join the horde and help build this abomination? Awesome. Contributions, bug reports, and optimizations are always welcome. 

However, this project was brought back from the dead with a very specific vision. To keep the codebase clean, performant, and focused, we play by a strict set of rules. Before you open an issue or submit a Pull Request, read this. 

**Note: We only accept PRs and issue reports for the latest active version. Legacy architectures (v1.3.8 and earlier) are officially deprecated and unsupported.**

## The Three Pillars of Survival

If your PR breaks any of these, it will be closed without review.

### 1. The UNIX Philosophy (Scope is Sacred)
UndeadWallpaper does one thing, and it does it well: it puts videos on your home screen smoothly. 
* **We are not a file manager.**
* **We are not a gallery app.**
* **We do not download content from the web.**
If a feature falls outside the core scope of rendering and managing the live wallpaper itself, it belongs in another app. Feature bloat is the enemy.

### 2. Performance & Battery Life are King
This app runs as a background service 24/7. Even a "minor" inefficiency will compound over the course of a day and murder a user's battery.
* Any code that introduces memory leaks, tanks the frame rate, or keeps the CPU awake unnecessarily is dead on arrival.
* Features must be optimized for the long haul, not just for a quick screenshot.

### 3. UI/UX Adherence (The "Two-Tap" Rule)
The core user experience is sacred: **Pick a video, hit the button, done.** Two taps to get a wallpaper running.
* We do not clutter the main interface. 
* We do not add mandatory configuration steps that interrupt the initial flow.
* Advanced settings (like scaling, and offset) belong in the Video Settings Sheet, completely out of the way of new users. 
If your feature proposal disrupts this core flow or crams another control into the main view without consideration, it will be rejected. 

This applies to both Pull Requests and Issue proposals. If you open an Issue suggesting an architectural rewrite or a feature outside our core scope, it will be closed immediately.

## The AI Policy (Human Accountability)
It's 2026. We know you use AI tools. We use AI tools with a human brain. They are the modern equivalent of Stack Overflow or a good compiler. 

However, **you are strictly responsible for every single line of code you submit.**
* Automated agents, bulk-generated PRs, and blind copy-pasting will be instantly rejected.
* If you submit a fix or a feature, you must be able to fully explain how it works, why it's the best approach, and how it impacts the Three Pillars. 
* If it looks like you pasted a prompt output without actually understanding the architecture of the app, your PR will be closed.

Whether you are submitting a Pull Request or opening a Feature Request Issue, you are strictly responsible for the content. AI-generated architectural essays or feature-bloat wishlists will be closed without engagement.

## How to Submit a PR
1. **Discuss it first:** Unless it's a simple typo fix, open an issue to discuss your idea before writing code. Don't waste your time building something that violates the Three Pillars.
2. **Keep it focused:** One PR = One fix/feature. Do not bundle UI tweaks with backend engine rewrites.
3. **Test it:** Ensure it works across different Android versions without breaking the performance optimized ExoPlayer pipeline.

Stay focused, keep the code clean, and let's keep this thing alive or undead.
