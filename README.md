# Veldash

Ultra-light offline/online navigation app for low-spec aftermarket Android head units
(1 GB RAM, Android 5.0+). Native Kotlin, classic XML Views, MapLibre Native, no Play Services.

## Build

```bash
./gradlew :app:assembleRelease
```

Optional: put `MAPBOX_TOKEN=pk.xxx` in `local.properties` for the Mapbox Directions API.
Without it the app falls back to OSRM.

The package name is `com.papajimmi.veldash` (the app registered in the Firebase project); the
code lives under `com.veldash`. Release builds are signed with the debug keystore so they
install straight from App Distribution.

## Sending a build to the car (Firebase App Distribution)

`app/google-services.json` is the Firebase config of the `veldash` project. The Gradle plugin
reads the app id from it and authenticates through the Firebase CLI login (`firebase login`),
or through `FIREBASE_TOKEN` / `GOOGLE_APPLICATION_CREDENTIALS` on a CI machine. Testers are
set in `app/build.gradle.kts`.

```bash
./gradlew :app:assembleRelease :app:appDistributionUploadRelease
```

That uploads the 64-bit (arm64-v8a) APK. For a 32-bit head unit add `-PabiToUpload=armeabi-v7a`
(that build also installs on 64-bit units). Testers get an email with the install link and can
download it on the head unit through the App Distribution web page or the App Tester app.

## Bundled offline data (Thessaloniki)

The APK ships a vector map of Thessaloniki and Central Macedonia and the BRouter routing
segment covering it (`E20_N40.rd5`), so the app navigates offline from the first start. On
first launch (and after an update with newer data) they are copied out of the APK into the
map and segment folders below; the bundled map opens by default until another file is picked.

The data files are git-ignored. Produce them once per machine before building:

```bash
JAVA="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" tools/fetch-bundled-data.sh
```

It downloads Planetiler, the Geofabrik Greece extract and the segment file into
`~/veldash-data`, builds `thessaloniki.mbtiles` for the default bounding box (pass your own as
`minLon,minLat,maxLon,maxLat` to widen it) and drops both files into `app/src/main/assets/`.
Without them the build still succeeds (with a warning) and the app falls back to files copied
in by hand.

## Map files

Veldash renders `.mbtiles` files in place, straight from the filesystem. Copy your files to:

```
/sdcard/Android/data/com.papajimmi.veldash/files/maps/
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
/sdcard/Android/data/com.papajimmi.veldash/files/brouter/segments/
```

Thessaloniki and the north are in `E20_N40.rd5` (bundled); the rest of Greece needs
`E20_N35.rd5`, `E25_N35.rd5` and `E25_N40.rd5`. The car profile and lookup table ship
inside the APK under `app/src/main/assets/brouter/` (`car-vario.brf` and `lookups.dat` from the
BRouter repository, `misc/profiles2/`). Without those two assets offline routing is disabled.

## Dashboard

An edge-to-edge map with flat ink-glass islands floating over it, laid out like Google Maps:
near-black panels at 90% with a hairline edge, Roboto Medium numerals under tiny tracked
labels, and bat-signal yellow kept for what matters (the route, the turn arrow, the time left,
the one call to action).

- **Destination pill** (top-left): `Where to?` when idle (tap it to open search: Home / Work,
  saved and recent places), or the bat, `TO` and the destination name with a round end-route
  cross while a destination is set.
- **Next-turn card** (under the pill, while navigating): yellow accent strip, the arrow, the
  maneuver label over the big distance, then the street name.
- **MAPS** (round button, top-right): loads or switches the `.mbtiles` file.
- **Trip island** (bottom-left): the speedometer badge, then `Routing…` / `No route` /
  `Arrived`, or the time left in yellow over `distance · arrival time`.
- **Round buttons** (bottom-right): `3D` / `2D` (glyph lit yellow while 3D) and the Batman
  logo, which recenters on the batmobile: quiet glass while the camera follows the car, a solid
  yellow disc once you have panned away, and tapping it while following resets the zoom and
  heading.

Every island is a plain XML shape drawable and every label is the system Roboto: no font
files, no image assets beyond the bat logo and the five place logos, no libraries, no gradients.

## Search and favorites

`SEARCH`, `SAVED` or the banner open a Google Maps style dropdown under the banner: Home and
Work shortcuts, saved places and recent destinations, filtered as you type. Press the search
icon (or the keyboard's search key) for online results, or type coordinates as `lat, lon`
(works offline). Tap any row to route to it.

- **Home / Work:** tap one while unset (or its Edit) to open its editor: type the address
  (or `lat, lon`), pick a logo, Save. The Search button in the editor instead picks the place
  from the list (results, saved, recent, or the current location). Long-press to remove it.
- **Saved places:** long-press a result to save it, with a name and a logo; long-press a saved
  place to edit (rename, move, change its logo) or remove it. When a destination is set, a
  row saves it.
- **Logos:** Home, Work and every saved place wear the bat logo by default, or one of five
  badges (Batcave, Wayne Enterprises, Catwoman, Joker, Riddler;
  `res/drawable-nodpi/logo_*.png`, sources in `art/`). The choice is stored with the place,
  and while driving to that place the destination pin on the map wears the same badge (on an
  ink disc, on the usual spike) instead of the bat logo, as does the destination pill next to
  the place name.
- **Recent history:** every destination you pick. The first four show, "More from recent
  history" shows the rest; long-press one to save it or remove it from history.

While a route is active the banner shows the destination and the readouts show ETA and
distance. `END ROUTE` in the banner ends the route (so does Back).

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
adb shell run-as com.papajimmi.veldash --user 10 sh -c 'D=/data/user/10/com.papajimmi.veldash/files; mkdir -p $D/maps $D/brouter/segments; cp /data/local/tmp/veldash/*.mbtiles $D/maps/; cp /data/local/tmp/veldash/*.rd5 $D/brouter/segments/'
adb shell settings put secure --user 10 location_mode 3
adb shell am start --user 10 -n com.papajimmi.veldash/com.veldash.MainActivity
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
