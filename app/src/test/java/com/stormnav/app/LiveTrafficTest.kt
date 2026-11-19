package com.stormnav.app

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Real TomTom: the built-in key must get a traffic tile back. Skipped unless LIVE_SEARCH=1. */
class LiveTrafficTest {
    private fun code(url: String): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 30000
        try { return c.responseCode } finally { c.disconnect() }
    }

    @Test fun builtInKeyGetsTraffic() {
        assumeTrue(System.getenv("LIVE_SEARCH") == "1")
        val key = Traffic.cleanKey(BuildConfig.TOMTOM_API_KEY)
        assumeTrue(key.isNotEmpty())
        val flow = code(Traffic.probeUrl(key, 39.7990, -89.6440))
        println("TomTom flow tile (Springfield, IL z12): HTTP $flow")
        val inc = code(Traffic.incidentsUrl(key, 0).replace("{z}", "12").replace("{x}", "1028").replace("{y}", "1553"))
        println("TomTom incidents tile: HTTP $inc")
        val b = "https://api.tomtom.com/traffic/map/4/tile"
        for ((name, u) in listOf(
            "inc plain" to "$b/incidents/s3/12/1028/1553.png?key=$key",
            "inc tileSize" to "$b/incidents/s3/12/1028/1553.png?key=$key&tileSize=256",
            "inc t=-1" to "$b/incidents/s3/12/1028/1553.png?key=$key&t=-1",
            "inc s1" to "$b/incidents/s1/12/1028/1553.png?key=$key",
            "flow t=123" to "$b/flow/relative0/12/1028/1553.png?key=$key&t=123",
            "flow thickness" to "$b/flow/relative0/12/1028/1553.png?key=$key&tileSize=256&thickness=8",
            "app flow" to Traffic.flowUrl(key, 5).replace("{z}", "12").replace("{x}", "1028").replace("{y}", "1553"))) println("  $name: HTTP ${code(u)}")
        val appFlow = code(Traffic.flowUrl(key, 5).replace("{z}", "12").replace("{x}", "1028").replace("{y}", "1553"))
        assertEquals(200, flow); assertEquals(200, appFlow); assertEquals(200, inc)
    }

    @Test fun routeAndIncidents() {
        assumeTrue(System.getenv("LIVE_SEARCH") == "1")
        val key = Traffic.cleanKey(BuildConfig.TOMTOM_API_KEY)
        assumeTrue(key.isNotEmpty())
        fun body(url: String): String {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 30000
            try {
                val code = c.responseCode
                if (code != 200) error("HTTP $code: " + (c.errorStream?.bufferedReader()?.readText() ?: ""))
                return c.inputStream.bufferedReader().readText()
            } finally { c.disconnect() }
        }
        val pts = listOf(GeoPoint(39.8403, -88.9548), GeoPoint(39.7990, -89.6440)) // Decatur -> Springfield, IL
        val r = RoutingProviders.parseTomTom(body(RoutingProviders.tomtomUrl(key, pts))).first()
        println("TomTom route: ${"%.1f".format(r.distanceM / 1609.34)} mi, ${(r.durationS / 60).toInt()} min + ${(r.trafficDelayS / 60).toInt()} min traffic, ${r.steps.size} steps")
        r.steps.take(6).forEach { println("   ${it.type} ${it.modifier} ${it.name} ${it.ref} ${it.distanceM.toInt()} m") }
        org.junit.Assert.assertTrue(r.coords.size > 50 && r.steps.size > 3)
        // Incidents around Chicago (busy area so there is usually something).
        val json = body(TrafficIncidents.url(key, 41.80, -87.75, 41.95, -87.60))
        val n = org.json.JSONObject(json).optJSONArray("incidents")?.length() ?: -1
        println("TomTom incidents around Chicago: $n")
        val route = r.coords.map { GeoPoint(it[1], it[0]) }
        val on = TrafficIncidents.onRoute(json, route, RouteMath.cumulative(route), 0.0)
        println("  on the Decatur->Springfield route: ${on.size}")
        on.take(5).forEach { println("   " + TrafficIncidents.message(it, UnitSystem.IMPERIAL) + " [cat ${it.category}, mag ${it.magnitude}]") }
        org.junit.Assert.assertTrue(n >= 0)
    }
}
