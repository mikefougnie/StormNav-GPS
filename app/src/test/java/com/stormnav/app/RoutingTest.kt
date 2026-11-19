package com.stormnav.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {
    @Test fun osrmUrlAndParse() {
        val url = RoutingProviders.osrmUrl(RoutingProviders.FOSSGIS, listOf(GeoPoint(39.84, -88.95), GeoPoint(39.80, -89.64)), true)
        assertTrue(url.startsWith("https://routing.openstreetmap.de/routed-car/route/v1/driving/-88.950000,39.840000;-89.640000,39.800000?"))
        assertTrue(url.contains("alternatives=3") && url.contains("steps=true"))
        val json = """{"code":"Ok","routes":[{"duration":1800,"distance":48000,
          "geometry":{"coordinates":[[-88.95,39.84],[-89.3,39.82],[-89.64,39.80]]},
          "legs":[{"steps":[
            {"name":"I 72","ref":"I 72","distance":40000,"maneuver":{"type":"depart","modifier":"","location":[-88.95,39.84]}},
            {"name":"","ref":"IL 29","distance":8000,"maneuver":{"type":"off ramp","modifier":"right","location":[-89.3,39.82]}},
            {"name":"","distance":0,"maneuver":{"type":"arrive","location":[-89.64,39.80]}}]}]}]}"""
        val r = RoutingProviders.parseOsrm(json).single()
        assertEquals(3, r.coords.size); assertEquals(3, r.steps.size)
        assertEquals("IL 29", r.steps[1].ref); assertEquals("right", r.steps[1].modifier)
        assertEquals(1800.0, r.durationS, 0.0)
        assertTrue(RoutingProviders.parseOsrm("""{"code":"NoRoute","routes":[]}""").isEmpty())
    }

    @Test fun orsParseMergesSegments() {
        val json = """{"type":"FeatureCollection","features":[{"type":"Feature",
          "geometry":{"type":"LineString","coordinates":[[-88.95,39.84],[-89.1,39.83],[-89.3,39.82],[-89.64,39.80]]},
          "properties":{"summary":{"distance":48000,"duration":1900},"segments":[
            {"steps":[{"distance":20000,"type":11,"name":"I 72","way_points":[0,1]},{"distance":0,"type":10,"name":"-","way_points":[1,1]}]},
            {"steps":[{"distance":0,"type":11,"name":"-","way_points":[1,1]},{"distance":8000,"type":1,"name":"IL 29","way_points":[2,3]},{"distance":0,"type":10,"name":"-","way_points":[3,3]}]}]}}]}"""
        val r = RoutingProviders.parseOrs(json).single()
        assertEquals(listOf("depart", "turn", "arrive"), r.steps.map { it.type })
        assertEquals("IL 29", r.steps[1].name); assertEquals("right", r.steps[1].modifier)
        assertEquals(-89.3, r.steps[1].lon, 1e-9)
        assertEquals(1900.0, r.durationS, 0.0)
    }

    @Test fun orsBodyAndManeuvers() {
        val body = RoutingProviders.orsBody(listOf(GeoPoint(39.84, -88.95), GeoPoint(39.80, -89.64)), true)
        assertTrue(body.contains("alternative_routes") && body.contains("[-88.95,39.84]"))
        assertTrue(!RoutingProviders.orsBody(listOf(GeoPoint(1.0, 2.0), GeoPoint(3.0, 4.0), GeoPoint(5.0, 6.0)), true).contains("alternative_routes"))
        assertEquals("turn" to "sharp left", RoutingProviders.orsManeuver(2))
        assertEquals("fork" to "slight right", RoutingProviders.orsManeuver(13))
    }
}
