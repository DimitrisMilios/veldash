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
