package com.stormnav.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficRouteTest {
    // A straight route due east along latitude 38.2, 0 → ~8.75 km.
    private val route = (0..100).map { GeoPoint(39.8, -89.7 + it * 0.001) }
    private val cum = RouteMath.cumulative(route)

    private fun incident(id: String, cat: Int, coords: String, delay: Int = 0, mag: Int = 1, road: String = "I-80") = """
        {"type":"Feature","geometry":{"type":"LineString","coordinates":$coords},
         "properties":{"id":"$id","iconCategory":$cat,"magnitudeOfDelay":$mag,"delay":$delay,
           "events":[{"description":"x","code":1}],"roadNumbers":["$road"]}}"""

    @Test fun matchesIncidentsAheadOnlyAndSameDirection() {
        val json = """{"incidents":[
            ${incident("ahead", TrafficIncidents.ACCIDENT, "[[-89.67,39.8],[-89.668,39.8]]", delay = 420)},
            ${incident("behind", TrafficIncidents.ACCIDENT, "[[-89.699,39.8],[-89.698,39.8]]")},
            ${incident("opposite", TrafficIncidents.JAM, "[[-89.65,39.8],[-89.655,39.8]]", delay = 600, mag = 3)},
            ${incident("offroad", TrafficIncidents.ACCIDENT, "[[-89.67,39.81],[-89.668,39.81]]")},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[-89.66,39.8]},
             "properties":{"id":"pt","iconCategory":8,"magnitudeOfDelay":4}}
        ]}"""
        val progress = cum[10] // ~875 m along
        val got = TrafficIncidents.onRoute(json, route, cum, progress)
        assertEquals(listOf("ahead", "pt"), got.map { it.id })
        val crash = got[0]
        assertTrue(crash.aheadM in 1700.0..1800.0)
        assertEquals(420.0, TrafficIncidents.delayAhead(got, progress), 0.1)
        assertEquals(0.0, TrafficIncidents.delayAhead(got, cum[40]), 0.1)
        assertTrue(TrafficIncidents.worthSaying(crash))
        val msg = TrafficIncidents.message(crash, UnitSystem.IMPERIAL)
        assertTrue(msg, msg.startsWith("Crash ahead in") && msg.contains("on I-80") && msg.contains("About 7 minutes"))
        assertTrue(TrafficIncidents.message(got[1], UnitSystem.IMPERIAL).startsWith("Road closed"))
    }

    @Test fun minorJamsStayQuiet() {
        val x = RouteIncident("j", TrafficIncidents.JAM, 1, "", "", 60.0, 500.0)
        assertFalse(TrafficIncidents.worthSaying(x))
        assertTrue(TrafficIncidents.worthSaying(x.copy(magnitude = 3)))
    }

    @Test fun boxAheadCoversNextStretch() {
        val b = TrafficIncidents.boxAhead(route, cum, cum[50])!!
        assertTrue(b[1] < -89.65 && b[3] > -89.6 + 0.004)
        assertTrue(TrafficIncidents.url("K", b[0], b[1], b[2], b[3]).contains("bbox=-89.65"))
    }

    @Test fun parsesTomTomRoute() {
        val json = """{"routes":[{"summary":{"lengthInMeters":3000,"travelTimeInSeconds":400,"trafficDelayInSeconds":90,"noTrafficTravelTimeInSeconds":300},
          "legs":[{"points":[{"latitude":39.8,"longitude":-89.7},{"latitude":39.8,"longitude":-89.69},{"latitude":39.81,"longitude":-89.69}]}],
          "guidance":{"instructions":[
            {"routeOffsetInMeters":0,"point":{"latitude":39.8,"longitude":-89.7},"maneuver":"DEPART","street":"Main Street"},
            {"routeOffsetInMeters":870,"point":{"latitude":39.8,"longitude":-89.69},"maneuver":"TURN_LEFT","street":"Oak St","roadNumbers":["IL-29"]},
            {"routeOffsetInMeters":3000,"point":{"latitude":39.81,"longitude":-89.69},"maneuver":"ARRIVE"}]}}]}"""
        val r = RoutingProviders.parseTomTom(json).single()
        assertEquals(3, r.coords.size)
        assertEquals(300.0, r.durationS, 0.1)
        assertEquals(100.0, r.trafficDelayS, 0.1)
        assertEquals(listOf("depart", "turn", "arrive"), r.steps.map { it.type })
        assertEquals("left", r.steps[1].modifier)
        assertEquals("IL-29", r.steps[1].ref)
        assertEquals(2130.0, r.steps[1].distanceM, 0.1)
        assertTrue(RoutingProviders.tomtomUrl("K", listOf(GeoPoint(39.84, -88.95), GeoPoint(39.80, -89.64)))
            .contains("calculateRoute/39.840000,-88.950000:39.800000,-89.640000/json?key=K&traffic=true"))
    }

    @Test fun gateRestsAfterRefusal() {
        TomTomGate.restUntil = 0
        assertTrue(TomTomGate.open(1000))
        TomTomGate.record(200, 1000); assertTrue(TomTomGate.open(1000))
        TomTomGate.record(403, 1000); assertFalse(TomTomGate.open(1000 + 60_000))
        assertTrue(TomTomGate.open(1000 + Traffic.RETRY_MS))
        TomTomGate.restUntil = 0
    }
}
