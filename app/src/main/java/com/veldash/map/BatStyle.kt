package com.veldash.map

import android.content.Context
import org.json.JSONObject

/**
 * Builds the MapLibre style JSON for one .mbtiles file. Generated in code so there is no
 * style asset to parse and the tile path can be injected directly.
 *
 * Look: Google-Maps-like hierarchy (roads get lighter and wider with importance, main roads
 * have a dark casing, water and greenery give the ground depth) on a near-black Batman base
 * with the yellow reserved for motorways and the route.
 *
 * Budget rules for a 1 GB head unit:
 *  - MapLibre builds one vertex bucket per layer per tile: two road fill layers plus one casing
 *    layer, not one layer per road class.
 *  - No fill-extrusion, no hillshade, no sprites, no patterns.
 *  - Zoom-gated: minor roads z12+, casings z12+, buildings z16+ (only at navigation zoom).
 *  - Symbol (text) layers are only emitted when glyph PBFs are bundled under assets/fonts/.
 *
 * Expects the OpenMapTiles vector schema. Raster .mbtiles are shown as-is.
 */
object BatStyle {

    private const val GLYPHS_ASSET_DIR = "fonts"
    private const val FONT = "Open Sans Regular"

    // ---- palette ----
    private const val C_BG = "#0B0C10"
    private const val C_WATER = "#071120"
    private const val C_WATERWAY = "#0E1C36"
    private const val C_WOOD = "#0D1710"
    private const val C_GRASS = "#101A12"
    private const val C_PARK = "#0F1C13"
    private const val C_BUILDING = "#17181C"
    private const val C_BOUNDARY = "#3A3D44"

    private const val C_CASING = "#000000"
    private const val C_ROAD_MINOR = "#2A2D33"
    private const val C_ROAD_MID = "#41454D"
    private const val C_ROAD_MAJOR = "#6B6F78"
    private const val C_ROAD_MOTORWAY = "#A08A2A"

    private const val C_LABEL_ROAD = "#C9CCD1"
    private const val C_LABEL_PLACE = "#FFFFFF"
    private const val C_HALO = "#0B0C10"

    /**
     * Building footprints only at navigation zoom. Measured cost ~4 MB native on a dense
     * centre; set false for the absolute minimum.
     */
    private const val SHOW_BUILDINGS = true

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

    /**
     * Main-road width by zoom and class. [extra] is added to every stop (casing = fill + 2 px).
     * Widths approximate the Google hierarchy: motorway > trunk/primary > secondary/tertiary.
     */
    private fun mainWidth(extra: Double): String {
        fun at(mw: Double, mj: Double, md: Double) =
            """["match", ["get", "class"], "motorway", ${mw + extra}, ["trunk", "primary"], ${mj + extra}, ${md + extra}]"""
        return """["interpolate", ["exponential", 1.5], ["zoom"],
                     5, ${at(1.0, 0.7, 0.4)},
                    12, ${at(3.0, 2.2, 1.4)},
                    15, ${at(8.0, 6.0, 4.0)},
                    18, ${at(24.0, 20.0, 15.0)}]"""
    }

    private fun vector(map: MapFile, withLabels: Boolean): String {
        val url = JSONObject.quote(map.sourceUrl)
        val glyphs = if (withLabels) {
            """"glyphs": "asset://$GLYPHS_ASSET_DIR/{fontstack}/{range}.pbf","""
        } else {
            ""
        }
        val labelLayers = if (withLabels) LABEL_LAYERS else ""
        val mainClasses = """["motorway", "trunk", "primary", "secondary", "tertiary"]"""

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

            { "id": "landcover", "type": "fill", "source": "osm", "source-layer": "landcover",
              "filter": ["match", ["get", "class"], ["wood", "grass", "farmland", "wetland"], true, false],
              "paint": { "fill-color": ["match", ["get", "class"], "wood", "$C_WOOD", "$C_GRASS"],
                         "fill-antialias": false } },

            { "id": "park", "type": "fill", "source": "osm", "source-layer": "park",
              "paint": { "fill-color": "$C_PARK", "fill-antialias": false } },

            { "id": "water", "type": "fill", "source": "osm", "source-layer": "water",
              "paint": { "fill-color": "$C_WATER", "fill-antialias": false } },

            { "id": "waterway", "type": "line", "source": "osm", "source-layer": "waterway",
              "minzoom": 9,
              "paint": { "line-color": "$C_WATERWAY",
                         "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 9, 0.5, 18, 5] } },

            $BUILDING_LAYER

            { "id": "road-minor", "type": "line", "source": "osm", "source-layer": "transportation",
              "minzoom": 12,
              "filter": ["match", ["get", "class"], ["minor", "service", "track", "living_street", "residential"], true, false],
              "layout": { "line-join": "round", "line-cap": "round" },
              "paint": { "line-color": "$C_ROAD_MINOR",
                         "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 12, 0.6, 15, 2.5, 18, 12] } },

            { "id": "road-main-casing", "type": "line", "source": "osm", "source-layer": "transportation",
              "minzoom": 12,
              "filter": ["match", ["get", "class"], $mainClasses, true, false],
              "layout": { "line-join": "round", "line-cap": "round" },
              "paint": { "line-color": "$C_CASING", "line-width": ${mainWidth(2.0)} } },

            { "id": "road-main", "type": "line", "source": "osm", "source-layer": "transportation",
              "minzoom": 4,
              "filter": ["match", ["get", "class"], $mainClasses, true, false],
              "layout": { "line-join": "round", "line-cap": "round",
                          "line-sort-key": ["match", ["get", "class"], "motorway", 3, ["trunk", "primary"], 2, 1] },
              "paint": { "line-color": ["match", ["get", "class"],
                                         "motorway", "$C_ROAD_MOTORWAY",
                                         ["trunk", "primary"], "$C_ROAD_MAJOR",
                                         "$C_ROAD_MID"],
                         "line-width": ${mainWidth(0.0)} } },

            { "id": "boundary", "type": "line", "source": "osm", "source-layer": "boundary",
              "filter": ["<=", ["get", "admin_level"], 2],
              "paint": { "line-color": "$C_BOUNDARY", "line-width": 1, "line-dasharray": [3, 2] } }
            $labelLayers
          ]
        }
        """.trimIndent()
    }

    private val BUILDING_LAYER: String = if (SHOW_BUILDINGS) {
        """
            { "id": "building", "type": "fill", "source": "osm", "source-layer": "building",
              "minzoom": 16,
              "paint": { "fill-color": "$C_BUILDING", "fill-antialias": false } },
        """
    } else {
        ""
    }

    /** Appended inside the layers array; leading comma is intentional. */
    private val LABEL_LAYERS = """
            ,
            { "id": "road-label", "type": "symbol", "source": "osm", "source-layer": "transportation_name",
              "minzoom": 13,
              "filter": ["match", ["get", "class"], ["motorway", "trunk", "primary", "secondary", "tertiary", "minor"], true, false],
              "layout": { "symbol-placement": "line", "text-field": ["get", "name"],
                          "text-font": ["$FONT"], "text-size": ["interpolate", ["linear"], ["zoom"], 13, 10, 17, 14],
                          "symbol-spacing": 350, "text-padding": 4, "text-max-angle": 30,
                          "text-rotation-alignment": "map", "text-pitch-alignment": "viewport" },
              "paint": { "text-color": "$C_LABEL_ROAD", "text-halo-color": "$C_HALO", "text-halo-width": 1.5 } },

            { "id": "place-label", "type": "symbol", "source": "osm", "source-layer": "place",
              "filter": ["match", ["get", "class"], ["city", "town", "village", "suburb", "neighbourhood"], true, false],
              "layout": { "text-field": ["get", "name"], "text-font": ["$FONT"],
                          "text-size": ["match", ["get", "class"], "city", 18, "town", 15, "village", 13, 12],
                          "text-max-width": 8, "text-padding": 8, "text-pitch-alignment": "viewport" },
              "paint": { "text-color": "$C_LABEL_PLACE", "text-halo-color": "$C_HALO", "text-halo-width": 2 } }
    """
}
