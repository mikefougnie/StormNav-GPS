package com.stormnav.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sinh

/*
 * Storm motion from two radar frames, and simple "nowcasting" by moving today's radar
 * along that motion: when a storm reaches a place, where it will be when you get there,
 * and where to aim when chasing one.
 *
 * This is straight-line extrapolation, the standard first step in nowcasting: good for the
 * next 30–60 minutes, rougher beyond that, and it can't foresee storms forming or dying.
 */

/** Radar intensity levels (RadarIntensity) on the pixel grid of one radar image. */
class LevelGrid(val window: RadarWindow, val levels: ByteArray) {
    val width get() = window.width
    val height get() = window.height

    fun at(x: Int, y: Int): Int = if (x < 0 || y < 0 || x >= width || y >= height) 0 else levels[y * width + x].toInt()

    /** Strongest level within [r] pixels of (x, y). */
    fun maxAround(x: Int, y: Int, r: Int): Int {
        var best = 0
        for (dy in -r..r) for (dx in -r..r) best = max(best, at(x + dx, y + dy))
        return best
    }

    /** Mercator metres per pixel (x and y are equal for windows built by RadarWindow). */
    val metresPerPx: Double get() = (window.maxX - window.minX) / width

    fun pixelOfMerc(mx: Double, my: Double): Pair<Int, Int> =
        ((mx - window.minX) / metresPerPx).toInt() to ((window.maxY - my) / ((window.maxY - window.minY) / height)).toInt()

    fun mercOfPixel(px: Double, py: Double): Pair<Double, Double> =
        window.minX + (px + 0.5) * metresPerPx to window.maxY - (py + 0.5) * ((window.maxY - window.minY) / height)

    companion object {
        fun fromPixels(window: RadarWindow, argb: IntArray): LevelGrid =
            LevelGrid(window, ByteArray(argb.size) { RadarIntensity.classify(argb[it]).toByte() })
    }
}

/** Storm motion in Web-Mercator metres per second (x east, y north). */
data class Motion(val vx: Double, val vy: Double, val confidence: Double) {
    /** Ground speed at [lat] (Mercator stretches distances by 1/cos(lat)). */
    fun speedMps(lat: Double) = hypot(vx, vy) * cos(Math.toRadians(lat))
    /** Direction the storm is moving *toward*, degrees clockwise from north. */
    val headingDeg: Double get() = (Math.toDegrees(atan2(vx, vy)) + 360.0) % 360.0
}

/** When a storm reaches a place: seconds from now until it starts and ends, and how strong. */
data class Arrival(val startS: Double, val endS: Double, val peak: Int)

/** One storm cell on radar. */
data class StormCell(val center: GeoPoint, val peak: Int, val areaKm2: Double, val pixels: Int)

object StormMotion {
    fun mercToGeo(mx: Double, my: Double) =
        GeoPoint(Math.toDegrees(atan(sinh(my / RouteMath.EARTH_RADIUS_M))), Math.toDegrees(mx / RouteMath.EARTH_RADIUS_M))

    private val COMPASS = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    fun compass(deg: Double): String = COMPASS[(((deg % 360 + 360) % 360) / 22.5).roundToInt() % 16]

    /** Max-pool a grid down so its width is at most [maxW]; weights are the levels (moderate+ only). */
    private fun pooled(g: LevelGrid, factor: Int): Array<IntArray> {
        val w = (g.width + factor - 1) / factor; val h = (g.height + factor - 1) / factor
        return Array(h) { y -> IntArray(w) { x ->
            var m = 0
            for (dy in 0 until factor) for (dx in 0 until factor) m = max(m, g.at(x * factor + dx, y * factor + dy))
            if (m >= RadarIntensity.MODERATE) m else 0
        } }
    }

    /**
     * Motion that best maps the older frame onto the newer one (cross-correlation of the
     * moderate-or-stronger echoes). Null when there is too little rain to track, or the
     * frames are too far apart in time.
     */
    fun estimate(older: LevelGrid, newer: LevelGrid, dtS: Double, maxSpeedMps: Double = 36.0): Motion? {
        if (dtS < 120 || dtS > 3600 || older.width != newer.width || older.height != newer.height) return null
        val factor = max(1, ceil(newer.width / 160.0).toInt())
        val a = pooled(older, factor); val b = pooled(newer, factor)
        val h = a.size; val w = a[0].size
        val signal = a.sumOf { row -> row.count { it > 0 } }
        if (signal < 12) return null
        val mpp = newer.metresPerPx * factor
        val midLat = mercToGeo(0.0, (newer.window.minY + newer.window.maxY) / 2).lat
        val reach = ceil(maxSpeedMps * dtS / cos(Math.toRadians(midLat)) / mpp).toInt().coerceIn(1, 40)
        var best = -1.0; var bx = 0; var by = 0; var zero = 0.0
        for (sy in -reach..reach) for (sx in -reach..reach) {
            var score = 0.0
            for (y in max(0, -sy) until min(h, h - sy)) {
                val ra = a[y]; val rb = b[y + sy]
                for (x in max(0, -sx) until min(w, w - sx)) {
                    val va = ra[x]; if (va == 0) continue
                    val vb = rb[x + sx]; if (vb != 0) score += min(va, vb)
                }
            }
            // Gentle preference for slower motion breaks ties between similar matches.
            val adj = score * (1.0 - 0.002 * hypot(sx.toDouble(), sy.toDouble()))
            if (sx == 0 && sy == 0) zero = score
            if (adj > best) { best = adj; bx = sx; by = sy }
        }
        if (best <= 0) return null
        val total = a.sumOf { it.sum() }.toDouble()
        val confidence = (best / total).coerceIn(0.0, 1.0)
        if (confidence < 0.15) return null
        return Motion(bx * mpp / dtS, -by * mpp / dtS, confidence)
    }

    /** Radar level at [p] after the storms move for [tS] seconds (radar sampled upwind). */
    fun levelAfter(g: LevelGrid, p: GeoPoint, m: Motion, tS: Double, radiusPx: Int = 1): Int {
        val mx = RouteMath.mercatorX(p.lon) - m.vx * tS
        val my = RouteMath.mercatorY(p.lat) - m.vy * tS
        val (px, py) = g.pixelOfMerc(mx, my)
        return g.maxAround(px, py, radiusPx)
    }

    /** When weather of at least [minLevel] reaches [p], and when it has passed (within [horizonS]). */
    fun arrivalAt(g: LevelGrid, p: GeoPoint, m: Motion, minLevel: Int = RadarIntensity.HEAVY, horizonS: Double = 7200.0, stepS: Double = 120.0): Arrival? {
        var start = -1.0; var end = -1.0; var peak = 0; var t = 0.0
        while (t <= horizonS) {
            val lv = levelAfter(g, p, m, t)
            if (lv >= minLevel) { if (start < 0) start = t; end = t; peak = max(peak, lv) }
            else if (start >= 0 && t - end > 2 * stepS) break
            t += stepS
        }
        return if (start < 0) null else Arrival(start, end + stepS, peak)
    }

    /**
     * Storm stretches the driver actually meets: each point of the route is checked against
     * the radar moved forward to the moment the driver gets there (leaving after [departDelayS]).
     */
    fun movingSegments(route: List<GeoPoint>, g: LevelGrid, m: Motion, speedMps: Double, fromM: Double = 0.0,
                       departDelayS: Double = 0.0, stepM: Double = 1_000.0, gapM: Double = 3_000.0): List<StormSegment> {
        if (route.size < 2 || speedMps <= 0.5) return emptyList()
        val samples = RouteMath.sampleEvery(route, max(stepM, RouteMath.totalLength(route) / 600.0))
        val out = mutableListOf<StormSegment>()
        var start = -1.0; var end = -1.0; var peak = 0
        for (s in samples) {
            if (s.distanceM < fromM) continue
            val t = departDelayS + (s.distanceM - fromM) / speedMps
            val lv = levelAfter(g, s.point, m, t, radiusPx = 2)
            if (lv >= RadarIntensity.MODERATE) {
                if (start < 0) { start = s.distanceM; peak = lv } else peak = max(peak, lv)
                end = s.distanceM
            } else if (start >= 0 && s.distanceM - end > gapM) {
                out += StormSegment(start, end, peak); start = -1.0; peak = 0
            }
        }
        if (start >= 0) out += StormSegment(start, end, peak)
        return out
    }

    /** Storm exposure for leaving now, in 15 min, … up to [maxDelayS]. */
    fun departureOptions(route: List<GeoPoint>, g: LevelGrid, m: Motion, speedMps: Double, maxDelayS: Double = 3 * 3600.0, stepS: Double = 900.0): List<Pair<Double, List<StormSegment>>> {
        val out = mutableListOf<Pair<Double, List<StormSegment>>>()
        var d = 0.0
        while (d <= maxDelayS) { out += d to movingSegments(route, g, m, speedMps, departDelayS = d); d += stepS }
        return out
    }

    /** Suggested departure: the earliest delay whose exposure is at most a quarter of leaving now's. */
    fun bestDeparture(options: List<Pair<Double, List<StormSegment>>>): Double? {
        val now = options.firstOrNull()?.let { StormPlanner.exposure(it.second, 0.0) } ?: return null
        if (now < 2.0) return null // already basically dry
        return options.firstOrNull { StormPlanner.exposure(it.second, 0.0) <= now * 0.25 }?.first
    }

    /** Connected areas of at least [minLevel] (4-neighbour), biggest first; tiny specks dropped. */
    fun cells(g: LevelGrid, minLevel: Int = RadarIntensity.HEAVY, minPixels: Int = 4): List<StormCell> {
        val seen = BooleanArray(g.width * g.height)
        val out = mutableListOf<StormCell>()
        val stack = IntArray(g.width * g.height)
        val kmPerPx = g.metresPerPx / 1000.0
        for (start in 0 until g.width * g.height) {
            if (seen[start] || g.levels[start] < minLevel) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var n = 0; var sx = 0.0; var sy = 0.0; var sw = 0.0; var peak = 0
            while (sp > 0) {
                val i = stack[--sp]; val x = i % g.width; val y = i / g.width
                val lv = g.levels[i].toInt()
                val wgt = exp(lv.toDouble()) // the core pulls the centre toward it
                n++; sx += x * wgt; sy += y * wgt; sw += wgt; peak = max(peak, lv)
                for ((nx, ny) in arrayOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1)) {
                    if (nx < 0 || ny < 0 || nx >= g.width || ny >= g.height) continue
                    val j = ny * g.width + nx
                    if (!seen[j] && g.levels[j] >= minLevel) { seen[j] = true; stack[sp++] = j }
                }
            }
            if (n < minPixels) continue
            val (mx, my) = g.mercOfPixel(sx / sw, sy / sw)
            val c = mercToGeo(mx, my)
            val area = n * kmPerPx * kmPerPx * cos(Math.toRadians(c.lat)).let { it * it }
            out += StormCell(c, peak, area, n)
        }
        return out.sortedByDescending { it.pixels }
    }

    /** Where the storm will be after moving for [tS] seconds. */
    fun moved(p: GeoPoint, m: Motion, tS: Double): GeoPoint =
        mercToGeo(RouteMath.mercatorX(p.lon) + m.vx * tS, RouteMath.mercatorY(p.lat) + m.vy * tS)

    /**
     * Where to drive to meet a moving storm: solves "where will it be by the time I get
     * there" a few times (roads ≈ 1.3 × straight-line distance), capped at 90 minutes ahead.
     */
    fun intercept(from: GeoPoint, cell: GeoPoint, m: Motion?, driveMps: Double = 22.0): GeoPoint {
        if (m == null) return cell
        var target = cell
        repeat(6) {
            val t = min(5400.0, RouteMath.haversine(from, target) * 1.3 / driveMps)
            target = moved(cell, m, t)
        }
        return target
    }

    /** A point [bufferM] short of [target] on the way from [from] (never past the start). */
    fun stopShort(from: GeoPoint, target: GeoPoint, bufferM: Double): GeoPoint {
        val d = RouteMath.haversine(from, target)
        if (bufferM <= 0 || d <= 0) return target
        val f = (1.0 - bufferM / d).coerceIn(0.0, 1.0)
        return GeoPoint(from.lat + (target.lat - from.lat) * f, from.lon + (target.lon - from.lon) * f)
    }

    /** Bearing from [a] to [b], degrees clockwise from north. */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val y = kotlin.math.sin(Math.toRadians(b.lon - a.lon)) * cos(Math.toRadians(b.lat))
        val x = cos(Math.toRadians(a.lat)) * kotlin.math.sin(Math.toRadians(b.lat)) -
            kotlin.math.sin(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * cos(Math.toRadians(b.lon - a.lon))
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /**
     * Times listed in a WMS time dimension: either a comma list of ISO instants or
     * "start/end/PTnM" intervals. Returns epoch milliseconds, oldest first.
     */
    fun parseTimes(dimension: String): List<Long> {
        val out = mutableListOf<Long>()
        for (part in dimension.trim().split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            val bits = part.split("/")
            if (bits.size == 3) {
                val s = iso(bits[0]) ?: continue; val e = iso(bits[1]) ?: continue
                val step = isoPeriodMs(bits[2]) ?: continue
                var t = e
                // Only the last couple of hours matter.
                while (t >= s && e - t <= 3 * 3600_000L) { out += t; t -= step }
            } else iso(part)?.let { out += it }
        }
        return out.distinct().sorted()
    }

    private fun iso(s: String): Long? = runCatching { java.time.Instant.parse(s.trim()).toEpochMilli() }.getOrNull()
    private fun isoPeriodMs(s: String): Long? = runCatching { java.time.Duration.parse(s.trim()).toMillis() }.getOrNull()?.takeIf { it > 0 }

    /** The frame to compare with the newest: the one closest to [targetAgoMs] before it. */
    fun pickPair(times: List<Long>, targetAgoMs: Long = 15 * 60_000L): Pair<Long, Long>? {
        val newest = times.maxOrNull() ?: return null
        val older = times.filter { it < newest - 4 * 60_000L && it > newest - 60 * 60_000L }
            .minByOrNull { abs((newest - it) - targetAgoMs) } ?: return null
        return older to newest
    }

    /** The time list from a WMS GetCapabilities document (WMS 1.3 Dimension or 1.1 Extent). */
    fun timesFromCapabilities(xml: String): List<Long> {
        val m = Regex("""<(Dimension|Extent)[^>]*name="time"[^>]*>([^<]*)</""", RegexOption.IGNORE_CASE).find(xml) ?: return emptyList()
        return parseTimes(m.groupValues[2])
    }

    fun isoUtc(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString()

    /** A storm description such as "Severe storm 18 mi WSW, moving ENE at 32 mph". */
    fun describe(cell: StormCell, from: GeoPoint, m: Motion?, units: UnitSystem): String {
        val d = RouteMath.haversine(from, cell.center)
        val where = "${Units.short(d, if (units == UnitSystem.BOTH) UnitSystem.IMPERIAL else units)} ${compass(bearing(from, cell.center))}"
        val what = if (cell.peak >= RadarIntensity.SEVERE) "Severe storm" else "Heavy storm"
        val move = m?.let { mo ->
            val sp = mo.speedMps(cell.center.lat)
            if (sp < 2.0) ", nearly stationary"
            else ", moving ${compass(mo.headingDeg)} at ${Units.wind(sp * 2.236936, if (units == UnitSystem.BOTH) UnitSystem.IMPERIAL else units)}"
        } ?: ""
        return "$what $where$move"
    }

    @Suppress("unused") private const val TAU = 2 * PI
}
