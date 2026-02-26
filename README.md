Wholphin Enhanced - A Custom Android TV Client for Jellyfin


Wholphin Enhanced is a heavily modified build of the open-source Wholphin Android TV client for Jellyfin. It includes a wide range of UI, playback, and quality-of-life improvements on top of the original app, with the goal of delivering a premium streaming experience on Android TV devices.

✨ What's New in Wholphin Enhanced!

🎨 UI, Visuals & Navigation

Premium Visual Overhaul — Comprehensive UI tweaks across the entire app to modernize the interface, smooth out transitions, and give the application a polished, top-tier streaming service feel.
Revamped "Continue Playing" & "Next Up" — Completely redesigned how ongoing movies and upcoming TV episodes are displayed, making the layout more intuitive and visually appealing.
Genre Hubs — Dedicated Genre lists natively added to the Movies and TV Shows pages, allowing for deep, categorized browsing without needing to search manually.
Dedicated "My List" — A standard, easily accessible "My List" feature to queue up movies and shows for later viewing.
Custom Screensaver — A native screensaver that automatically triggers after 10 minutes of inactivity to protect TV displays.

[PXL_20260226_030141035](https://github.com/user-attachments/assets/9305ffa6-16f6-4d38-8dc2-32b6f38dff2c)

🎮 Quality of Life & Controls

Long-Press to Favorite — Quickly add items to your favorites by long-pressing the select button on any poster.

📼 Playback & Media Engine

Direct Play by Default — Rewrote the player logic to prioritize Direct Play, ensuring maximum local network quality without unnecessarily stressing the Jellyfin server.
Granular Transcoding Presets — A custom video quality selector in the player lets you force specific transcode resolutions (e.g., stepping a 4K file down to 480p) to manage bandwidth constraints.
Quick Audio Switching — A dedicated, seamless button directly inside the playback UI to swap audio tracks and languages on the fly without interrupting the viewing experience.

🍿 SyncPlay (Watch Party) — Experimental

WebSocket Engine Integration — A custom background manager built using the Jellyfin SDK to listen for server commands, discover active network rooms, and maintain a localized UTC heartbeat.
Native UI Dialog — A custom popup menu that dynamically fetches open rooms and allows the user to join or create Watch Parties directly from the player.


Note: SyncPlay is currently in beta. It successfully discovers groups, joins rooms, tracks participant counts, and receives commands from the server. Final sync execution (perfectly matching timestamps and play/pause states across devices) is still being optimized.


📱 Compatibility
Requires Android 6+ (or Fire TV OS 6+) and Jellyfin server 10.10.x or 10.11.x.

## 📲 Installation

### Step 1 — Download the APK

Download the latest `.apk` file directly from the [latest stable release](https://github.com/AylaTheTanuki/Wholphin-Enhanced/releases/tag/WholphinEnhancedStable) to your phone or computer.

### Step 2 — Enable Sideloading on your TV

Before you can install apps from outside the app store, you need to allow it on your TV:

- **Android TV / Google TV:** Go to **Settings → Device Preferences → Security & Restrictions → Unknown Sources** and enable it for your file manager or the Files app.
- **Fire TV:** Go to **Settings → My Fire TV → Developer Options → Install Unknown Apps** and enable it for Downloader or your file manager.

### Step 3 — Transfer the APK to your TV

The easiest way is using the free **Send Files to TV** app:

1. Install **Send Files to TV** from the Google Play Store or Amazon AppStore on **both your phone and your TV**.
2. Open the app on your **TV** and tap **Receive**.
3. Open the app on your **phone**, tap **Send**, find the downloaded APK file, and send it to your TV.
4. On the TV, accept the incoming file.

### Step 4 — Install the APK

1. Once the file has transferred, **Send Files to TV** will prompt you to open it — tap **Open**.
2. A system prompt will appear asking if you want to install the app — tap **Install**.
3. Once installed, tap **Open** or find **Wholphin Enhanced** in your apps.

### Step 5 — Connect to your Jellyfin server

Open the app, enter your Jellyfin server address, log in, and you're good to go!
