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

"Where to?" opens the search panel. Type an address or place name and press Go, or type
coordinates as `lat, lon` (works offline). Tap a result to route to it. Long-press a result to
save it as a favorite, or long-press a favorite to remove it. When a destination is set, the
first row saves it as a favorite.

- **Online:** Mapbox Geocoding when `MAPBOX_TOKEN` is set, otherwise Nominatim (OSM). Nominatim
  is queried only on an explicit search, never per keystroke, per its usage policy.
- **Offline:** favorites and coordinate input.

Favorites live in `files/favorites.json` inside the app's private storage.

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
folders, so this works without root. To replay a drive, `tools/route-to-fixes.js` turns an OSRM
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
