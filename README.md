<div align="center">

# Veldash

**Ultra-lightweight offline/online navigation system engineered for resource-constrained Android head units (1 GB RAM, Android 5.0+).**

[![Platform](https://img.shields.io/badge/Platform-Android%205.0%2B%20%7C%20Automotive-3DDC84.svg?style=flat-square&logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF.svg?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Engine](https://img.shields.io/badge/Renderer-MapLibre%20Native-003366.svg?style=flat-square)](https://maplibre.org)
[![Routing](https://img.shields.io/badge/Offline%20Routing-BRouter-008080.svg?style=flat-square)](https://brouter.de)
[![Memory Target](https://img.shields.io/badge/RAM%20Footprint-%3C%2080%20MB%20PSS-brightgreen.svg?style=flat-square)](#memory-tuning-measured)
[![License](https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square)](LICENSE)
[![Legal Notice](https://img.shields.io/badge/IP%20Notice-Fan%20Project%20%2F%20Non--Commercial-orange.svg?style=flat-square)](LEGAL.md)

</div>

---

> [!IMPORTANT]
> **Legal Notice & Trademark Disclaimer (DC Comics / Warner Bros. Discovery)**
> 
> **Batman**, the **Batmobile**, **Wayne Enterprises**, **Batcave**, **The Joker**, **Catwoman**, **The Riddler**, and the **Bat-symbol** are registered trademarks and copyrights of **DC Comics, Inc.** and **Warner Bros. Discovery**.
> 
> **Veldash** is an independent, non-commercial open-source fan tribute and educational research project. It is **not** endorsed by, sponsored by, or affiliated with DC Comics or Warner Bros.
> 
> **DO NOT distribute or upload builds containing Batman trademarks or assets to commercial storefronts (such as the Google Play Store).** The core navigation architecture is modular and can be used with generic automotive markers. See [LEGAL.md](LEGAL.md) for full intellectual property details, asset provenance, and de-theming instructions.

---

## Overview

Modern turn-by-turn navigation apps (Google Maps, Waze, HERE WeGo) are bloated with background tracking services, complex component hierarchies, and heavy memory footprints (often exceeding 400–600 MB of RAM). On low-cost aftermarket Android head units—frequently equipped with only **1 GB of RAM** and older quad-core ARM SoCs—this results in aggressive process terminations, UI lag, and dangerous freezes while driving.

**Veldash** is designed from scratch to deliver a smooth, high-contrast, distraction-free navigation experience within an ultra-lean memory budget of **~80 MB PSS (~40 MB private dirty)**.

### Key Engineering Highlights

- **Zero-Framework Overhead**: Extends plain `android.app.Activity` without `AppCompatActivity`, Jetpack Compose, or Material Components. ViewBinding provides compile-time safety with zero runtime reflection.
- **Direct Filesystem In-Place Vector Rendering**: Built on MapLibre Native SDK (OpenGL ES 2.0). Map tiles are read directly from local `.mbtiles` files on flash storage with all memory-hogging tile caches evicted (`MapSetup.kt`).
- **Dual-Tier Offline/Online Routing**:
  - **Online**: Mapbox Directions API or high-performance OSRM.
  - **Offline**: Embedded pure-Java [BRouter](https://github.com/abrensch/brouter) engine reading 5° × 5° `.rd5` segments with zero graph-loading heap penalties.
- **Pseudo-3D Directional Sprite Engine**: Pre-rendered 24-frame multi-angle WebP sprite sequence with real-time continuous alpha cross-fading and heading lag—delivers authentic 3D chasing perspective with **zero 3D engine overhead** (`CarSprites.kt`).
- **Tactical Dark HUD ("Ink Glass")**: Edge-to-edge dark map theme with flat 90% near-black glass floating islands, high-contrast yellow route accents, and bold typography designed for high automotive legibility in bright sunlight and dark night driving.
- **Automotive Native**: Fully compatible with Android Automotive OS (AAOS) distraction-optimization rules, handles standard `geo:` system intents, and backports TLS 1.3 to Android 5.0–9.0 via Conscrypt.

---

## Dashboard & User Interface

The interface is structured into floating dark-glass islands overlaying the edge-to-edge vector map:

```
┌─────────────────────────────────────────────────────────────┐
│ [ Where to? / Destination Pill ]              [ (MAPS) ]    │
│ ┌──────────────────────────────┐                            │
│ │ ↱ 200 m · Turn right onto    │                            │
│ │   Tsimiski Street            │                            │
│ └──────────────────────────────┘                            │
│                                                             │
│                                                             │
│                         ▲ [Batmobile]                       │
│                                                             │
│ ┌──────────────────────────────┐              ┌───────────┐ │
│ │ 54 km/h  │  14 min           │              │   [3D]    │ │
│ │          │  8.2 km · 14:32   │              │ [Recenter]│ │
│ └──────────────────────────────┘              └───────────┘ │
└─────────────────────────────────────────────────────────────┘
```

- **Destination Pill** (Top-Left): Displays "Where to?" when idle (tap to open Search & Favorites). While navigating, displays the destination name and place badge alongside a quick End-Route button.
- **Next-Turn Card** (Top-Left, below pill): High-visibility yellow accent bar, directional maneuver icon, distance countdown, and target street name.
- **Maps Switcher** (Top-Right): Quickly loads or hot-swaps `.mbtiles` map files on device storage.
- **Trip Status Island** (Bottom-Left): Speedometer badge alongside ETA, remaining distance, and arrival time.
- **Camera Controls** (Bottom-Right): 
  - `3D` / `2D` toggle (yellow glow when 3D chase mode is active).
  - Recenter button (re-locks camera onto the vehicle; panned-away states turn it into a solid yellow disc).

---

## Memory & Performance Benchmarks

Measured on an Android Automotive 13 emulator (1080×600 resolution) rendering dense downtown Thessaloniki at zoom level 16 under continuous panning. Figures captured via `dumpsys meminfo`:

| Configuration | Native Heap | Total PSS | Private Dirty |
| :--- | :---: | :---: | :---: |
| Default MapLibre (Tile cache on, prefetch 2, 3D buildings) | 70 MB | 128 MB | 86 MB |
| **Veldash Tuned** (Cache off, prefetch 0, 3D buildings) | 28 MB | 82 MB | 42 MB |
| **Veldash Ultra-Lean** (Cache off, prefetch 0, 2D flat) | 25 MB | 78 MB | 38 MB |
| Rich style (casings, landcover, buildings z16+), 2D | 36 MB | 83 MB | 51 MB |
| **Rich style in full 3D chase view (pitch 50° during drive)** | **40–45 MB** | **86–93 MB** | **53–61 MB** |

> [!NOTE]
> Out of the ~86 MB PSS during an active 3D navigation drive, active application heap allocations are only ~31–33 MB; the remainder consists of shared system libraries and file-backed executable code that the Android kernel can discard under low memory conditions.

For deep architectural details, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Getting Started & Building

### Prerequisites
- JDK 17+ (Java 21 recommended)
- Android SDK (API 35 compile, minSdk 21)
- Gradle 8.7+ (wrapper included)

### Clone & Build
```bash
git clone https://github.com/DimitrisMilios/veldash.git
cd veldash
./gradlew :app:assembleRelease
```

### ABI Split APKs
Automotive head units run ARM processors. To keep the APK payload small, Veldash generates separate per-ABI APKs:
- `app/build/outputs/apk/release/app-armeabi-v7a-release.apk` (32-bit: universal compatibility for all ARM head units)
- `app/build/outputs/apk/release/app-arm64-v8a-release.apk` (64-bit ARM)
- `app/build/outputs/apk/release/app-x86_64-release.apk` (Emulator testing only)

### Firebase App Distribution
To push an update directly to testers or your connected vehicle over-the-air:
```bash
# Upload 64-bit ARM build
./gradlew :app:assembleRelease :app:appDistributionUploadRelease

# Upload 32-bit ARM build (for older 32-bit head units)
./gradlew :app:assembleRelease :app:appDistributionUploadRelease -PabiToUpload=armeabi-v7a
```

---

## Offline Maps & Routing Data Setup

Veldash navigates completely offline when map files and routing segments are copied to the device.

### 1. Bundled Offline Data (Thessaloniki)
To package an offline vector map and BRouter segment directly into the APK assets for out-of-the-box navigation:
```bash
JAVA="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" tools/fetch-bundled-data.sh
```
This downloads Planetiler, fetches OpenStreetMap extracts for Greece, and compiles `thessaloniki.mbtiles` and BRouter segment `E20_N40.rd5` into `app/src/main/assets/`.

### 2. Loading Maps onto the Head Unit
Copy your `.mbtiles` files (OpenMapTiles vector schema) to:
```
/sdcard/Android/data/com.papajimmi.veldash/files/maps/
```
*(On Android 12 and below, `/sdcard/veldash/` and `/sdcard/Download/` are also scanned).*

### 3. Loading BRouter Offline Routing Segments
Download 5° × 5° routing segment files (`.rd5`) from [BRouter Segments](https://brouter.de/brouter/segments4/) and copy them to:
```
/sdcard/Android/data/com.papajimmi.veldash/files/brouter/segments/
```

For complete instructions on generating custom `.mbtiles` for any city in the world and configuring online API keys, read [docs/MAPS_AND_ROUTING.md](docs/MAPS_AND_ROUTING.md).

---

## Search, Favorites & System Integration

- **Search**: Tap the destination pill or press the search button. Works with text addresses (online via Mapbox or Nominatim) or direct geographic coordinates (`lat, lon`, works 100% offline).
- **Home & Work Shortcuts**: Long-press or tap Edit to assign custom coordinates, names, and visual badges.
- **Saved Places**: Categorize saved destinations with custom Gotham-themed icons (Batcave, Wayne Enterprises, Catwoman, Joker, Riddler) stored in `files/favorites.json`.
- **System Intent Handling**: Other applications can trigger navigation using standard `geo:` URIs:
  ```bash
  adb shell am start -a android.intent.action.VIEW -d "geo:40.6401,22.9444"
  adb shell am start -a android.intent.action.VIEW -d "geo:0,0?q=Syntagma+Square"
  ```

---

## Testing on Android Automotive (AAOS) Emulator

The Android Automotive emulator executes its user interface under user ID 10. To sideload and test without root restrictions:

```bash
# Build and install the benchmark/debuggable release build
./gradlew :app:assembleBench
adb install -r -g app/build/outputs/apk/bench/app-x86_64-bench.apk

# Push map and routing files to user 10 storage
adb push north-greece.mbtiles segments/*.rd5 /data/local/tmp/veldash/
adb shell run-as com.papajimmi.veldash --user 10 sh -c '\
  D=/data/user/10/com.papajimmi.veldash/files; \
  mkdir -p $D/maps $D/brouter/segments; \
  cp /data/local/tmp/veldash/*.mbtiles $D/maps/; \
  cp /data/local/tmp/veldash/*.rd5 $D/brouter/segments/'

# Enable mock location and launch
adb shell settings put secure --user 10 location_mode 3
adb shell am start --user 10 -n com.papajimmi.veldash/com.veldash.MainActivity
```

To simulate GPS vehicle motion along a route, use the included emulator feeder scripts:
```bash
node tools/route-to-fixes.js route.json > fixes.txt
node tools/emu-drive.js fixes.txt
```

---

## Technical Documentation Index

- [Architecture & Low-Memory Design](docs/ARCHITECTURE.md)
- [Map Generation & BRouter Setup Guide](docs/MAPS_AND_ROUTING.md)
- [Legal Notices, Fair Use & Asset Provenance](LEGAL.md)
- [Source Code License (MIT)](LICENSE)

---

## Credits & Attribution

- **3D Vehicle Model**: *"Batmobile Jet Car (1989)"* by [kulonee](https://sketchfab.com/ynesolefru), licensed under [CC BY 4.0](http://creativecommons.org/licenses/by/4.0/).
- **Map Rendering**: [MapLibre Native Android](https://github.com/maplibre/maplibre-native) (BSD 2-Clause).
- **Offline Routing**: [BRouter](https://github.com/abrensch/brouter) (MIT).
- **Map Cartography**: Data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL).
- **Tile Pipeline**: [Planetiler](https://github.com/onthegomap/planetiler) (Apache 2.0).

---

## License

The source code and software architecture of Veldash are released under the [MIT License](LICENSE).
Third-party character names, trademarks, vehicle designs, and associated intellectual property belong to **DC Comics, Inc.** and **Warner Bros. Discovery**. See [LEGAL.md](LEGAL.md) for full trademark disclaimers and non-commercial fan usage terms.
