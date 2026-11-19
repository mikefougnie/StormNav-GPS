package com.stormnav.app

/**
 * Basemaps. SATELLITE matches the 0.5.0 HUD design: Esri World Imagery underneath,
 * OpenFreeMap (OpenStreetMap) road lines and labels on top, and the NOAA radar layer is
 * inserted between them (below the first "road" layer) so roads stay readable.
 */
enum class Basemap { SATELLITE, DARK }

object MapStyles {
    const val DARK_URL = "https://tiles.openfreemap.org/styles/dark"
    private const val ESRI_IMAGERY = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

    /** Satellite + roads style JSON. Layer ids start with "road" so the radar anchor logic finds them. */
    val satelliteJson: String = """
    {
      "version": 8,
      "name": "StormNav Satellite",
      "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
      "sources": {
        "imagery": {
          "type": "raster", "tiles": ["$ESRI_IMAGERY"], "tileSize": 256, "maxzoom": 19,
          "attribution": "Imagery © Esri, Maxar, Earthstar Geographics"
        },
        "omt": {
          "type": "vector", "url": "https://tiles.openfreemap.org/planet",
          "attribution": "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"
        }
      },
      "layers": [
        { "id": "imagery", "type": "raster", "source": "imagery",
          "paint": { "raster-saturation": 0.15, "raster-contrast": 0.08 } },
        { "id": "road-minor", "type": "line", "source": "omt", "source-layer": "transportation", "minzoom": 11,
          "filter": ["in", ["get", "class"], ["literal", ["minor", "service", "tertiary"]]],
          "paint": { "line-color": "rgba(255,255,255,0.55)", "line-width": ["interpolate", ["linear"], ["zoom"], 11, 0.4, 16, 2.5] } },
        { "id": "road-major-casing", "type": "line", "source": "omt", "source-layer": "transportation", "minzoom": 6,
          "filter": ["in", ["get", "class"], ["literal", ["primary", "secondary", "trunk", "motorway"]]],
          "layout": { "line-join": "round" },
          "paint": { "line-color": "rgba(40,40,40,0.55)", "line-width": ["interpolate", ["linear"], ["zoom"], 6, 1.5, 16, 9] } },
        { "id": "road-major", "type": "line", "source": "omt", "source-layer": "transportation", "minzoom": 6,
          "filter": ["in", ["get", "class"], ["literal", ["primary", "secondary", "trunk"]]],
          "layout": { "line-join": "round" },
          "paint": { "line-color": "rgba(225,225,225,0.85)", "line-width": ["interpolate", ["linear"], ["zoom"], 6, 0.8, 16, 6] } },
        { "id": "road-motorway", "type": "line", "source": "omt", "source-layer": "transportation", "minzoom": 5,
          "filter": ["==", ["get", "class"], "motorway"],
          "layout": { "line-join": "round" },
          "paint": { "line-color": "rgba(235,235,235,0.95)", "line-width": ["interpolate", ["linear"], ["zoom"], 5, 1, 16, 8] } },
        { "id": "road-label", "type": "symbol", "source": "omt", "source-layer": "transportation_name", "minzoom": 12,
          "layout": { "symbol-placement": "line", "text-field": ["coalesce", ["get", "name"], ["get", "ref"]],
            "text-font": ["Noto Sans Regular"], "text-size": 12 },
          "paint": { "text-color": "#ffffff", "text-halo-color": "rgba(0,0,0,0.85)", "text-halo-width": 1.6 } },
        { "id": "road-shield", "type": "symbol", "source": "omt", "source-layer": "transportation_name", "minzoom": 7,
          "filter": ["all", ["has", "ref"], ["in", ["get", "class"], ["literal", ["motorway", "trunk", "primary"]]]],
          "layout": { "symbol-placement": "line", "symbol-spacing": 400, "text-field": ["get", "ref"],
            "text-font": ["Noto Sans Bold"], "text-size": 12, "text-rotation-alignment": "viewport" },
          "paint": { "text-color": "#ffffff", "text-halo-color": "#1a4fb5", "text-halo-width": 3 } },
        { "id": "place-label", "type": "symbol", "source": "omt", "source-layer": "place", "minzoom": 7,
          "filter": ["in", ["get", "class"], ["literal", ["city", "town", "village"]]],
          "layout": { "text-field": ["get", "name"], "text-font": ["Noto Sans Bold"],
            "text-size": ["interpolate", ["linear"], ["zoom"], 7, 11, 14, 16] },
          "paint": { "text-color": "#ffffff", "text-halo-color": "rgba(0,0,0,0.9)", "text-halo-width": 1.8 } }
      ]
    }
    """.trimIndent()
}
