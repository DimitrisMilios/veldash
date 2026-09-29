package com.veldash.map

import android.content.Context
import org.json.JSONObject

/**
 * Builds the MapLibre style JSON for one .mbtiles file. Generated in code so there is no
 * style asset to parse and the tile path can be injected directly.
 *
 * Design rules for a 1 GB head unit:
 *  - ~12 layers total. Every layer is a draw call per tile; fewer layers = less GPU and less RAM.
 *  - No fill-extrusion, no hillshade, no raster-dem, no sprites, no line casings, no patterns.
 *  - Roads are zoom-gated: minor roads only appear at z12+, buildings at z14+.
 *  - Symbol (text) layers are only emitted when glyph PBFs are bundled under assets/fonts/.
 *    Without glyphs MapLibre cannot rasterise labels and would log an error per tile.
 *
 * Expects the OpenMapTiles vector schema (layers: water, park, building, waterway,
 * boundary, transportation, transportation_name, place). Raster .mbtiles are shown as-is.
 */
object BatStyle {

    private const val GLYPHS_ASSET_DIR = "fonts"
    private const val FONT = "Noto Sans Regular"

    private const val C_BG = "#000000"
    private const val C_WATER = "#070B14"
    private const val C_WATERWAY = "#0F1B33"
    private const val C_PARK = "#0A120A"
    private const val C_BUILDING = "#161616"
    private const val C_BOUNDARY = "#3A3A3A"
    private const val C_ROAD_MINOR = "#3C3C3C"
    private const val C_ROAD_MID = "#6E6E6E"
    private const val C_ROAD_MAJOR = "#B8A500"
    private const val C_ROAD_MOTORWAY = "#FFE600"
    private const val C_LABEL = "#E0E0E0"
    private const val C_LABEL_PLACE = "#FFFFFF"
    private const val C_HALO = "#000000"

    /** True when assets/fonts/<FONT>/ exists in the APK. Checked once per process. */
    @Volatile
    private var glyphsAvailable: Boolean? = null

    fun hasGlyphs(context: Context): Boolean {
        glyphsAvailable?.let { return it }
        val found = try {
            context.assets.list("$GLYPHS_ASSET_DIR/$FONT")?.isNotEmpty() == true
        } catch (e: Exception) {
            false
        }
        glyphsAvailable = found
        return found
    }

    fun build(context: Context, map: MapFile): String {
        return if (map.isVector) vector(map, hasGlyphs(context)) else raster(map)
    }

    // ---------------------------------------------------------------------------------------------

    private fun raster(map: MapFile): String {
        val url = JSONObject.quote(map.sourceUrl)
        return """
        {
          "version": 8,
          "sources": {
            "tiles": { "type": "raster", "url": $url, "tileSize": 256 }
          },
          "layers": [
            { "id": "bg", "type": "background", "paint": { "background-color": "$C_BG" } },
            { "id": "raster", "type": "raster", "source": "tiles",
              "paint": { "raster-fade-duration": 0, "raster-resampling": "nearest" } }
          ]
        }
        """.trimIndent()
    }

    private fun vector(map: MapFile, withLabels: Boolean): String {
        val url = JSONObject.quote(map.sourceUrl)
        val glyphs = if (withLabels) {
            """"glyphs": "asset://$GLYPHS_ASSET_DIR/{fontstack}/{range}.pbf","""
        } else {
            ""
        }
        val labelLayers = if (withLabels) LABEL_LAYERS else ""

        return """
        {
          "version": 8,
          $glyphs
          "sources": {
            "osm": { "type": "vector", "url": $url }
          },
          "layers": [
            { "id": "bg", "type": "background",
              "paint": { "background-color": "$C_BG" } },

            { "id": "park", "type": "fill", "source": "osm", "source-layer": "park",
              "paint": { "fill-color": "$C_PARK", "fill-antialias": false } },

            { "id": "water", "type": "fill", "source": "osm", "source-layer": "water",
              "paint": { "fill-color": "$C_WATER", "fill-antialias": false } },

            { "id": "waterway", "type": "line", "source": "osm", "source-layer": "waterway",
              "minzoom": 9,
              "paint": { "line-color": "$C_WATERWAY",
                         "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 9, 0.5, 18, 4] } },

            { "id": "building", "type": "fill", "source": "osm", "source-layer": "building",
              "minzoom": 14,
              "paint": { "fill-color": "$C_BUILDING", "fill-antialias": false } },

            { "id": "road-minor", "type": "line", "source": "osm", "source-layer": "transportation",
              "minzoom": 12,
              "filter": ["match", ["get", "class"], ["minor", "service", "track", "living_street", "residential"], true, false],
              "layout": { "line-join": "round", "line-cap": "butt" },
              "paint": { "line-color": "$C_ROAD_MINOR",
                         "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 12, 0.6, 18, 8] } },

            { "id": "road-mid", "type": "line", "source": "osm", "source-layer": "transportation",
              "minzoom": 9,
              "filter": ["match", ["get", "class"], ["secondary", "tertiary"], true, false],
              "layout": { "line-join": "round", "line-cap": "butt" },
              "paint": { "line-color": "$C_ROAD_MID",
                         "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 9, 0.7, 18, 12] } },

            { "id": "road-major", "type": "line", "source": "osm", "source-layer": "transportation",
              "minzoom": 6,
              "filter": ["match", ["get", "class"], ["primary", "trunk"], true, false],
              "layout": { "line-join": "round", "line-cap": "butt" },
              "paint": { "line-color": "$C_ROAD_MAJOR",
                         "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 6, 0.8, 18, 16] } },

            { "id": "road-motorway", "type": "line", "source": "osm", "source-layer": "transportation",
              "minzoom": 4,
              "filter": ["==", ["get", "class"], "motorway"],
              "layout": { "line-join": "round", "line-cap": "butt" },
              "paint": { "line-color": "$C_ROAD_MOTORWAY",
                         "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 4, 0.8, 18, 18] } },

            { "id": "boundary", "type": "line", "source": "osm", "source-layer": "boundary",
              "filter": ["<=", ["get", "admin_level"], 2],
              "paint": { "line-color": "$C_BOUNDARY", "line-width": 1, "line-dasharray": [3, 2] } }
            $labelLayers
          ]
        }
        """.trimIndent()
    }

    /** Appended inside the layers array; leading comma is intentional. */
    private val LABEL_LAYERS = """
            ,
            { "id": "road-label", "type": "symbol", "source": "osm", "source-layer": "transportation_name",
              "minzoom": 13,
              "filter": ["match", ["get", "class"], ["motorway", "trunk", "primary", "secondary", "tertiary", "minor"], true, false],
              "layout": { "symbol-placement": "line", "text-field": ["get", "name"],
                          "text-font": ["$FONT"], "text-size": 12, "symbol-spacing": 400,
                          "text-padding": 4, "text-max-angle": 30 },
              "paint": { "text-color": "$C_LABEL", "text-halo-color": "$C_HALO", "text-halo-width": 1.5 } },

            { "id": "place-label", "type": "symbol", "source": "osm", "source-layer": "place",
              "filter": ["match", ["get", "class"], ["city", "town", "village"], true, false],
              "layout": { "text-field": ["get", "name"], "text-font": ["$FONT"],
                          "text-size": ["match", ["get", "class"], "city", 18, "town", 14, 12],
                          "text-max-width": 8, "text-padding": 8 },
              "paint": { "text-color": "$C_LABEL_PLACE", "text-halo-color": "$C_HALO", "text-halo-width": 2 } }
    """
}
