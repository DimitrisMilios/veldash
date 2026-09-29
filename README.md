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
