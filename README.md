# Veldash

Ultra-light offline/online navigation app for low-spec aftermarket Android head units
(1 GB RAM, Android 5.0+). Native Kotlin, classic XML Views, MapLibre Native, no Play Services.

## Build

```bash
./gradlew :app:assembleRelease
```

Optional: put `MAPBOX_TOKEN=pk.xxx` in `local.properties` for the Mapbox Directions API.
Without it the app falls back to OSRM.

## Map files

Veldash renders `.mbtiles` files in place, straight from the filesystem. Copy your files to:

```
/sdcard/Android/data/com.veldash/files/maps/
```

On Android 12 and below it also scans `/sdcard/veldash/` and `/sdcard/Download/`.

Vector tiles must use the OpenMapTiles schema (`format=pbf`). Raster `.mbtiles` (`png`/`jpg`)
are supported as a fallback.

## Routing

Long-press the map to set a destination. Back clears the route.

- **Online:** Mapbox Directions when `MAPBOX_TOKEN` is set, otherwise the OSRM URL from
  `app/build.gradle.kts` (the public demo server by default; run your own for production).
- **Offline:** embedded BRouter. Copy segment files (`*.rd5`, 5x5 degree tiles, from
  `https://brouter.de/brouter/segments4/`) to:

```
/sdcard/Android/data/com.veldash/files/brouter/segments/
```

For Greece that is `E20_N35.rd5` and `E25_N35.rd5`. The car profile and lookup table ship
inside the APK under `app/src/main/assets/brouter/` (`car-vario.brf` and `lookups.dat` from the
BRouter repository, `misc/profiles2/`). Without those two assets offline routing is disabled.

## Search and favorites

The "Where to?" bar (top left) opens a Google Maps style dropdown: Home and Work shortcuts,
saved places and recent destinations, filtered as you type. Press the search icon (or the
keyboard's search key) for online results, or type coordinates as `lat, lon` (works offline).
Tap any row to route to it.

- **Home / Work:** tap one while unset (or its Edit) to pick it: search, or use the current
  location. Long-press to remove it.
- **Saved places:** long-press a result to save it; long-press a saved place to remove it.
  When a destination is set, a row saves it.
- **Recent history:** every destination you pick. The first four show, "More from recent
  history" shows the rest; long-press one to save it or remove it from history.

While a route is active the trip card replaces "Where to?" at the bottom left: time left,
distance and arrival time. Its ✕ ends the route (so does Back).

## Batmobile and pins

The batmobile is a real 3D model, pre-rendered once into 24 small WebP frames in
`app/src/main/assets/car/` (~620 KB). The head unit runs no 3D engine: each frame the app picks the
render that matches the camera tilt and the car's heading relative to the camera
(`CarSprites`), so the car truly tilts during the 2D/3D switch and turns into corners. The
neighbouring render is cross-faded on top by proximity, so the car morphs between frames
instead of popping, and the whole car scales with zoom relative to the navigation zoom so a
zoomed-out map is not dominated by it. The camera heading lags the car's by a short 0.35 s:
enough to show the flank in a bend, not enough to look like sliding.

To re-render (Blender 4.2+, runs headless, ~10 minutes on a CPU):

```bash
blender -b -P tools/render-car.py -- --glb ~/veldash-data/models/batmobile_jet_car_1989.glb --out app/src/main/assets/car --size 480 --samples 48
```

Add `--preview <dir>` to also write PNGs over the map colour, or `--only p55_y000` for one frame.
The model stays out of git (11 MB); keep it in `~/veldash-data/models/`.

The destination pin is the Batman symbol in `art/batman-symbol.png`, shipped trimmed and
downscaled as `app/src/main/res/drawable-nodpi/bat_logo.png`.

**Credits:** "Batmobile Jet Car (1989)" by [kulonee](https://sketchfab.com/ynesolefru),
[CC BY 4.0](http://creativecommons.org/licenses/by/4.0/), from
[Sketchfab](https://sketchfab.com/3d-models/batmobile-jet-car-1989-2e82d07ed190408e95cdb406c44aefec);
rendered to sprites. Batman and the Batmobile are trademarks of DC Comics: this build is for
personal use, not for store distribution.

- **Online:** Mapbox Geocoding when `MAPBOX_TOKEN` is set, otherwise Nominatim (OSM). Nominatim
  is queried only on an explicit search, never per keystroke, per its usage policy.
- **Offline:** favorites and coordinate input.

Saved places and recents live in `files/favorites.json` and `files/recents.json` inside the
app's private storage; Home and Work in its preferences.

Other apps can hand over a destination with a `geo:` intent, for example
`geo:37.98,23.72`, `geo:0,0?q=37.98,23.72(Label)` or `geo:0,0?q=Syntagma+Square`.

## Building a map file

`tools/build-map.sh` runs Planetiler (Java 21+) over an OpenStreetMap extract:

```bash
tools/build-map.sh ~/veldash-data/greece-latest.osm.pbf ~/veldash-data/north-greece.mbtiles
```

The default bounds cover northern Greece (Macedonia, Thrace, Epirus, Thessaly): 164 MB at
zoom 14 with only Greek and English names. Pass `""` as a third argument for the whole extract.
First run downloads ~1.2 GB of auxiliary data (oceans, Natural Earth) into `data/sources`.

## Memory tuning (measured)

Measured on an Android Automotive 13 emulator, 1080x600, dense Thessaloniki centre at zoom 16,
after panning around. Numbers are `dumpsys meminfo` PSS / private dirty.

| Configuration | Native heap | PSS | Private dirty |
|---|---|---|---|
| Tile cache on, prefetch 2, buildings | 70 MB | 128 MB | 86 MB |
| Tile cache off, prefetch 0, buildings | 28 MB | 82 MB | 42 MB |
| Tile cache off, prefetch 0, no buildings, 2D | 25 MB | 78 MB | 38 MB |
| Rich style (casings, landcover, buildings z16+), 2D | 36 MB | 83 MB | 51 MB |
| Rich style, 3D chase view (pitch 50), during a drive | 40-45 MB | 86-93 MB | 53-61 MB |

The tile cache is the dominant cost: it retains every tile ever shown. With local `.mbtiles`
a tile reloads in milliseconds, so the cache buys nothing and is disabled in `MapSetup`.
Of the 3D figures, live allocations are only ~31-33 MB; the rest is freed pages the native
allocator keeps after tile churn. Buildings (`BatStyle.SHOW_BUILDINGS`) cost ~4 MB and can be
switched off for the absolute minimum. About 35 MB of the PSS is shared system libraries and
file-backed code that the kernel can evict.

## Testing on the Automotive emulator

The AAOS emulator runs its UI as user 10, and `adb push` cannot reach that user's storage.
Use the `bench` build type (release + debuggable) and copy files through `run-as`:

```bash
./gradlew :app:assembleBench
adb install -r -g app/build/outputs/apk/bench/app-x86_64-bench.apk
adb push north-greece.mbtiles segments/*.rd5 /data/local/tmp/veldash/
adb shell run-as com.veldash --user 10 sh -c 'D=/data/user/10/com.veldash/files; mkdir -p $D/maps $D/brouter/segments; cp /data/local/tmp/veldash/*.mbtiles $D/maps/; cp /data/local/tmp/veldash/*.rd5 $D/brouter/segments/'
adb shell settings put secure --user 10 location_mode 3
adb shell am start --user 10 -n com.veldash/.MainActivity
```

The app scans its internal `files/maps` and `files/brouter/segments` as well as the external
folders, so this works without root.

Android Automotive blocks non-optimised activities once the vehicle "moves", and the emulator
derives vehicle speed from GPS replays. The manifest declares the activity distraction-optimised
and the app as a navigation app (`res/xml/automotive_app_desc.xml`), which is what real AAOS
builds require. The Google-built emulator image ignores these for sideloaded apps, so if the
"You can't use this feature while driving" screen appears, reboot the emulator (state resets
to parked) and keep replays short. Aftermarket Android head units have no such restriction. To replay a drive, `tools/route-to-fixes.js` turns an OSRM
response into one fix per second and `tools/emu-drive.js` replays them over a single emulator console connection (per-call `adb emu` sessions get refused after a few dozen).

## Map labels (glyphs)

MapLibre needs glyph PBFs to draw text. To enable road and place labels, add a
`Noto Sans Regular` glyph set to:

```
app/src/main/assets/fonts/Noto Sans Regular/0-255.pbf
app/src/main/assets/fonts/Noto Sans Regular/256-511.pbf
app/src/main/assets/fonts/Noto Sans Regular/768-1023.pbf   (Greek)
...
```

Prebuilt sets are available from the openmaptiles/fonts repository. Ship only the Unicode
ranges you need; each range file is roughly 20 to 60 KB. If the folder is absent the map
renders without labels and logs nothing.
