# Veldash Technical Architecture & Design Decisions

Veldash is engineered from the ground up to solve a specific, difficult problem: **providing modern offline and online turn-by-turn navigation on resource-constrained aftermarket Android head units**.

Most low-cost aftermarket car head units feature 1 GB of RAM and older ARM SoCs (Cortex-A7 / Cortex-A53) running Android 5.0 through Android 10. Mainstream navigation applications (Google Maps, Waze, HERE WeGo) routinely allocate 350 MB to 600+ MB of RAM, leading to aggressive Low-Memory Killer (LMK) eviction, UI freezes, or outright crashes while driving.

Veldash achieves full turn-by-turn navigation, 2D/3D smooth camera chasing, and offline routing within **~80 MB total PSS (~40 MB private dirty)**.

---

## 1. Zero-Framework Architectural Philosophy

To stay strictly within the memory budget, Veldash intentionally excludes common modern Android dependencies:

```
┌─────────────────────────────────────────────────────────────┐
│                       Veldash Core                          │
│                                                             │
│   Native android.app.Activity (No AppCompat / Jetpack X)    │
│   Classic XML Views + ViewBinding (Zero Runtime Overhead)   │
│   Direct OkHttp + Platform org.json (No Retrofit / Gson)    │
│   Conscrypt TLS 1.3 Backport (API 21-28 Support)            │
└──────────────────────────────┬──────────────────────────────┘
                               │
       ┌───────────────────────┴───────────────────────┐
       ▼                                               ▼
┌──────────────────────────────┐       ┌──────────────────────────────┐
│     MapLibre Native (C++)    │       │     Embedded BRouter Core    │
│  - Custom Style Engine       │       │  - Zero Graph Loading Bloat  │
│  - Tile Cache Stripped       │       │  - Native Java Bridge        │
│  - Direct In-Place .mbtiles  │       │  - Fast .rd5 Segments        │
└──────────────────────────────┘       └──────────────────────────────┘
```

1. **No `AppCompatActivity` or Material Components**:
   - `AppCompat` and `MaterialComponents` drag in hundreds of transitive classes, theme resolvers, and large attribute tables.
   - Veldash extends plain `android.app.Activity`. All UI elements are standard framework views (`FrameLayout`, `TextView`, `ImageView`) styled with zero-overhead XML shape drawables (`res/drawable/bg_*.xml`).
2. **No Jetpack Compose**:
   - Compose runtime and compiler codegen introduce substantial memory allocation spikes and steady-state heap overhead unsuited for 1 GB head units.
   - Veldash uses compile-time **ViewBinding** (`viewBinding = true`). ViewBinding generates direct class references with zero reflection and zero runtime overhead.
3. **No Heavy Serialization Libraries**:
   - Retrofit, Gson, Jackson, and Moshi are eliminated.
   - Network calls use raw `OkHttp` coupled with the Android platform's built-in `org.json` parser.
4. **No Core-Library Desugaring**:
   - Desugaring adds a ~200 KB runtime footprint. Veldash targets Java 17 bytecode but strictly limits runtime API calls to those available in `minSdk 21`.

---

## 2. Map Rendering & Tile Memory Management

Veldash uses **MapLibre Native Android SDK** (OpenGL ES 2.0). Under default configurations, vector map renderers maintain large in-memory tile caches that retain every tile rendered during a session.

### The Problem
Panning across a city on default settings causes the native tile cache to consume 100+ MB within minutes, triggering memory pressure on 1 GB devices.

### The Solution: Direct Filesystem In-Place Rendering
Because Veldash loads local `.mbtiles` files (SQLite databases containing pre-tiled vector data), reading a tile from local flash storage takes **less than 2–4 milliseconds**. 

In `MapSetup.kt`, the tile cache and prefetch are disabled:
- **Prefetch Count**: Set to `0`.
- **Tile Cache**: Evicted immediately.
- **Results**:
  - Live heap allocation drops from ~128 MB PSS down to **~78-83 MB PSS**.
  - No dropped frames or stuttering during driving maneuvers.

---

## 3. Embedded Offline Routing via BRouter

Many mobile offline routing engines (e.g., GraphHopper) build or load in-memory graph hierarchies that exceed 60–100 MB of heap for a single country.

### Why BRouter?
[BRouter](https://github.com/abrensch/brouter) is a mature, high-performance routing engine written in pure Java:
- Reads routing data directly from compact 5x5 degree segment files (`.rd5`).
- Uses minimal transient memory: graph nodes are paged dynamically from disk.
- DEX footprint after R8 shrinking is only **~85 KB**.

### The Bridge Architecture (`BRouterBridge.java`)
To prevent memory leaks and keep the main application decoupled:
- `btools.router.BRouterBridge` operates in BRouter's own package scope to access internal engine structs without reflection.
- Coordinates are passed as primitive arrays.
- Upon route completion, node caches and routing engine instances are immediately released for garbage collection.

---

## 4. Dual Routing Pipeline (`Router.kt`)

Veldash implements a tiered routing strategy that automatically handles online and offline connectivity transitions:

```
User Selects Destination
           │
           ▼
    Check Connectivity
      /          \
  (Online)     (Offline)
    /              \
Try Mapbox / OSRM   Embedded BRouter
    │                (Local .rd5)
(If fails)                 │
    └──────────────────────┘
           │
           ▼
Display Route & Navigation Steps
```

1. **Online Directions**:
   - Queries **Mapbox Directions API** if a valid token is present in `local.properties`.
   - Falls back to an **OSRM** server (`router.project-osrm.org` or a self-hosted instance).
2. **Offline Fallback**:
   - If network connectivity is unavailable or the online API returns an error or timeout, routing silently and seamlessly falls through to embedded **BRouter**.
   - Ensures turn-by-turn navigation never fails due to cellular blind spots.

---

## 5. Pseudo-3D Directional Sprite Engine (`CarSprites.kt`)

Rendering a high-polygon 3D car model in a real-time OpenGL scene graph alongside MapLibre on a low-end Mali or Adreno GPU introduces substantial thermal load and frame drops.

Veldash solves this with an innovative **pseudo-3D multi-angle sprite renderer**:

1. **Pre-Rendering**:
   - The 3D car model is pre-rendered headless using Blender into **24 lightweight WebP frames** (~620 KB total).
   - Frames cover pitch angles (0° to 55° in 5° steps for 2D/3D transition) and yaw angles (0° to 180° in 15° steps for chasing view).
2. **Real-Time Cross-Fading**:
   - `CarSprites.kt` calculates the vehicle's heading relative to the camera azimuth.
   - It selects the nearest two pre-rendered angles and applies dynamic alpha cross-fading between them.
   - The car morphs smoothly through turns without stepping or "popping".
3. **Camera Heading Lag**:
   - The camera heading trails the vehicle's heading by **0.35 seconds** (`SmoothMotion.kt`).
   - This subtle lag reveals the car's flank dynamically in bends, creating a convincing sense of automotive momentum and cinematic depth.

---

## 6. Android Automotive & Head Unit System Integration

1. **Distraction Optimization**:
   - Android Automotive OS (AAOS) restricts non-optimized applications when vehicle speed > 0.
   - `AndroidManifest.xml` and `res/xml/automotive_app_desc.xml` declare distraction-optimized navigation status.
2. **`geo:` Intent Protocol Support**:
   - Supports system-wide geo intents:
     - `geo:37.98,23.72`
     - `geo:0,0?q=37.98,23.72(Label)`
     - `geo:0,0?q=Syntagma+Square`
3. **Legacy TLS 1.3 Backport**:
   - Android 5.0 through 9.0 natively support only TLS 1.2.
   - Modern routing and tile servers require TLS 1.3.
   - Veldash installs **Conscrypt** as the primary security provider on API < 29, enabling TLS 1.3 without platform updates.
