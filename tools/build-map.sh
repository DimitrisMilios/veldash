#!/usr/bin/env bash
# Builds an OpenMapTiles-schema .mbtiles for Veldash with Planetiler.
#
# Usage:
#   tools/build-map.sh <osm.pbf> <output.mbtiles> [bbox]   bbox = minLon,minLat,maxLon,maxLat
#
# Defaults to northern Greece (Macedonia, Thrace, Epirus, Thessaly, Chalkidiki).
# Full Greece: pass an empty bbox ("") to skip --bounds.
#
# Kept deliberately light for a 1 GB head unit:
#   --languages=el,en   only two name fields per feature instead of ~60 -> smaller tiles,
#                       less memory per tile decode
#   --maxzoom=14        standard OpenMapTiles max; the app overzooms 2 levels on the GPU
#   --bounds            only the region you drive in
#
# Needs Java 21+. Android Studio ships one at:
#   /c/Program Files/Android/Android Studio/jbr/bin/java
set -euo pipefail

PBF="${1:?osm.pbf path}"
OUT="${2:?output .mbtiles path}"
BBOX="${3-20.5,38.8,26.7,41.8}"

JAVA="${JAVA:-java}"
if [ -x "/c/Program Files/Android/Android Studio/jbr/bin/java.exe" ]; then
  JAVA="/c/Program Files/Android/Android Studio/jbr/bin/java.exe"
fi

JAR="${PLANETILER_JAR:-$HOME/veldash-data/planetiler.jar}"

# Planetiler parses --output as a URI, so a Windows "C:/..." path reads as scheme "C".
# Run from the output directory and hand it relative paths instead.
OUT_DIR="$(mkdir -p "$(dirname "$OUT")" && cd "$(dirname "$OUT")" && pwd)"
OUT_NAME="$(basename "$OUT")"
PBF_ABS="$(cd "$(dirname "$PBF")" && pwd)/$(basename "$PBF")"
cd "$OUT_DIR"
# GNU realpath --relative-to is missing on macOS; python does the same everywhere.
PBF_REL="$(python3 -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$PBF_ABS" "$OUT_DIR")"

ARGS=(
  --osm-path="$PBF_REL"
  --output="$OUT_NAME"
  --force
  # Fetches ocean polygons, Natural Earth and lake centerlines (~1.2 GB) into ./data/sources
  # on first run; later runs reuse them.
  --download
  --languages=el,en
  --maxzoom=14
  --nodemap-type=sparsearray
  --storage=mmap
)
if [ -n "$BBOX" ]; then
  ARGS+=(--bounds="$BBOX")
fi

echo "Planetiler: $JAR"
echo "Input:      $PBF"
echo "Output:     $OUT"
echo "Bounds:     ${BBOX:-<whole file>}"
"$JAVA" -Xmx3g --enable-native-access=ALL-UNNAMED -jar "$JAR" "${ARGS[@]}"
ls -l "$OUT_NAME"
