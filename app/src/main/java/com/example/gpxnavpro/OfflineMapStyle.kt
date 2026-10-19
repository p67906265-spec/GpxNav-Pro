package com.example.gpxnavpro

/** Stile vettoriale per le mappe offline (.pmtiles), condiviso da telefono e Android Auto. */
object OfflineMapStyle {

    private const val MAP_SOURCE_ID = "offline-map-source"

    fun json(mapUri: String): String {
        val escapedUri = mapUri
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        return """
            {
              "version": 8,
              "name": "GpxNav Pro Offline",
              "sources": {
                "$MAP_SOURCE_ID": {
                  "type": "vector",
                  "url": "$escapedUri",
                  "minzoom": 0,
                  "maxzoom": 15
                }
              },
              "layers": [
                {
                  "id": "background",
                  "type": "background",
                  "paint": { "background-color": "#F2EFE6" }
                },
                {
                  "id": "earth",
                  "type": "fill",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "earth",
                  "paint": { "fill-color": "#F2EFE6" }
                },
                {
                  "id": "landuse",
                  "type": "fill",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "landuse",
                  "paint": {
                    "fill-color": [
                      "match", ["get", "kind"],
                      "park", "#D6E8C8",
                      "forest", "#C7DFB5",
                      "wood", "#C7DFB5",
                      "grass", "#DDECCE",
                      "residential", "#EEEAE1",
                      "industrial", "#E3DED6",
                      "commercial", "#E8E1D8",
                      "#E9E6DC"
                    ],
                    "fill-opacity": 0.82
                  }
                },
                {
                  "id": "water",
                  "type": "fill",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "water",
                  "paint": {
                    "fill-color": [
                      "case",
                      ["==", ["get", "intermittent"], true], "#E4DED1",
                      ["==", ["get", "kind"], "playa"], "#E8E1D2",
                      ["==", ["get", "kind"], "other"], "#DED9CF",
                      ["==", ["get", "kind"], "ocean"], "#A8CBE0",
                      ["==", ["get", "kind"], "lake"], "#C1DCE8",
                      "#C9E0E8"
                    ],
                    "fill-opacity": 0.92
                  }
                },
                {
                  "id": "waterways",
                  "type": "line",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "physical_line",
                  "filter": ["==", ["get", "kind"], "waterway"],
                  "paint": {
                    "line-color": "#8ABFD3",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 7, 0.5, 15, 2.2]
                  }
                },
                {
                  "id": "buildings",
                  "type": "fill",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "buildings",
                  "minzoom": 13,
                  "paint": {
                    "fill-color": "#CDBEAD",
                    "fill-outline-color": "#AFA08F"
                  }
                },
                {
                  "id": "boundaries",
                  "type": "line",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "boundaries",
                  "paint": {
                    "line-color": "#A3978A",
                    "line-width": 0.8,
                    "line-dasharray": [3, 2]
                  }
                },
                {
                  "id": "roads-path",
                  "type": "line",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "roads",
                  "filter": ["==", ["get", "kind"], "path"],
                  "minzoom": 12,
                  "paint": {
                    "line-color": "#B9AA91",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.7, 16, 2.2],
                    "line-dasharray": [2, 1]
                  }
                },
                {
                  "id": "roads-minor-casing",
                  "type": "line",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "roads",
                  "filter": ["==", ["get", "kind"], "minor_road"],
                  "minzoom": 10,
                  "paint": {
                    "line-color": "#C5BBAE",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1.1, 16, 5.4]
                  }
                },
                {
                  "id": "roads-minor",
                  "type": "line",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "roads",
                  "filter": ["==", ["get", "kind"], "minor_road"],
                  "minzoom": 10,
                  "paint": {
                    "line-color": "#FAF8F2",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.7, 16, 4.0]
                  }
                },
                {
                  "id": "roads-major-casing",
                  "type": "line",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "roads",
                  "filter": ["in", ["get", "kind"], ["literal", ["major_road", "highway"]]],
                  "paint": {
                    "line-color": "#C88952",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 6, 1.8, 16, 9.0]
                  }
                },
                {
                  "id": "roads-major",
                  "type": "line",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "roads",
                  "filter": ["in", ["get", "kind"], ["literal", ["major_road", "highway"]]],
                  "paint": {
                    "line-color": "#F2C98D",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 6, 1.0, 16, 7.0]
                  }
                },
                {
                  "id": "roads-rail",
                  "type": "line",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "roads",
                  "filter": ["==", ["get", "kind"], "rail"],
                  "minzoom": 10,
                  "paint": {
                    "line-color": "#716E69",
                    "line-width": 1.2,
                    "line-dasharray": [2, 2]
                  }
                },
                {
                  "id": "transit",
                  "type": "line",
                  "source": "$MAP_SOURCE_ID",
                  "source-layer": "transit",
                  "minzoom": 10,
                  "paint": {
                    "line-color": "#77736E",
                    "line-width": 1.2,
                    "line-dasharray": [2, 2]
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
