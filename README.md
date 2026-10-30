<div align="center">

<img src="docs/assets/icon-512.png" width="96" alt="TV Launcher Logo">

# TV Launcher

**A fast, clean, ad-free home screen and launcher for Android TV and Google TV.**

Trending picks from your own apps, universal search, custom shelves, and a look that follows the sunset.<br>
Open source fork tailored for speed, customization, and privacy.

[**GitHub Repository**](https://github.com/flaviohuertas/tv-launcher) · [Install](#install) · [Features](#exclusive-features) · [Building on Windows](#building-on-windows) · [Settings](#settings) · [Credits](#credits)

<img src=".github/preview.webp" width="880" alt="TV Launcher home screen">

</div>

## What you get

- **No ads.** No sponsored rows, banners or autoplaying promos. Not one.
- **Your apps, front and center.** The streaming apps you actually use, on a sleek glass shelf. Pick them and their order right on the TV.
- **Trending, not sponsored.** Fresh picks from what's trending this week and latest releases, only from apps you have, and from the services you choose.
- **Universal Search.** Search movies, series, and installed apps in one place with voice search support and direct app launching.
- **Content Tabs.** Categorized navigation for Explore, Movies, Series, Anime, Kids, and Documentaries.
- **Enhanced Continue Watching.** Configurable number of items (3 to 20) with advanced privacy controls (hide items, auto-unhide when rewatched).
- **Weather and markets, one press away.** Hour-by-hour forecast (or next 5 days), sunrise/sunset, and live crypto prices.
- **What's playing.** Background music, podcasts, or radio shown on the home screen and screensaver.
- **Light by day, dark by night.** Follows your city's real sunset, just like your phone.
- **Fast & Lightweight.** 60 fps smooth animations with minimal memory footprint on the TV's own chip.
- **Safe.** No root required. Nothing is uninstalled. One button restores the factory home screen anytime.

---

## Exclusive Features in this Version

### 1. Refined Layout & Proportions
- **Compact Shelf:** App shelf size reduced by 20%, maintaining crisp 16:9 banners and freeing up vertical screen space.
- **Expanded Dual Highlights:** Larger, high-impact highlight cards for trending content (wide backdrop & tall poster layout).

### 2. Universal Search
- Search dialog accessible directly from the top bar.
- Split-screen detail view (40% list / 60% detail pane) with poster art, ratings, release year, overview, and direct launch buttons.
- Voice search integration for hands-free queries.

### 3. Categorized Content Tabs
- Browse curated picks across specialized categories: **Explore**, **Movies**, **Series**, **Anime**, **Kids**, and **Documentaries**.
- Toggle and order tabs directly in *Home Settings*.

### 4. Advanced Continue Watching & Privacy
- **Configurable Item Count:** Choose between 3 and 20 items in *Settings → Home Settings → Continue Watching items*.
- **Hide / Privacy Control:** Hold **OK** (400 ms) or press **Menu** / **Info** on any card to remove it from Continue Watching.
- **Smart Auto-Unhide:** If you resume watching that content in any streaming app (playback advances, position changes, or episode changes), it automatically reappears on your home screen!
- **Nuvio & Stremio Support:** Native support for `com.nuvio.tv` and popular streaming channel providers.

### 5. Windows Native Build (`build_win.py`)
- Direct build script for Windows environments without requiring heavy IDEs or Gradle.

---

## Install

You need a Google TV or Android TV device (Android 8 or newer) and a computer on the same Wi-Fi network.

### Step 1: Enable USB Debugging on your TV
1. On the TV, go to **Settings → System → About**.
2. Press **Android TV OS build** 7 times until Developer mode is enabled.
3. Go back to **Settings → System → Developer options** and enable **USB debugging**.
4. Note your TV's IP address in **Settings → Network & Internet** (e.g. `192.168.100.5`).

### Step 2: Build or Get the APK
Compile the APK on your computer using the Windows script or shell script (see [Building](#building)).

### Step 3: Install via ADB
From your terminal:
```bash
adb connect <TV_IP>:5555
adb install -r FreeMyTV.apk
```
*Tip:* If Google Play Protect displays *Risky app blocked*, select **More details → Install anyway**.

On TCL TVs, allow background execution:
```bash
adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"
```

### Step 4: Activate TV Launcher
1. Open **TV Launcher** on your TV from your apps list.
2. Select **Home Screen → TV Launcher**.
3. When prompted *Allow USB debugging?*, check **Always allow** and accept.

---

## Building

### Building on Windows (Recommended)
This repository includes a standalone Python build script tailored for Windows that doesn't need Gradle:

```powershell
# Prerequisites: Python 3 and Java JDK 17+ installed and on PATH
python build_win.py
```
The script automatically downloads necessary lightweight Android SDK tools (`aapt2`, `d8`, `zipalign`, `apksigner`) into `tools-build/`, compiles all Java sources, links resources, packages, and signs `FreeMyTV.apk`.

### Building on Linux / macOS
```bash
./build.sh
```

---

## Settings & Configuration

Settings can be customized locally in `config.properties` before building, or directly on the TV via **TV Launcher → Home Settings**:

| Key | What it does | Default |
|---|---|---|
| `tmdb_key` | Free TMDB API key for trending picks and search | empty |
| `tmdb_region` | Country code for streaming availability (e.g. `BR`, `US`, `PT`) | `US` |
| `watch_items` | Number of items shown in Continue Watching (3..20) | `10` |
| `latitude`, `longitude` | Coordinates for local weather and sunset calculations | New York |
| `city` | Name displayed on the weather widget | `Weather` |
| `language` | Language code (`auto`, `pt`, `en`, `es`, `de`, etc.) | `auto` |
| `show_crypto` | Display crypto ticker prices | `true` |
| `currency` | Currency for crypto prices (`BRL`, `USD`, `EUR`) | `USD` |
| `temperature` | Temperature scale (`C`, `F`, `auto`) | `auto` |
| `theme` | UI appearance (`auto`, `light`, `dark`) | `auto` |

### Setting up TMDB Key
1. Create a free account at [themoviedb.org](https://www.themoviedb.org/signup).
2. Go to [Settings → API](https://www.themoviedb.org/settings/api) and generate an API key.
3. On the TV, open **Home Settings → TMDB key** and scan the QR code with your phone to paste the key, or enter it directly in `config.properties`.

---

## Privacy & Safety

- **No telemetry or data collection.** The launcher does not track usage or transmit analytics.
- **Zero remote servers.** It communicates only with TMDB (for posters/metadata), Open-Meteo (for weather), and Binance (for crypto if enabled).
- **Safety net included.** A built-in watchdog switches back to the factory launcher if multiple crashes occur in quick succession.
- **One-click restore.** Switch back to your original factory launcher anytime by selecting **Home Screen → Original** in settings.

---

## Credits & Upstream

- Based on the excellent [Free My TV](https://github.com/simbasjungles/free-my-tv) project by [@simbasjungles](https://github.com/simbasjungles).
- Icons from [Material Symbols](https://fonts.google.com/icons) (Apache 2.0).
- Typography: [Inter](https://rsms.me/inter/) (SIL OFL 1.1).
- Metadata provided by [The Movie Database (TMDB)](https://www.themoviedb.org/).
- Weather data provided by [Open-Meteo](https://open-meteo.com/).

---

## License

[MIT](LICENSE).
