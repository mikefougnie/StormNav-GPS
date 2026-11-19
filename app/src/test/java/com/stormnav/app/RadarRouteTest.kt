package com.stormnav.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarRouteTest {
    // ~111 km straight line due east along lat 40
    private val route = listOf(GeoPoint(40.0, -90.0), GeoPoint(40.0, -89.0), GeoPoint(40.0, -88.0))

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun haversineOneDegreeOfLongitudeAtLat40() {
        val d = RouteMath.haversine(GeoPoint(40.0, -90.0), GeoPoint(40.0, -89.0))
        assertEquals(85_300.0, d, 500.0)
    }

    @Test fun sampleEveryIncludesEndpointsAndSpacing() {
        val samples = RouteMath.sampleEvery(route, 20_000.0)
        assertEquals(0.0, samples.first().distanceM, 0.0)
        assertEquals(RouteMath.totalLength(route), samples.last().distanceM, 0.001)
        assertEquals(20_000.0, samples[1].distanceM, 0.001)
        // interpolated points stay on the line
        assertTrue(samples.all { it.point.lat == 40.0 })
    }

    @Test fun progressAlongProjectsOntoRoute() {
        val cum = RouteMath.cumulative(route)
        val mid = RouteMath.progressAlong(route, cum, GeoPoint(40.01, -89.0))
        assertEquals(cum[1], mid, 50.0)
    }

    @Test fun classifyPaletteColors() {
        assertEquals(RadarIntensity.NONE, RadarIntensity.classify(argb(0, 255, 0, 0)))
        assertEquals(RadarIntensity.LIGHT, RadarIntensity.classify(argb(255, 0, 200, 0)))
        assertEquals(RadarIntensity.MODERATE, RadarIntensity.classify(argb(255, 255, 255, 0)))
        assertEquals(RadarIntensity.HEAVY, RadarIntensity.classify(argb(255, 255, 140, 0)))
        assertEquals(RadarIntensity.HEAVY, RadarIntensity.classify(argb(255, 255, 0, 0)))
        assertEquals(RadarIntensity.SEVERE, RadarIntensity.classify(argb(255, 255, 0, 255)))
        assertEquals(RadarIntensity.SEVERE, RadarIntensity.classify(argb(255, 255, 255, 255)))
    }

    @Test fun windowPixelMappingAndAspect() {
        val w = RadarWindow.forRoute(route, padM = 10_000.0, maxPx = 800)
        assertEquals(800, w.width)
        val ratioImage = w.width.toDouble() / w.height
        val ratioGeo = (w.maxX - w.minX) / (w.maxY - w.minY)
        assertEquals(ratioGeo, ratioImage, 0.02)
        assertNotNull(w.pixelFor(route[1]))
        assertNull(w.pixelFor(GeoPoint(10.0, 10.0)))
        // west end is left of east end
        assertTrue(w.pixelFor(route.first())!!.first < w.pixelFor(route.last())!!.first)
    }

    @Test fun findsStormSegmentWithinKnownLongitudeBand() {
        val w = RadarWindow.forRoute(route, padM = 10_000.0, maxPx = 1024)
        val stormMinX = RouteMath.mercatorX(-89.5)
        val stormMaxX = RouteMath.mercatorX(-89.3)
        // radar field: red where x lies in the storm band, transparent elsewhere
        val pixelAt = { px: Int, _: Int ->
            val x = w.minX + (px + 0.5) / w.width * (w.maxX - w.minX)
            if (x in stormMinX..stormMaxX) argb(255, 255, 0, 0) else 0
        }
        val segs = RadarRouteAnalyzer.analyze(route, w, pixelAt)
        assertEquals(1, segs.size)
        val seg = segs[0]
        assertEquals(RadarIntensity.HEAVY, seg.peak)
        val cum = RouteMath.cumulative(route)
        val expectedStart = cum[0] + (cum[1] * 0.5) // lon -89.5 is halfway to -89.0
        assertEquals(expectedStart, seg.startM, 3_000.0)
        assertEquals(17_000.0, seg.lengthM, 6_000.0) // 0.2 deg lon ~ 17 km
    }

    @Test fun clearRouteReturnsNothing() {
        val w = RadarWindow.forRoute(route)
        assertTrue(RadarRouteAnalyzer.analyze(route, w, { _, _ -> 0 }).isEmpty())
        assertNull(RadarRouteAnalyzer.summarize(emptyList(), 0.0, 20.0))
    }

    @Test fun aheadTrimsPassedStorm() {
        val s = listOf(StormSegment(1000.0, 5000.0, 3), StormSegment(20_000.0, 30_000.0, 2))
        val ahead = RadarRouteAnalyzer.ahead(s, 3000.0)
        assertEquals(2, ahead.size)
        assertEquals(3000.0, ahead[0].startM, 0.0)
        assertEquals(1, RadarRouteAnalyzer.ahead(s, 6000.0).size)
        assertEquals(0, RadarRouteAnalyzer.ahead(s, 31_000.0).size)
    }

    @Test fun summaryMentionsDistanceAndEta() {
        val s = listOf(StormSegment(42_000.0, 51_000.0, 3))
        val text = RadarRouteAnalyzer.summarize(s, 0.0, 20.0)!!
        assertTrue(text, text.contains("in 42 km"))
        assertTrue(text, text.contains("~35 min"))
        assertTrue(text, text.contains("9.0 km") || text.contains("9 km"))
        assertTrue(text, text.contains("mi)"))
    }

    @Test fun nearGapsMergeIntoOneStorm() {
        val w = RadarWindow.forRoute(route, padM = 10_000.0, maxPx = 1024)
        val bands = listOf(-89.60..-89.55, -89.52..-89.45) // ~2 km gap
        val pixelAt = { px: Int, _: Int ->
            val lon = Math.toDegrees((w.minX + (px + 0.5) / w.width * (w.maxX - w.minX)) / RouteMath.EARTH_RADIUS_M)
            if (bands.any { lon in it }) argb(255, 255, 255, 0) else 0
        }
        assertEquals(1, RadarRouteAnalyzer.analyze(route, w, pixelAt).size)
    }

    @Test fun unitsShowBothSystems() {
        assertEquals("350 m (1150 ft)", Units.distance(350.0))
        assertEquals("4.2 km (2.6 mi)", Units.distance(4200.0))
        assertEquals("42 km (26 mi)", Units.distance(42_000.0))
        assertEquals("72°F (22°C)", Units.tempFromF(72.0))
        assertEquals("22°C (72°F)", Units.tempFromC(22.0))
        assertEquals("-40°F (-40°C)", Units.tempFromF(-40.0))
    }
}
