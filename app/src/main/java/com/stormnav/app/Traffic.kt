package com.stormnav.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Live traffic from TomTom's free raster tiles: coloured lines on roads (flow) plus crash,
 * closure and roadwork icons (incidents). Needs a free key from developer.tomtom.com.
 * Everything here is pure so it can be unit-tested; MainActivity puts the tiles on the map.
 */
object Traffic {
    private const val BASE = "https://api.tomtom.com/traffic/map/4/tile"

    /** Below this zoom traffic is unreadable anyway; skipping it saves the shared free allowance. */
    const val MIN_ZOOM = 9f

    /** TomTom renders up to 22; past 18 the map just enlarges the 18 tiles (no extra requests). */
    const val MAX_ZOOM = 18f

    /** How often to fetch fresh traffic. TomTom updates about every minute; 5 min keeps the quota safe. */
    const val REFRESH_MS = 5 * 60_000L

    /** After a refusal (bad key or daily limit), wait this long before trying again. */
    const val RETRY_MS = 30 * 60_000L

    enum class Status { NO_KEY, CHECKING, OK, REJECTED, BUSY, OFFLINE }

    /** Keys are letters and digits; people often paste them with spaces, quotes or "key=". */
    fun cleanKey(raw: String?): String {
        val s = raw.orEmpty().trim().removePrefix("key=").trim('"', '\'', ' ', '\n', '\t')
        return s.filter { it.isLetterOrDigit() }
    }

    fun looksValid(key: String): Boolean = key.length in 20..64 && key.all { it.isLetterOrDigit() }

    // [stamp] is unused in the URL (TomTom rejects unknown values, e.g. "t" is a traffic-model id);
    // fresh tiles come from a new map source plus TomTom's own short cache times.
    /** "relative0": green / yellow / orange / red against the road's normal speed, closures in grey. */
    fun flowUrl(key: String, stamp: Long): String =
        "$BASE/flow/relative0/{z}/{x}/{y}.png?key=$key&tileSize=256"

    /** "s3": incident icons plus coloured lines along the affected stretch. */
    fun incidentsUrl(key: String, stamp: Long): String =
        "$BASE/incidents/s3/{z}/{x}/{y}.png?key=$key"

    /** One real tile at the user's spot, to learn whether the key works before drawing anything. */
    fun probeUrl(key: String, lat: Double, lon: Double, zoom: Int = 12): String {
        val (x, y) = tileXY(lat, lon, zoom)
        return "$BASE/flow/relative0/$zoom/$x/$y.png?key=$key&tileSize=256"
    }

    /** Standard Web-Mercator (slippy map) tile containing a point. */
    fun tileXY(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val n = 1 shl zoom
        val la = lat.coerceIn(-85.0511, 85.0511)
        val x = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val r = la * PI / 180.0
        val y = floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        return x to y
    }

    /** What an HTTP answer to the probe means for the layer. */
    fun statusFor(httpCode: Int): Status = when (httpCode) {
        200, 204 -> Status.OK
        400, 401, 403 -> Status.REJECTED // bad key, key not enabled for traffic, or today's free tiles used up
        429 -> Status.BUSY               // too many requests right now
        in 500..599 -> Status.BUSY
        else -> Status.OFFLINE
    }

    fun describe(s: Status): String = when (s) {
        Status.NO_KEY -> "Live traffic needs a free TomTom key (developer.tomtom.com, no credit card)."
        Status.CHECKING -> "Checking TomTom traffic…"
        Status.OK -> "Live traffic from TomTom: green flowing, yellow/orange slow, red jammed, grey closed. Icons mark crashes, closures and roadwork. Updates every 5 minutes."
        Status.REJECTED -> "TomTom refused the traffic request: the key is wrong, or today's free allowance is used up. Traffic is hidden and will be tried again in 30 minutes."
        Status.BUSY -> "TomTom traffic is busy right now. Trying again shortly."
        Status.OFFLINE -> "Can't reach TomTom right now. Traffic will come back when the connection does."
    }
}

/** One TomTom incident that lies on the road ahead of you. */
data class RouteIncident(
    val id: String, val category: Int, val magnitude: Int, val description: String,
    val road: String, val delayS: Double, val aheadM: Double, val startAlongM: Double = 0.0
)

/**
 * TomTom Incident Details: crashes, closures, jams and roadwork, matched against the route ahead.
 * A request covers a box around the next stretch of route; everything else is local maths.
 */
object TrafficIncidents {
    /** Look this far ahead each check (TomTom limits one request to a 10,000 km² box). */
    const val AHEAD_M = 40_000.0

    /** How often to check while driving. A 40-minute trip uses about 10 requests. */
    const val CHECK_MS = 4 * 60_000L

    /** An incident counts as "on your route" when its line runs within this distance of it. */
    const val ON_ROUTE_M = 35.0

    // TomTom iconCategory values
    const val ACCIDENT = 1; const val FOG = 2; const val DANGEROUS = 3; const val RAIN = 4; const val ICE = 5
    const val JAM = 6; const val LANE_CLOSED = 7; const val ROAD_CLOSED = 8; const val ROADWORKS = 9
    const val WIND = 10; const val FLOODING = 11; const val BROKEN_DOWN = 14

    private const val FIELDS = "{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay," +
        "events{description,code},from,to,length,delay,roadNumbers}}}"

    fun url(key: String, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): String {
        val bbox = String.format(java.util.Locale.US, "%.5f,%.5f,%.5f,%.5f", minLon, minLat, maxLon, maxLat)
        return "https://api.tomtom.com/traffic/services/5/incidentDetails?key=$key&bbox=$bbox" +
            "&fields=" + java.net.URLEncoder.encode(FIELDS, "UTF-8") + "&language=en-US&timeValidityFilter=present"
    }

    /** Box around the route from [fromM] to [fromM]+[AHEAD_M], padded ~500 m. Null if nothing is left. */
    fun boxAhead(route: List<GeoPoint>, cum: DoubleArray, fromM: Double): DoubleArray? {
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0; var n = 0
        for (i in route.indices) {
            if (cum[i] < fromM - 200 || cum[i] > fromM + AHEAD_M) continue
            val p = route[i]; n++
            minLat = minOf(minLat, p.lat); maxLat = maxOf(maxLat, p.lat); minLon = minOf(minLon, p.lon); maxLon = maxOf(maxLon, p.lon)
        }
        if (n == 0) return null
        val pad = 0.005
        return doubleArrayOf(minLat - pad, minLon - pad, maxLat + pad, maxLon + pad)
    }

    /** Incidents from a TomTom response that sit on the route ahead, travelling your way, nearest first. */
    fun onRoute(json: String, route: List<GeoPoint>, cum: DoubleArray, progressM: Double): List<RouteIncident> {
        val list = org.json.JSONObject(json).optJSONArray("incidents") ?: return emptyList()
        val out = mutableListOf<RouteIncident>()
        for (i in 0 until list.length()) {
            val f = list.optJSONObject(i) ?: continue
            val props = f.optJSONObject("properties") ?: continue
            val pts = geometryPoints(f.optJSONObject("geometry")) ?: continue
            if (pts.isEmpty()) continue
            // Check a handful of points along the incident against the route.
            val sample = if (pts.size <= 8) pts else (0 until 8).map { pts[it * (pts.size - 1) / 7] }
            val hits = sample.map { StormPlanner.nearestOnRoute(route, cum, it) }.filter { it.first <= ON_ROUTE_M }
            if (hits.isEmpty() || hits.size * 2 < sample.size) continue
            // Lines must run the same way you drive; the other side of a divided road doesn't count.
            if (sample.size > 1 && hits.size > 1 && hits.last().second < hits.first().second - 20) continue
            val startAlong = hits.minOf { it.second }
            val endAlong = hits.maxOf { it.second }
            if (endAlong < progressM) continue
            val ev = props.optJSONArray("events")?.optJSONObject(0)
            val roads = props.optJSONArray("roadNumbers")
            out += RouteIncident(
                id = props.optString("id", "$i"),
                category = props.optInt("iconCategory", 0),
                magnitude = props.optInt("magnitudeOfDelay", 0),
                description = ev?.optString("description").orEmpty(),
                road = roads?.optString(0).orEmpty(),
                delayS = props.optDouble("delay", 0.0).let { if (it.isNaN()) 0.0 else it },
                aheadM = (startAlong - progressM).coerceAtLeast(0.0),
                startAlongM = startAlong
            )
        }
        return out.sortedBy { it.aheadM }
    }

    private fun geometryPoints(g: org.json.JSONObject?): List<GeoPoint>? {
        g ?: return null
        val c = g.optJSONArray("coordinates") ?: return null
        return when (g.optString("type")) {
            "Point" -> listOf(GeoPoint(c.getDouble(1), c.getDouble(0)))
            "LineString" -> (0 until c.length()).map { c.getJSONArray(it).let { p -> GeoPoint(p.getDouble(1), p.getDouble(0)) } }
            else -> null
        }
    }

    /** Extra minutes from the incidents still ahead (jams carry TomTom's delay estimate). */
    fun delayAhead(incidents: List<RouteIncident>, progressM: Double): Double =
        incidents.filter { it.startAlongM > progressM }.sumOf { it.delayS }

    /** Worth a spoken warning? Crashes, closures, stalled cars, flooding, and only serious jams or roadwork. */
    fun worthSaying(x: RouteIncident): Boolean = when (x.category) {
        ACCIDENT, ROAD_CLOSED, LANE_CLOSED, BROKEN_DOWN, FLOODING -> true
        JAM -> x.magnitude >= 3 || x.delayS >= 300
        ROADWORKS -> x.magnitude >= 2 || x.delayS >= 180
        else -> false
    }

    /** Short spoken/written warning, e.g. "Crash ahead in 2 miles on I-80. About 6 minutes of delay." */
    fun message(x: RouteIncident, units: UnitSystem): String {
        val what = when (x.category) {
            ACCIDENT -> "Crash"; ROAD_CLOSED -> "Road closed"; LANE_CLOSED -> "Lane closed"
            BROKEN_DOWN -> "Stalled vehicle"; FLOODING -> "Flooding"; JAM -> "Heavy traffic"
            ROADWORKS -> "Roadwork"; else -> "Traffic incident"
        }
        val where = if (x.aheadM < 300) " just ahead" else " ahead in ${Units.spokenDistance(x.aheadM, units)}"
        val road = x.road.takeIf { it.isNotBlank() }?.let { " on $it" } ?: ""
        val mins = (x.delayS / 60).toInt()
        val delay = when {
            x.category == ROAD_CLOSED -> ". Watch for a detour."
            mins >= 2 -> ". About $mins minutes of delay."
            else -> "."
        }
        return "$what$where$road$delay"
    }
}

/**
 * TomTom requests other than map tiles come out of a small shared daily allowance. When TomTom
 * says no (key refused or allowance used up) StormNav quietly stops asking for a while and uses
 * the free servers instead. No error is shown.
 */
object TomTomGate {
    @Volatile var restUntil = 0L
    fun open(now: Long = System.currentTimeMillis()) = now >= restUntil
    /** Call with the HTTP code of any non-tile TomTom request. */
    fun record(httpCode: Int, now: Long = System.currentTimeMillis()) {
        when (httpCode) {
            401, 403, 429 -> restUntil = now + Traffic.RETRY_MS
            in 500..599 -> restUntil = now + 2 * 60_000L
        }
    }
}
