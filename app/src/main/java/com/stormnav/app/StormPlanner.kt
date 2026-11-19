package com.stormnav.app

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Decisions behind the "storm ahead" popup, kept Android-free so they can be tested:
 * when to ask, how bad a route is, which detour is worth taking, where to wait, and
 * when the route counts as clear again.
 *
 * Everything here works from the radar as it is *now*: StormNav does not yet predict
 * where a storm will move, which is why waiting re-checks radar instead of guessing.
 */

/** A candidate route: points from the driver's position to the destination. */
data class RouteOption(val points: List<GeoPoint>, val durationS: Double, val distanceM: Double, val tag: String)

data class ScoredRoute(val option: RouteOption, val segments: List<StormSegment>) {
    val exposure: Double get() = StormPlanner.exposure(segments, 0.0)
    val worst: Int get() = segments.maxOfOrNull { it.peak } ?: RadarIntensity.NONE
}

data class DetourChoice(val route: ScoredRoute, val extraS: Double, val baseline: ScoredRoute)

/** A place to wait, [alongM] metres along the route from its start. */
data class WaitStop(val place: PlaceResult, val alongM: Double, val offRouteM: Double)

object StormPlanner {
    /** Popups only for storms reached within this long. */
    const val PROMPT_HORIZON_S = 60 * 60.0

    /** How much one kilometre of each intensity counts against a route. */
    fun weight(level: Int): Double = when (level) {
        RadarIntensity.MODERATE -> 1.0
        RadarIntensity.HEAVY -> 4.0
        RadarIntensity.SEVERE -> 12.0
        else -> 0.0
    }

    /** Weighted kilometres of storm still ahead of [fromM]. */
    fun exposure(segments: List<StormSegment>, fromM: Double): Double =
        RadarRouteAnalyzer.ahead(segments, fromM).sumOf { max(0.5, it.lengthM / 1000.0) * weight(it.peak) }

    /**
     * The storm worth a popup: the nearest stretch ahead that is severe on radar (or heavy
     * while the NWS has a severe-thunderstorm or tornado warning on the route), reached
     * within the next hour.
     */
    fun promptFor(segments: List<StormSegment>, progressM: Double, speedMps: Double, nwsSevere: Boolean): StormSegment? {
        val minLevel = if (nwsSevere) RadarIntensity.HEAVY else RadarIntensity.SEVERE
        val next = RadarRouteAnalyzer.ahead(segments, progressM).filter { it.peak >= minLevel }.minByOrNull { it.startM } ?: return null
        val dist = next.startM - progressM
        if (speedMps > 0.5 && dist / speedMps > PROMPT_HORIZON_S) return null
        return next
    }

    /** Same storm, same key, even as it drifts a little between radar updates. */
    fun stormKey(seg: StormSegment): String = "${(seg.startM / 8000.0).roundToInt()}"

    /** Point [alongM] metres along a route, and the route's bearing there (radians, 0 = north). */
    fun pointAndBearing(route: List<GeoPoint>, cum: DoubleArray, alongM: Double): Pair<GeoPoint, Double> {
        if (route.size < 2) return (route.firstOrNull() ?: GeoPoint(0.0, 0.0)) to 0.0
        val s = alongM.coerceIn(0.0, cum.last())
        var i = 1
        while (i < cum.size - 1 && cum[i] < s) i++
        val a = route[i - 1]; val b = route[i]
        val t = if (cum[i] > cum[i - 1]) (s - cum[i - 1]) / (cum[i] - cum[i - 1]) else 0.0
        val p = GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        val y = sin(Math.toRadians(b.lon - a.lon)) * cos(Math.toRadians(b.lat))
        val x = cos(Math.toRadians(a.lat)) * sin(Math.toRadians(b.lat)) -
            sin(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * cos(Math.toRadians(b.lon - a.lon))
        return p to atan2(y, x)
    }

    /** Move [distM] metres from [p] on [bearing] (radians). Flat-earth; fine for tens of km. */
    fun offset(p: GeoPoint, bearing: Double, distM: Double): GeoPoint {
        val dLat = distM * cos(bearing) / 111_320.0
        val dLon = distM * sin(bearing) / (111_320.0 * cos(Math.toRadians(p.lat)).coerceAtLeast(0.05))
        return GeoPoint(p.lat + dLat, p.lon + dLon)
    }

    /**
     * Two waypoints that force a route around the storm, one each side of its middle,
     * far enough out to clear it (15–60 km).
     */
    fun detourVias(route: List<GeoPoint>, cum: DoubleArray, storm: StormSegment): List<GeoPoint> {
        val (mid, bearing) = pointAndBearing(route, cum, (storm.startM + storm.endM) / 2)
        val dist = (storm.lengthM * 0.6 + 12_000.0).coerceIn(15_000.0, 60_000.0)
        return listOf(offset(mid, bearing + Math.PI / 2, dist), offset(mid, bearing - Math.PI / 2, dist))
    }

    /**
     * Best detour, or null when none is clearly better. A detour has to cut storm exposure at
     * least in half (or avoid the worst intensity entirely) and add no more than 25 minutes or
     * 40 % of the trip, whichever is larger.
     */
    fun pickDetour(baseline: ScoredRoute, candidates: List<ScoredRoute>): DetourChoice? {
        val maxExtra = max(25 * 60.0, baseline.option.durationS * 0.4)
        val base = baseline.exposure
        val good = candidates.filter { c ->
            val extra = c.option.durationS - baseline.option.durationS
            extra <= maxExtra && (c.exposure <= base * 0.5 || c.worst < baseline.worst) && c.exposure < base
        }
        // Each minute of extra driving "costs" about as much as half a km of moderate rain.
        val best = good.minByOrNull { it.exposure + (it.option.durationS - baseline.option.durationS) / 120.0 } ?: return null
        return DetourChoice(best, best.option.durationS - baseline.option.durationS, baseline)
    }

    /** Distance from [p] to the nearest point of [route], and how far along the route that point is. */
    fun nearestOnRoute(route: List<GeoPoint>, cum: DoubleArray, p: GeoPoint): Pair<Double, Double> {
        var best = Double.MAX_VALUE; var along = 0.0
        for (i in route.indices) {
            val d = RouteMath.haversine(route[i], p)
            if (d < best) { best = d; along = cum[i] }
        }
        return best to along
    }

    /**
     * Places to wait that the driver reaches *before* the storm: near the route (≤ 2 km off),
     * at least 500 m ahead, and at least 3 km short of the storm. Nearest first.
     */
    fun waitStops(places: List<PlaceResult>, route: List<GeoPoint>, cum: DoubleArray, progressM: Double, stormStartM: Double): List<WaitStop> {
        val candidates = places.map { pl ->
            val (off, along) = nearestOnRoute(route, cum, GeoPoint(pl.lat, pl.lon))
            WaitStop(pl, along, off)
        }.filter { it.offRouteM <= 2_000.0 && it.alongM >= progressM + 500.0 && it.alongM <= stormStartM - 3_000.0 }
            .distinctBy { "${it.place.name}|${(it.alongM / 300).toInt()}" }
            .sortedBy { it.alongM }
        // A useful mix, spread along the way: a town full of restaurants shouldn't crowd out
        // the gas station or hotel a few miles on.
        val limits = mapOf("fuel" to 3, "food" to 4, "lodging" to 2, "rest" to 2)
        val picked = mutableListOf<WaitStop>()
        for (c in candidates) {
            val group = waitGroup(c.place.category)
            val same = picked.filter { waitGroup(it.place.category) == group }
            if (same.size >= (limits[group] ?: 2)) continue
            if (same.any { kotlin.math.abs(it.alongM - c.alongM) < 1_500.0 }) continue
            picked += c
        }
        return picked.sortedBy { it.alongM }
    }

    fun waitGroup(category: String): String = when (category) {
        "fuel" -> "fuel"; "hotel", "motel" -> "lodging"; "rest_area" -> "rest"; else -> "food"
    }

    /** The part of a route to search for stops: from here to just before the storm, ≤ 60 points. */
    fun corridor(route: List<GeoPoint>, cum: DoubleArray, fromM: Double, toM: Double): List<GeoPoint> {
        val inside = route.indices.filter { cum[it] >= fromM && cum[it] <= toM }.map { route[it] }
        if (inside.size <= 60) return inside
        val step = inside.size / 60.0
        return (0 until 60).map { inside[min(inside.size - 1, (it * step).toInt())] } + inside.last()
    }

    /**
     * After waiting: is the way on clear? No heavy-or-worse stretch starting within the next
     * [horizonM] metres of the fresh route from where the driver is.
     */
    fun clearAhead(segments: List<StormSegment>, horizonM: Double = 50_000.0): Boolean =
        segments.none { it.peak >= RadarIntensity.HEAVY && it.startM <= horizonM }
}
