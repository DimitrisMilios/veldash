#!/usr/bin/env bash
# Produces the offline data that ships inside the APK (app/src/main/assets, git-ignored):
#
#   maps/thessaloniki.mbtiles        vector tiles for Thessaloniki and Central Macedonia
#   brouter/segments/E20_N40.rd5     BRouter routing segment covering lon 20-25 / lat 40-45
#                                    (Thessaloniki, Chalkidiki, all of Macedonia and Thrace west of 25E)
#
# Usage:
#   tools/fetch-bundled-data.sh [bbox]        bbox = minLon,minLat,maxLon,maxLat
#
# Downloads (once, into ~/veldash-data): Planetiler (~90 MB), the Geofabrik Greece extract
# (~300 MB), the BRouter segment (~63 MB). Planetiler itself fetches ~1.2 GB of ocean /
# Natural Earth sources on first run. Needs Java 21+ (Android Studio's JBR is fine):
#   JAVA="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" tools/fetch-bundled-data.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ASSETS="$HERE/../app/src/main/assets"
DATA="${VELDASH_DATA:-$HOME/veldash-data}"
# Thessaloniki and its region: Pieria, Imathia, Pella, Kilkis, Chalkidiki, western Serres.
BBOX="${1-22.0,39.9,24.1,41.2}"
RD5=E20_N40

mkdir -p "$DATA" "$ASSETS/maps" "$ASSETS/brouter/segments"

fetch() { # url target
  if [ ! -s "$2" ]; then
    echo "Downloading $(basename "$2")"
    curl -fL --retry 3 -o "$2.part" "$1" && mv "$2.part" "$2"
  fi
}

fetch "https://github.com/onthegomap/planetiler/releases/download/v0.10.2/planetiler.jar" "$DATA/planetiler.jar"
fetch "https://download.geofabrik.de/europe/greece-latest.osm.pbf" "$DATA/greece-latest.osm.pbf"
fetch "https://brouter.de/brouter/segments4/$RD5.rd5" "$DATA/$RD5.rd5"

export PLANETILER_JAR="$DATA/planetiler.jar"
"$HERE/build-map.sh" "$DATA/greece-latest.osm.pbf" "$DATA/thessaloniki.mbtiles" "$BBOX"

# Planetiler names every archive "OpenMapTiles"; the app shows this name in the status card and picker.
sqlite3 "$DATA/thessaloniki.mbtiles" "update metadata set value='Thessaloniki' where name='name';"
cp "$DATA/thessaloniki.mbtiles" "$ASSETS/maps/thessaloniki.mbtiles"
cp "$DATA/$RD5.rd5" "$ASSETS/brouter/segments/$RD5.rd5"
echo
echo "Bundled into $ASSETS:"
ls -lh "$ASSETS/maps/thessaloniki.mbtiles" "$ASSETS/brouter/segments/$RD5.rd5"
