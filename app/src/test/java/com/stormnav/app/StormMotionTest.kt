package com.stormnav.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StormMotionTest {
    // ~300 km window around Springfield, IL at 400 px (~1 km/px in Mercator at this latitude).
    private val here = GeoPoint(39.80, -89.64)
    private val win = RadarWindow.forRoute(listOf(here), padM = 150_000.0, maxPx = 400)

    /** A synthetic storm: severe core in a heavy ring, centred [km] east/north of [here]. */
    private fun grid(eastKm: Double, northKm: Double, radiusPx: Int = 9): LevelGrid {
        val g = ByteArray(win.width * win.height)
        val proto = LevelGrid(win, g)
        val c = StormMotion.moved(here, Motion(1.0, 0.0, 1.0), 0.0)
        val mx = RouteMath.mercatorX(c.lon) + eastKm * 1000 / Math.cos(Math.toRadians(here.lat))
        val my = RouteMath.mercatorY(c.lat) + northKm * 1000 / Math.cos(Math.toRadians(here.lat))
        val (cx, cy) = proto.pixelOfMerc(mx, my)
        for (y in 0 until win.height) for (x in 0 until win.width) {
            val d = Math.hypot((x - cx).toDouble(), (y - cy).toDouble())
            g[y * win.width + x] = when {
                d <= radiusPx * 0.35 -> RadarIntensity.SEVERE
                d <= radiusPx * 0.7 -> RadarIntensity.HEAVY
                d <= radiusPx -> RadarIntensity.MODERATE
                else -> 0
            }.toByte()
        }
        return LevelGrid(win, g)
    }

    @Test fun estimatesEastwardMotion() {
        // Storm 40 km west moved 12 km east in 15 min = 13.3 m/s (~30 mph) toward the east.
        val m = StormMotion.estimate(grid(-52.0, 0.0), grid(-40.0, 0.0), 900.0)!!
        assertEquals(13.3, m.speedMps(here.lat), 2.5)
        assertEquals("E", StormMotion.compass(m.headingDeg))
    }

    @Test fun estimatesNortheastMotion() {
        val m = StormMotion.estimate(grid(-30.0, -30.0), grid(-22.0, -22.0), 900.0)!!
        assertTrue(StormMotion.compass(m.headingDeg) == "NE")
    }

    @Test fun noRainNoMotion() {
        val empty = LevelGrid(win, ByteArray(win.width * win.height))
        assertNull(StormMotion.estimate(empty, empty, 900.0))
        assertNull(StormMotion.estimate(grid(-52.0, 0.0), grid(-40.0, 0.0), 30.0))
    }

    @Test fun arrivalForAStationaryDriver() {
        val m = StormMotion.estimate(grid(-52.0, 0.0), grid(-40.0, 0.0), 900.0)!!
        val a = StormMotion.arrivalAt(grid(-40.0, 0.0), here, m)!!
        // Heavy ring edge is ~6 km nearer than the centre: (40-6)/13.3 m/s ≈ 42 min.
        assertEquals(42.0, a.startS / 60, 8.0)
        assertTrue(a.endS > a.startS)
        assertEquals(RadarIntensity.SEVERE, a.peak)
        // A storm moving away never arrives.
        val away = StormMotion.estimate(grid(-40.0, 0.0), grid(-52.0, 0.0), 900.0)!!
        assertNull(StormMotion.arrivalAt(grid(-52.0, 0.0), here, away))
    }

    @Test fun movingSegmentsSeeWhereTheStormWillBe() {
        // Route heads due east 80 km; the storm is 40 km north of the route's midpoint, moving south.
        val route = (0..80).map { GeoPoint(here.lat, here.lon + it * 0.0114) }
        val now = grid(40.0, 40.0)
        val m = StormMotion.estimate(grid(40.0, 52.0), now, 900.0)!!
        // Static radar: the route is dry right now.
        assertTrue(StormMotion.movingSegments(route, now, Motion(0.0, 0.0, 1.0), 25.0).isEmpty())
        // Leaving now at 25 m/s you pass the 40 km mark after ~27 min, while the storm is still ~19 km north: dry.
        assertTrue(StormMotion.movingSegments(route, now, m, 25.0).isEmpty())
        // The storm crosses the route ~50 min from now; leave ~23 min late and you meet it mid-route.
        val late = StormMotion.movingSegments(route, now, m, 25.0, departDelayS = 1400.0)
        assertTrue(late.isNotEmpty())
        assertEquals(40_000.0, (late[0].startM + late[0].endM) / 2, 8_000.0)
    }

    @Test fun bestDepartureWaitsForTheStormToPass() {
        // Storm sitting on the route start, moving north away from an eastbound route.
        val route = (0..60).map { GeoPoint(here.lat, here.lon + it * 0.0114) }
        val now = grid(5.0, 0.0, radiusPx = 12)
        val m = Motion(0.0, 15.0 / Math.cos(Math.toRadians(here.lat)), 1.0)
        val opts = StormMotion.departureOptions(route, now, m, 25.0, maxDelayS = 7200.0)
        val best = StormMotion.bestDeparture(opts)!!
        assertTrue(best in 900.0..3600.0)
    }

    @Test fun cellsAndIntercept() {
        val g = grid(-40.0, 10.0)
        val cells = StormMotion.cells(g)
        assertEquals(1, cells.size)
        assertEquals(RadarIntensity.SEVERE, cells[0].peak)
        val dKm = RouteMath.haversine(here, cells[0].center) / 1000
        assertEquals(41.2, dKm, 3.0)
        val m = Motion(15.0 / Math.cos(Math.toRadians(here.lat)), 0.0, 1.0) // 15 m/s east, toward us
        val target = StormMotion.intercept(here, cells[0].center, m)
        // Storm comes toward us, so we meet it closer than it is now.
        assertTrue(RouteMath.haversine(here, target) < RouteMath.haversine(here, cells[0].center))
        val short = StormMotion.stopShort(here, target, 3_000.0)
        assertEquals(RouteMath.haversine(here, target) - 3_000.0, RouteMath.haversine(here, short), 200.0)
    }

    @Test fun timeDimensionParsing() {
        val list = StormMotion.parseTimes("2026-11-18T14:00:00.000Z,2026-11-18T14:02:00.000Z, 2026-11-18T14:16:00Z")
        assertEquals(3, list.size)
        val (older, newer) = StormMotion.pickPair(list)!!
        assertEquals(16 * 60_000L, newer - older)
        val interval = StormMotion.parseTimes("2026-11-18T12:00:00Z/2026-11-18T14:00:00Z/PT2M")
        assertEquals(61, interval.size)
        val (o2, n2) = StormMotion.pickPair(interval)!!
        assertEquals(15.0, (n2 - o2) / 60_000.0, 1.0)
    }

    @Test fun compassAndDescribe() {
        assertEquals("N", StormMotion.compass(359.0))
        assertEquals("ENE", StormMotion.compass(67.5))
        val cell = StormCell(GeoPoint(39.75, -89.98), RadarIntensity.SEVERE, 80.0, 50)
        val text = StormMotion.describe(cell, here, Motion(14.0 / Math.cos(Math.toRadians(39.75)), 0.0, 1.0), UnitSystem.IMPERIAL)
        assertTrue(text.startsWith("Severe storm ") && text.contains(" W") && text.endsWith("moving E at 31 mph"))
    }
}
