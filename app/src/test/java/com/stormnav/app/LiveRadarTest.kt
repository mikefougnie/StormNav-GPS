package com.stormnav.app

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Real NOAA radar: checks the time dimension exists, fetches two frames, finds storms and
 * measures their motion. Skipped unless LIVE_SEARCH=1 (runs as the non-blocking CI step).
 */
class LiveRadarTest {
    private val base = "https://opengeo.ncep.noaa.gov/geoserver/conus/conus_bref_qcd/ows"

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "StormNav-CI/0.9 (github.com/mikefougnie/StormNav-GPS)")
        try { return c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }

    private fun frame(win: RadarWindow, timeMs: Long?): LevelGrid {
        val t = timeMs?.let { "&TIME=" + StormMotion.isoUtc(it) } ?: ""
        val url = "$base?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&LAYERS=conus_bref_qcd&STYLES=&FORMAT=image/png&TRANSPARENT=true&SRS=EPSG:3857" +
            "&WIDTH=${win.width}&HEIGHT=${win.height}&BBOX=${win.bbox}$t"
        // javax.imageio isn't on the Android test compile classpath, but the CI JVM has it.
        val imageIO = Class.forName("javax.imageio.ImageIO")
        val img = imageIO.getMethod("read", java.io.InputStream::class.java).invoke(null, get(url).inputStream())
        val w = img.javaClass.getMethod("getWidth").invoke(img) as Int
        val h = img.javaClass.getMethod("getHeight").invoke(img) as Int
        val px = IntArray(w * h)
        img.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(img, 0, 0, w, h, px, 0, w)
        return LevelGrid.fromPixels(RadarWindow(win.minX, win.minY, win.maxX, win.maxY, w, h), px)
    }

    @Test fun timeDimensionAndMotion() {
        assumeTrue(System.getenv("LIVE_SEARCH") == "1")
        val caps = String(get("$base?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetCapabilities"))
        val dim = Regex("""<(Dimension|Extent)[^>]*name="time"[^>]*>[^<]{0,200}""", RegexOption.IGNORE_CASE).find(caps)?.value
        println("LIVE caps bytes=${caps.length} time dim: $dim")
        val times = StormMotion.timesFromCapabilities(caps)
        println("LIVE times: ${times.size}, newest=${times.maxOrNull()?.let { StormMotion.isoUtc(it) }}")
        val pair = StormMotion.pickPair(times)
        println("LIVE pair: ${pair?.let { StormMotion.isoUtc(it.first) + " -> " + StormMotion.isoUtc(it.second) }}")

        // Whole US at low resolution: where are the storms right now?
        val us = RadarWindow.forRoute(listOf(GeoPoint(24.5, -124.0), GeoPoint(49.0, -67.0)), padM = 0.0, maxPx = 1024)
        val usNow = frame(us, pair?.second)
        val cells = StormMotion.cells(usNow, RadarIntensity.MODERATE, minPixels = 6)
        println("LIVE US cells (moderate+): ${cells.size}; biggest: " + cells.take(5).joinToString { "${"%.2f,%.2f".format(it.center.lat, it.center.lon)} peak=${it.peak} px=${it.pixels}" })
        if (pair == null || cells.isEmpty()) { println("LIVE no time pair or no rain anywhere; motion not testable today"); return }

        // Zoom on the biggest area of rain and measure its motion.
        val c = cells.first().center
        val win = RadarWindow.forRoute(listOf(c), padM = 150_000.0, maxPx = 512)
        val newer = frame(win, pair.second); val older = frame(win, pair.first)
        val dt = (pair.second - pair.first) / 1000.0
        val m = StormMotion.estimate(older, newer, dt)
        println("LIVE motion near ${"%.2f,%.2f".format(c.lat, c.lon)} over ${dt / 60} min: " +
            (m?.let { "${StormMotion.compass(it.headingDeg)} at ${"%.1f".format(it.speedMps(c.lat) * 2.236936)} mph (confidence ${"%.2f".format(it.confidence)})" } ?: "not trackable"))
        val local = StormMotion.cells(newer)
        println("LIVE local heavy cells: ${local.size}; " + local.take(3).joinToString { "${"%.2f,%.2f".format(it.center.lat, it.center.lon)} peak=${it.peak} area=${it.areaKm2.toInt()}km2" })
        if (m != null) {
            val probe = GeoPoint(c.lat, c.lon + 0.5)
            println("LIVE arrival at a point 0.5° east: ${StormMotion.arrivalAt(newer, probe, m, RadarIntensity.MODERATE)}")
        }
    }
}
