# Map Generation, Routing Segments & Asset Pipelines

This guide provides instructions for acquiring, generating, and loading map tiles, routing segments, and rendering assets into Veldash.

---

## 1. Vector Map Tiles (`.mbtiles`)

Veldash renders OpenMapTiles vector tiles directly from `.mbtiles` SQLite archives on the filesystem.

### Device Storage Locations

Copy `.mbtiles` files to your head unit using `adb push`, an SD card, or a USB thumb drive:

```
/sdcard/Android/data/com.papajimmi.veldash/files/maps/
```

*Note: On Android 12 and below, Veldash also automatically scans `/sdcard/veldash/` and `/sdcard/Download/`.*

### Building Custom Maps with Planetiler

Veldash includes an automated helper script (`tools/build-map.sh`) that uses [Planetiler](https://github.com/onthegomap/planetiler) (Java 21+) to convert OpenStreetMap `.osm.pbf` extracts into compact `.mbtiles`.

#### Step 1: Download an OSM Extract
Download an extract from [Geofabrik](https://download.geofabrik.de/) (e.g., Greece, Germany, California):

```bash
mkdir -p ~/veldash-data
curl -L -o ~/veldash-data/greece-latest.osm.pbf https://download.geofabrik.de/europe/greece-latest.osm.pbf
```

#### Step 2: Run the Builder Script

```bash
tools/build-map.sh ~/veldash-data/greece-latest.osm.pbf ~/veldash-data/custom-map.mbtiles
```

- By default, the script optimizes bounding boxes for northern Greece (zoom 0–14, bilingual Greek/English labels).
- To build the entire extract without bounding box clipping, pass empty quotes as the 3rd argument:
  ```bash
  tools/build-map.sh ~/veldash-data/greece-latest.osm.pbf ~/veldash-data/greece-all.mbtiles ""
  ```

---

## 2. Offline Routing Data (BRouter `.rd5` Segments)

Veldash uses [BRouter](https://brouter.de/) for turn-by-turn routing when offline or outside mobile coverage.

### Storage Location
Place routing segments into:

```
/sdcard/Android/data/com.papajimmi.veldash/files/brouter/segments/
```

### Segment Grid & Download
BRouter segments are divided into **5° × 5° geographic tiles** named by their bottom-left coordinate (e.g. `E20_N40.rd5` covers Longitude 20°E–25°E and Latitude 40°N–45°N).

Download segments for your target region from the official BRouter segment repository:
👉 [https://brouter.de/brouter/segments4/](https://brouter.de/brouter/segments4/)

#### Common European Segments (Greece & Balkans):
- `E20_N40.rd5`: Northern Greece, Albania, North Macedonia, Southern Bulgaria
- `E20_N35.rd5`: Peloponnese, Central Greece, Athens, Crete (West)
- `E25_N35.rd5`: Aegean Islands, Cyclades, Dodecanese, Crete (East)
- `E25_N40.rd5`: Thrace, North Aegean, Western Turkey

### Embedded Routing Assets
The car routing profile and lookup table are bundled inside the APK under:
- `app/src/main/assets/brouter/car-vario.brf`
- `app/src/main/assets/brouter/lookups.dat`

On first launch, Veldash automatically unpacks these into the application's internal files directory (`BundledData.kt`).

---

## 3. Font Glyphs (Map Labels)

MapLibre Native requires pre-rendered protocol buffer glyph sets (`.pbf`) to render text labels for roads, cities, and points of interest.

Store glyphs under:
```
app/src/main/assets/fonts/Noto Sans Regular/
├── 0-255.pbf          # Basic Latin & ASCII
├── 256-511.pbf        # Latin Extended
├── 768-1023.pbf       # Greek and Coptic
└── ...
```

Prebuilt glyph ranges can be sourced from the [openmaptiles/fonts](https://github.com/openmaptiles/fonts) repository. Only bundle the Unicode blocks relevant to your geographic region (each block is 20–60 KB).

---

## 4. Headless 3D Car Sprite Rendering

To render your own vehicle model into directional sprites, use `tools/render-car.py` with Blender 4.2+ (CPU or GPU):

```bash
blender -b -P tools/render-car.py -- \
  --glb ~/veldash-data/models/my_car_model.glb \
  --out app/src/main/assets/car \
  --size 480 \
  --samples 48
```

### Options:
- `--preview <dir>`: Renders test PNGs composited over the map background color.
- `--only <frame>`: Renders a single frame (e.g., `--only p55_y000`) for rapid iteration.
- Output: Produces 24 optimized WebP frames with transparent backgrounds and centered ground contact points.
