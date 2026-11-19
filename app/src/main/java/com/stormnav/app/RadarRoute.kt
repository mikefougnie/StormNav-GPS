package com.stormnav.app

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * Android-free route / radar math so it can be unit-tested on a plain JVM.
 * MainActivity feeds it a route polyline and a decoded NOAA radar bitmap.
 */

data class GeoPoint(val lat: Double, val lon: Double)

/** A point on the route together with how far along the route it is, in meters. */
data class RoutePoint(val point: GeoPoint, val distanceM: Double)

object RouteMath {
    const val EARTH_RADIUS_M = 6_378_137.0

    fun haversine(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6_371_000.0 * atan2(sqrt(h), sqrt(1 - h))
    }

    fun mercatorX(lon: Double): Double = EARTH_RADIUS_M * Math.toRadians(lon)

    fun mercatorY(lat: Double): Double {
        val clamped = lat.coerceIn(-85.05, 85.05)
        return EARTH_RADIUS_M * ln(tan(PI / 4 + Math.toRadians(clamped) / 2))
    }

    /** Cumulative distance (m) at each vertex; cum[0] = 0. */
    fun cumulative(route: List<GeoPoint>): DoubleArray {
        val cum = DoubleArray(route.size)
        for (i in 1 until route.size) cum[i] = cum[i - 1] + haversine(route[i - 1], route[i])
        return cum
    }

    fun totalLength(route: List<GeoPoint>): Double = cumulative(route).lastOrNull() ?: 0.0

    /**
     * Points every [spacingM] meters along the route (interpolated inside segments),
     * always including the first and last point.
     */
    fun sampleEvery(route: List<GeoPoint>, spacingM: Double): List<RoutePoint> {
        if (route.isEmpty()) return emptyList()
        val cum = cumulative(route)
        val total = cum.last()
        val out = mutableListOf(RoutePoint(route.first(), 0.0))
        if (route.size < 2 || total <= 0.0 || spacingM <= 0.0) return out
        var target = spacingM
        var seg = 0
        while (target < total) {
            while (seg < route.size - 2 && cum[seg + 1] < target) seg++
            val segLen = cum[seg + 1] - cum[seg]
            val t = if (segLen <= 0.0) 0.0 else (target - cum[seg]) / segLen
            val a = route[seg]
            val b = route[seg + 1]
            out.add(RoutePoint(GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t), target))
            target += spacingM
        }
        out.add(RoutePoint(route.last(), total))
        return out
    }

    /** Distance along the route (m) of the point on the polyline nearest to [p]. */
    fun progressAlong(route: List<GeoPoint>, cum: DoubleArray, p: GeoPoint): Double {
        if (route.size < 2) return 0.0
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(p.lat)).coerceAtLeast(0.01)
        var bestDist = Double.POSITIVE_INFINITY
        var bestAlong = 0.0
        for (i in 0 until route.size - 1) {
            val ax = (route[i].lon - p.lon) * mLon
            val ay = (route[i].lat - p.lat) * mLat
            val bx = (route[i + 1].lon - p.lon) * mLon
            val by = (route[i + 1].lat - p.lat) * mLat
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val ex = ax + t * dx
            val ey = ay + t * dy
            val d = sqrt(ex * ex + ey * ey)
            if (d < bestDist) {
                bestDist = d
                bestAlong = cum[i] + t * (cum[i + 1] - cum[i])
            }
        }
        return bestAlong
    }
}

object RadarIntensity {
    const val NONE = 0
    const val LIGHT = 1
    const val MODERATE = 2
    const val HEAVY = 3
    const val SEVERE = 4

    /**
     * Heuristic classification of one pixel of an NWS-style reflectivity image
     * (green -> yellow -> orange -> red -> magenta/purple -> white).
     *
     * This is a color heuristic, NOT calibrated dBZ. It must be checked against the real
     * palette of the NOAA layer on a device (see README), and ideally replaced by
     * WMS GetFeatureInfo or raw MRMS values.
     */
    fun classify(argb: Int): Int {
        val a = (argb ushr 24) and 0xFF
        if (a < 40) return NONE
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        // white / very pale: extreme returns (hail cores) in most NWS palettes
        if (r > 200 && g > 200 && b > 200) return SEVERE
        // magenta / purple
        if (r > 120 && b > 120 && g < r - 40 && g < b - 20) return SEVERE
        // red
        if (r > 170 && g < 90 && b < 90) return HEAVY
        // orange
        if (r > 200 && g in 100..199 && b < 100) return HEAVY
        // yellow
        if (r > 190 && g > 190 && b < 110) return MODERATE
        // dark yellow / olive transition shades
        if (r > 150 && g > 150 && b < 80) return MODERATE
        // green, cyan, blue: light precipitation
        return LIGHT
    }

    fun label(level: Int): String = when (level) {
        LIGHT -> "light rain"
        MODERATE -> "moderate rain"
        HEAVY -> "heavy rain / strong storm"
        SEVERE -> "severe storm core (possible hail)"
        else -> "no precipitation"
    }
}

/** Geographic window (EPSG:3857) covered by a requested radar image. */
data class RadarWindow(
    val minX: Double, val minY: Double, val maxX: Double, val maxY: Double,
    val width: Int, val height: Int
) {
    val bbox: String get() = "$minX,$minY,$maxX,$maxY"

    /** Pixel (x, y) for a lat/lon, or null if outside the image. */
    fun pixelFor(p: GeoPoint): Pair<Int, Int>? {
        val x = RouteMath.mercatorX(p.lon)
        val y = RouteMath.mercatorY(p.lat)
        val px = ((x - minX) / (maxX - minX) * width).toInt()
        val py = ((maxY - y) / (maxY - minY) * height).toInt()
        return if (px in 0 until width && py in 0 until height) px to py else null
    }

    companion object {
        /** Window around the route with padding; aspect ratio matches the image so it is not distorted. */
        fun forRoute(route: List<GeoPoint>, padM: Double = 15_000.0, maxPx: Int = 1024): RadarWindow {
            require(route.isNotEmpty()) { "empty route" }
            var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (p in route) {
                val x = RouteMath.mercatorX(p.lon); val y = RouteMath.mercatorY(p.lat)
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            }
            // padM is ground distance; Web Mercator stretches by 1/cos(lat) so scale it
            val midLat = Math.toDegrees(2 * kotlin.math.atan(kotlin.math.exp(((minY + maxY) / 2) / RouteMath.EARTH_RADIUS_M)) - PI / 2)
            val pad = padM / cos(Math.toRadians(midLat)).coerceAtLeast(0.05)
            minX -= pad; maxX += pad; minY -= pad; maxY += pad
            val dx = maxX - minX
            val dy = maxY - minY
            val w: Int
            val h: Int
            if (dx >= dy) {
                w = maxPx; h = max(64, (maxPx * dy / dx).roundToInt())
            } else {
                h = maxPx; w = max(64, (maxPx * dx / dy).roundToInt())
            }
            return RadarWindow(minX, minY, maxX, maxY, w, h)
        }
    }
}

/** A stretch of route that radar says is wet. Distances are along the route, from its start. */
data class StormSegment(val startM: Double, val endM: Double, val peak: Int) {
    val lengthM: Double get() = endM - startM
}

object RadarRouteAnalyzer {
    /**
     * Walks the route and finds stretches where radar shows at least [minLevel].
     *
     * @param pixelAt returns the ARGB pixel at (x, y) of the radar image described by [window]
     * @param stepM   sampling interval along the route
     * @param gapM    clear stretches shorter than this do not split one storm into two segments
     * @param radiusPx a hit anywhere within this many pixels of the route point counts, so a
     *                 narrow road is not missed by a one-pixel offset
     */
    fun analyze(
        route: List<GeoPoint>,
        window: RadarWindow,
        pixelAt: (Int, Int) -> Int,
        stepM: Double = 1_000.0,
        minLevel: Int = RadarIntensity.MODERATE,
        gapM: Double = 3_000.0,
        radiusPx: Int = 2
    ): List<StormSegment> {
        // Cap samples so very long routes stay cheap.
        val total = RouteMath.totalLength(route)
        val step = max(stepM, total / 600.0)
        val samples = RouteMath.sampleEvery(route, step)
        val segments = mutableListOf<StormSegment>()
        var start = -1.0
        var end = -1.0
        var peak = RadarIntensity.NONE
        for (s in samples) {
            val level = levelAt(window, pixelAt, s.point, radiusPx)
            if (level >= minLevel) {
                if (start < 0) { start = s.distanceM; peak = level } else peak = max(peak, level)
                end = s.distanceM
            } else if (start >= 0 && s.distanceM - end > gapM) {
                segments.add(StormSegment(start, end, peak))
                start = -1.0; peak = RadarIntensity.NONE
            }
        }
        if (start >= 0) segments.add(StormSegment(start, end, peak))
        return segments
    }

    private fun levelAt(window: RadarWindow, pixelAt: (Int, Int) -> Int, p: GeoPoint, radius: Int): Int {
        val px = window.pixelFor(p) ?: return RadarIntensity.NONE
        var best = RadarIntensity.NONE
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val x = px.first + dx
            val y = px.second + dy
            if (x < 0 || y < 0 || x >= window.width || y >= window.height) continue
            best = max(best, RadarIntensity.classify(pixelAt(x, y)))
        }
        return best
    }

    /** Segments (or the unfinished part of them) still ahead of [progressM]. */
    fun ahead(segments: List<StormSegment>, progressM: Double): List<StormSegment> =
        segments.filter { it.endM > progressM }.map { if (it.startM < progressM) it.copy(startM = progressM) else it }

    /** One-line, human-readable summary of the nearest storm ahead; null if the route is clear. */
    fun summarize(segments: List<StormSegment>, progressM: Double, avgSpeedMps: Double): String? {
        val next = ahead(segments, progressM).minByOrNull { it.startM } ?: return null
        val dist = next.startM - progressM
        val etaMin = if (avgSpeedMps > 1.0) (dist / avgSpeedMps / 60.0).roundToInt() else -1
        val where = if (dist < 1000.0) "now" else "in ${Units.distance(dist)}" + if (etaMin >= 1) " (~$etaMin min)" else ""
        val extent = if (next.lengthM >= 1000.0) ", ${Units.distance(next.lengthM)} long" else ""
        return "${RadarIntensity.label(next.peak).replaceFirstChar { it.uppercase() }} $where$extent"
    }
}
