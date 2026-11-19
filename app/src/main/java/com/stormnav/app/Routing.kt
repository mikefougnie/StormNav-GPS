package com.stormnav.app

import org.json.JSONArray
import org.json.JSONObject

/*
 * Routing providers, tried in order:
 *  1. FOSSGIS OSRM (routing.openstreetmap.de): free community server behind openstreetmap.org's
 *     directions; no key; at most one request per second.
 *  2. openrouteservice: free plan (2,000 directions/day, 40/min) with an API key; used only when
 *     a key is configured and FOSSGIS fails.
 *  3. OSRM demo server, last resort.
 * Everything is parsed into the OSRM-style shape the rest of the app already uses.
 */

/** One maneuver in OSRM terms (type + modifier), so the app's instruction text works for every provider. */
data class ParsedStep(val lon: Double, val lat: Double, val type: String, val modifier: String, val name: String, val ref: String, val distanceM: Double)

/**
 * [durationS] is the drive time without traffic; [trafficDelayS] is extra time from current
 * traffic (only TomTom knows it; the free servers report 0).
 */
data class ParsedRoute(val coords: List<DoubleArray>, val steps: List<ParsedStep>, val durationS: Double, val distanceM: Double, val trafficDelayS: Double = 0.0)

object RoutingProviders {
    const val FOSSGIS = "https://routing.openstreetmap.de/routed-car/route/v1/driving/"
    const val OSRM_DEMO = "https://router.project-osrm.org/route/v1/driving/"
    const val ORS = "https://api.openrouteservice.org/v2/directions/driving-car/geojson"

    /** OSRM URL for [points] (lon,lat) with full geometry and turn-by-turn steps. */
    fun osrmUrl(base: String, points: List<GeoPoint>, alternatives: Boolean): String {
        val path = points.joinToString(";") { String.format(java.util.Locale.US, "%.6f,%.6f", it.lon, it.lat) }
        val alt = if (alternatives && points.size == 2) "3" else "false"
        return "$base$path?overview=full&geometries=geojson&steps=true&alternatives=$alt"
    }

    /** OSRM response (any OSRM server). Waypoint "arrive"/"depart" between legs are dropped. */
    fun parseOsrm(json: String): List<ParsedRoute> {
        val root = JSONObject(json)
        if (root.optString("code", "Ok") != "Ok") return emptyList()
        val routes = root.optJSONArray("routes") ?: return emptyList()
        return (0 until routes.length()).map { ri ->
            val r = routes.getJSONObject(ri)
            val g = r.getJSONObject("geometry").getJSONArray("coordinates")
            val coords = (0 until g.length()).map { i -> g.getJSONArray(i).let { doubleArrayOf(it.getDouble(0), it.getDouble(1)) } }
            val steps = mutableListOf<ParsedStep>()
            val legs = r.getJSONArray("legs")
            for (li in 0 until legs.length()) {
                val raw = legs.getJSONObject(li).getJSONArray("steps")
                for (i in 0 until raw.length()) {
                    val st = raw.getJSONObject(i)
                    val m = st.getJSONObject("maneuver")
                    val type = m.optString("type", "continue")
                    if ((type == "arrive" && li < legs.length() - 1) || (type == "depart" && li > 0)) continue
                    val loc = m.getJSONArray("location")
                    steps += ParsedStep(loc.getDouble(0), loc.getDouble(1), type, m.optString("modifier", ""),
                        st.optString("name"), st.optString("ref"), st.optDouble("distance", 0.0))
                }
            }
            ParsedRoute(coords, steps, r.optDouble("duration", 0.0), r.optDouble("distance", 0.0))
        }
    }

    /** openrouteservice POST body: waypoints, turn instructions, alternatives for A→B. */
    fun orsBody(points: List<GeoPoint>, alternatives: Boolean): String {
        val o = JSONObject()
        o.put("coordinates", JSONArray().apply { points.forEach { put(JSONArray().put(it.lon).put(it.lat)) } })
        o.put("instructions", true)
        o.put("units", "m")
        if (alternatives && points.size == 2) o.put("alternative_routes", JSONObject().put("target_count", 3).put("share_factor", 0.6).put("weight_factor", 1.6))
        return o.toString()
    }

    /** openrouteservice step type → OSRM (type, modifier). */
    fun orsManeuver(type: Int): Pair<String, String> = when (type) {
        0 -> "turn" to "left"; 1 -> "turn" to "right"
        2 -> "turn" to "sharp left"; 3 -> "turn" to "sharp right"
        4 -> "turn" to "slight left"; 5 -> "turn" to "slight right"
        6 -> "continue" to "straight"
        7 -> "roundabout" to "right"; 8 -> "continue" to "straight"
        9 -> "turn" to "uturn"; 10 -> "arrive" to ""; 11 -> "depart" to ""
        12 -> "fork" to "slight left"; 13 -> "fork" to "slight right"
        else -> "continue" to ""
    }

    /** openrouteservice GeoJSON response. Steps between segments (waypoints) are merged like OSRM. */
    fun parseOrs(json: String): List<ParsedRoute> {
        val feats = JSONObject(json).optJSONArray("features") ?: return emptyList()
        return (0 until feats.length()).map { fi ->
            val f = feats.getJSONObject(fi)
            val g = f.getJSONObject("geometry").getJSONArray("coordinates")
            val coords = (0 until g.length()).map { i -> g.getJSONArray(i).let { doubleArrayOf(it.getDouble(0), it.getDouble(1)) } }
            val props = f.getJSONObject("properties")
            val segs = props.optJSONArray("segments") ?: JSONArray()
            val steps = mutableListOf<ParsedStep>()
            for (si in 0 until segs.length()) {
                val raw = segs.getJSONObject(si).optJSONArray("steps") ?: continue
                for (i in 0 until raw.length()) {
                    val st = raw.getJSONObject(i)
                    val (type, mod) = orsManeuver(st.optInt("type", 6))
                    if ((type == "arrive" && si < segs.length() - 1) || (type == "depart" && si > 0)) continue
                    val wp = st.optJSONArray("way_points")
                    val idx = (wp?.optInt(0, 0) ?: 0).coerceIn(0, coords.size - 1)
                    val name = st.optString("name").let { if (it == "-") "" else it }
                    steps += ParsedStep(coords[idx][0], coords[idx][1], type, mod, name, "", st.optDouble("distance", 0.0))
                }
            }
            val sum = props.optJSONObject("summary") ?: JSONObject()
            ParsedRoute(coords, steps, sum.optDouble("duration", 0.0), sum.optDouble("distance", 0.0))
        }
    }

    // ---------------------------------------------------------------- TomTom (first route of a trip only)

    /** TomTom routing with live traffic, turn instructions and the no-traffic time for comparison. */
    fun tomtomUrl(key: String, points: List<GeoPoint>): String {
        val path = points.joinToString(":") { String.format(java.util.Locale.US, "%.6f,%.6f", it.lat, it.lon) }
        return "https://api.tomtom.com/routing/1/calculateRoute/$path/json?key=$key" +
            "&traffic=true&travelMode=car&routeType=fastest&instructionsType=coded&computeTravelTimeFor=all&language=en-US"
    }

    /** TomTom maneuver code → OSRM (type, modifier). */
    fun tomtomManeuver(code: String): Pair<String, String> = when (code) {
        "DEPART" -> "depart" to ""
        "ARRIVE", "ARRIVE_LEFT", "ARRIVE_RIGHT" -> "arrive" to ""
        "TURN_LEFT" -> "turn" to "left"; "TURN_RIGHT" -> "turn" to "right"
        "BEAR_LEFT" -> "turn" to "slight left"; "BEAR_RIGHT" -> "turn" to "slight right"
        "SHARP_LEFT" -> "turn" to "sharp left"; "SHARP_RIGHT" -> "turn" to "sharp right"
        "KEEP_LEFT" -> "fork" to "slight left"; "KEEP_RIGHT" -> "fork" to "slight right"
        "MAKE_UTURN", "TRY_MAKE_UTURN" -> "turn" to "uturn"
        "ENTER_MOTORWAY", "ENTER_FREEWAY", "ENTER_HIGHWAY", "ENTRANCE_RAMP" -> "on ramp" to ""
        "TAKE_EXIT", "MOTORWAY_EXIT_RIGHT" -> "off ramp" to "slight right"
        "MOTORWAY_EXIT_LEFT" -> "off ramp" to "slight left"
        "ROUNDABOUT_RIGHT", "ROUNDABOUT_LEFT", "ROUNDABOUT_CROSS", "ROUNDABOUT_BACK" -> "roundabout" to "right"
        "STRAIGHT", "FOLLOW", "SWITCH_MAIN_ROAD", "SWITCH_PARALLEL_ROAD" -> "continue" to "straight"
        else -> "continue" to ""
    }

    fun parseTomTom(json: String): List<ParsedRoute> {
        val routes = JSONObject(json).optJSONArray("routes") ?: return emptyList()
        return (0 until routes.length()).mapNotNull { ri ->
            val r = routes.getJSONObject(ri)
            val coords = mutableListOf<DoubleArray>()
            val legs = r.optJSONArray("legs") ?: return@mapNotNull null
            for (li in 0 until legs.length()) {
                val pts = legs.getJSONObject(li).optJSONArray("points") ?: continue
                for (i in 0 until pts.length()) {
                    val p = pts.getJSONObject(i)
                    val c = doubleArrayOf(p.getDouble("longitude"), p.getDouble("latitude"))
                    val last = coords.lastOrNull()
                    if (last == null || last[0] != c[0] || last[1] != c[1]) coords += c
                }
            }
            if (coords.size < 2) return@mapNotNull null
            val sum = r.optJSONObject("summary") ?: JSONObject()
            val total = sum.optDouble("lengthInMeters", 0.0)
            val withTraffic = sum.optDouble("travelTimeInSeconds", 0.0)
            val noTraffic = sum.optDouble("noTrafficTravelTimeInSeconds", Double.NaN)
            val delay = if (noTraffic.isNaN()) sum.optDouble("trafficDelayInSeconds", 0.0) else (withTraffic - noTraffic)
            val ins = r.optJSONObject("guidance")?.optJSONArray("instructions") ?: JSONArray()
            val steps = mutableListOf<ParsedStep>()
            for (i in 0 until ins.length()) {
                val it = ins.getJSONObject(i)
                val code = it.optString("maneuver")
                if (code.startsWith("WAYPOINT")) continue
                val (type, mod) = tomtomManeuver(code)
                val p = it.optJSONObject("point") ?: continue
                val off = it.optDouble("routeOffsetInMeters", 0.0)
                val nextOff = if (i + 1 < ins.length()) ins.getJSONObject(i + 1).optDouble("routeOffsetInMeters", total) else total
                val ref = it.optJSONArray("roadNumbers")?.optString(0).orEmpty()
                steps += ParsedStep(p.getDouble("longitude"), p.getDouble("latitude"), type, mod,
                    it.optString("street"), ref, (nextOff - off).coerceAtLeast(0.0))
            }
            ParsedRoute(coords, steps, if (noTraffic.isNaN()) withTraffic - delay else noTraffic, total, delay.coerceAtLeast(0.0))
        }
    }
}
