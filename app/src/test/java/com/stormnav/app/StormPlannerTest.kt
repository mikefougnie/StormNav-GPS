package com.stormnav.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StormPlannerTest {
    private val straightNorth = (0..400).map { GeoPoint(40.0 + it * 0.001, -90.0) } // ~44 km north
    private val cum = RouteMath.cumulative(straightNorth)

    private fun opt(min: Double, tag: String) = RouteOption(straightNorth, min * 60, 10_000.0, tag)

    @Test fun promptOnlyForSevereWithinAnHour() {
        val heavy = listOf(StormSegment(10_000.0, 14_000.0, RadarIntensity.HEAVY))
        val severe = listOf(StormSegment(10_000.0, 14_000.0, RadarIntensity.SEVERE))
        assertNull(StormPlanner.promptFor(heavy, 0.0, 27.0, nwsSevere = false))
        assertTrue(StormPlanner.promptFor(heavy, 0.0, 27.0, nwsSevere = true) != null)
        assertTrue(StormPlanner.promptFor(severe, 0.0, 27.0, nwsSevere = false) != null)
        // Behind us: nothing to ask about.
        assertNull(StormPlanner.promptFor(severe, 15_000.0, 27.0, nwsSevere = false))
        // 200 km away at 27 m/s is ~2 h: too early to bother the driver.
        assertNull(StormPlanner.promptFor(listOf(StormSegment(200_000.0, 210_000.0, RadarIntensity.SEVERE)), 0.0, 27.0, false))
    }

    @Test fun exposureWeighsSevereMost() {
        val moderate = StormPlanner.exposure(listOf(StormSegment(0.0, 10_000.0, RadarIntensity.MODERATE)), 0.0)
        val severe = StormPlanner.exposure(listOf(StormSegment(0.0, 2_000.0, RadarIntensity.SEVERE)), 0.0)
        assertEquals(10.0, moderate, 0.01)
        assertTrue(severe > moderate)
        assertEquals(0.0, StormPlanner.exposure(listOf(StormSegment(0.0, 5_000.0, RadarIntensity.SEVERE)), 6_000.0), 0.0)
    }

    @Test fun detourViasSitEitherSideOfTheStorm() {
        val storm = StormSegment(20_000.0, 24_000.0, RadarIntensity.SEVERE)
        val vias = StormPlanner.detourVias(straightNorth, cum, storm)
        assertEquals(2, vias.size)
        // Route runs north along -90.0, so one via is east and one west, at the storm's latitude.
        assertTrue(vias.any { it.lon > -89.9 } && vias.any { it.lon < -90.1 })
        vias.forEach { assertEquals(40.0 + 22_000.0 / 111_320.0, it.lat, 0.01) }
    }

    @Test fun pickDetourNeedsARealImprovement() {
        val base = ScoredRoute(opt(60.0, "main"), listOf(StormSegment(10_000.0, 16_000.0, RadarIntensity.SEVERE)))
        val dry = ScoredRoute(opt(72.0, "east"), emptyList())
        val tooLong = ScoredRoute(opt(140.0, "far"), emptyList())
        val barelyBetter = ScoredRoute(opt(62.0, "alt"), listOf(StormSegment(10_000.0, 15_000.0, RadarIntensity.SEVERE)))
        val choice = StormPlanner.pickDetour(base, listOf(tooLong, barelyBetter, dry))!!
        assertEquals("east", choice.route.option.tag)
        assertEquals(12 * 60.0, choice.extraS, 0.1)
        assertNull(StormPlanner.pickDetour(base, listOf(tooLong, barelyBetter)))
        // Heavy instead of severe counts as better even if long.
        val softer = ScoredRoute(opt(70.0, "soft"), listOf(StormSegment(10_000.0, 16_000.0, RadarIntensity.HEAVY)))
        assertEquals("soft", StormPlanner.pickDetour(base, listOf(softer))!!.route.option.tag)
    }

    @Test fun waitStopsAreBeforeTheStorm() {
        fun at(km: Double, offEastKm: Double, name: String) =
            PlaceResult(name, "", 40.0 + km * 1000 / 111_320.0, -90.0 + offEastKm * 1000 / (111_320.0 * 0.766), PlaceKind.POI, "fuel")
        val places = listOf(at(2.0, 0.3, "Too close behind"), at(5.0, 0.4, "Speedway"), at(12.0, 1.0, "Hotel"),
            at(18.5, 0.2, "Too near storm"), at(9.0, 6.0, "Far off route"), at(30.0, 0.1, "Past storm"))
        val stops = StormPlanner.waitStops(places, straightNorth, cum, progressM = 2_000.0, stormStartM = 20_000.0)
        assertEquals(listOf("Speedway", "Hotel"), stops.map { it.place.name })
        assertTrue(stops[0].alongM < stops[1].alongM)
    }

    @Test fun waitStopsMixCategories() {
        fun at(km: Double, name: String, cat: String) =
            PlaceResult(name, "", 40.0 + km * 1000 / 111_320.0, -90.0, PlaceKind.POI, cat)
        val food = (0 until 20).map { at(1.0 + it * 0.05, "Diner $it", "restaurant") }
        val places = food + at(8.0, "Speedway", "fuel") + at(12.0, "Motel 6", "motel") + at(14.0, "Rest Area", "rest_area")
        val stops = StormPlanner.waitStops(places, straightNorth, cum, 0.0, 20_000.0)
        assertTrue(stops.count { it.place.category == "restaurant" } <= 1) // all within 1.5 km of each other
        assertTrue(stops.any { it.place.name == "Speedway" } && stops.any { it.place.name == "Motel 6" } && stops.any { it.place.name == "Rest Area" })
    }

    @Test fun corridorIsCapped() {
        val c = StormPlanner.corridor(straightNorth, cum, 0.0, 40_000.0)
        assertTrue(c.size <= 61)
        assertTrue(StormPlanner.corridor(straightNorth, cum, 5_000.0, 6_000.0).size < 20)
    }

    @Test fun clearAheadIgnoresLightAndFarStorms() {
        assertTrue(StormPlanner.clearAhead(emptyList()))
        assertTrue(StormPlanner.clearAhead(listOf(StormSegment(2_000.0, 4_000.0, RadarIntensity.MODERATE))))
        assertTrue(StormPlanner.clearAhead(listOf(StormSegment(90_000.0, 95_000.0, RadarIntensity.SEVERE))))
        assertTrue(!StormPlanner.clearAhead(listOf(StormSegment(8_000.0, 12_000.0, RadarIntensity.HEAVY))))
    }
}
