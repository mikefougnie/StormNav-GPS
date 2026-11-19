package com.stormnav.app

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hits the real Photon and Census services. Skipped unless LIVE_SEARCH=1, so normal
 * builds never depend on the network; CI runs it as a separate, non-blocking step.
 */
class LiveSearchTest {
    private val http: (String) -> String = { url ->
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "StormNav-CI/0.5 (github.com/mikefougnie/StormNav-GPS)")
        try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }
    private val decatur = GeoPoint(39.8403, -88.9548)
    private val springfieldIl = GeoPoint(39.7990, -89.6440)

    private fun show(q: String, r: List<PlaceResult>) {
        println("LIVE \"$q\" -> ${r.size} results")
        r.take(5).forEach { println("   ${"%.1f".format(it.score)}  ${it.kind}  ${it.name} | ${it.detail} | ${it.category} | ${"%.4f,%.4f".format(it.lat, it.lon)}") }
    }

    @Test fun springfieldWalmart() {
        assumeTrue(System.getenv("LIVE_SEARCH") == "1")
        val r = PlaceSearch(http).full("Springfield Walmart", decatur, null); show("Springfield Walmart", r)
        assertTrue(r.first().name.contains("Walmart", true))
        assertTrue(GeoMath.km(springfieldIl, r.first()) < 15.0)
    }

    @Test fun walmartInSpringfieldMo() {
        assumeTrue(System.getenv("LIVE_SEARCH") == "1")
        val r = PlaceSearch(http).full("walmart in springfield mo", decatur, null); show("walmart in springfield mo", r)
        assertTrue(r.first().name.contains("Walmart", true))
        assertTrue(GeoMath.km(37.2090, -93.2923, r.first().lat, r.first().lon) < 20.0)
    }

    @Test fun fullAddress() {
        assumeTrue(System.getenv("LIVE_SEARCH") == "1")
        val r = PlaceSearch(http).full("1600 Pennsylvania Ave NW, Washington, DC 20500", decatur, null); show("White House", r)
        assertTrue(GeoMath.km(38.8977, -77.0365, r.first().lat, r.first().lon) < 1.0)
    }

    @Test fun bareAddressWithTown() {
        assumeTrue(System.getenv("LIVE_SEARCH") == "1")
        val town = PlaceSearch(http).reverseTown(springfieldIl.lat, springfieldIl.lon)
        println("LIVE reverseTown -> $town")
        val r = PlaceSearch(http).full("401 S 2nd St", springfieldIl, town ?: "Springfield, Illinois"); show("401 S 2nd St", r)
        assertTrue(r.isNotEmpty())
        assertTrue(GeoMath.km(springfieldIl, r.first()) < 10.0)
    }

    @Test fun nearestGas() {
        assumeTrue(System.getenv("LIVE_SEARCH") == "1")
        val r = PlaceSearch(http).full("gas", springfieldIl, null); show("gas", r)
        // Overpass (shared, rate-limited) may be unavailable from CI; whatever comes back must be fuel.
        assertTrue(r.all { it.category == "fuel" })
        if (r.size >= 3) assertTrue(GeoMath.km(springfieldIl, r.first()) < 12.0)
    }

    @Test fun coffeeInTown() {
        assumeTrue(System.getenv("LIVE_SEARCH") == "1")
        val r = PlaceSearch(http).full("coffee in springfield il", decatur, null); show("coffee in springfield il", r)
        assertTrue(r.isNotEmpty())
        assertTrue(GeoMath.km(springfieldIl, r.first()) < 15.0)
    }

    @Test fun waitStopsAlongI72() {
        assumeTrue(System.getenv("LIVE_SEARCH") == "1")
        // I-72 from Decatur toward Springfield, IL, sampled roughly every 3.5 km.
        val line = (0..16).map { GeoPoint(39.83 - it * 0.0019, -88.98 - it * 0.039) }
        val ps = PlaceSearch(http)
        val r = ps.waitPlacesAlong(line)
        show("wait stops along I-72", r)
        println("LIVE overpass issue: ${ps.lastOverpassIssue}")
        val cum = RouteMath.cumulative(line)
        val stops = StormPlanner.waitStops(r, line, cum, 0.0, RouteMath.totalLength(line) + 3_000.0)
        println("LIVE waitStops kept ${stops.size}: " + stops.take(5).joinToString { "${it.place.name}@${(it.alongM / 1000).toInt()}km" })
    }

    @Test fun parkingAddress() {
        assumeTrue(System.getenv("LIVE_SEARCH") == "1")
        val a = PlaceSearch(http).reverseAddress(39.7983, -89.6544) // Illinois State Capitol, Springfield
        println("LIVE reverseAddress -> $a")
        assertTrue(a != null && a.contains("Springfield"))
    }

    @Test fun stationConditions() {
        assumeTrue(System.getenv("LIVE_SEARCH") == "1")
        val pt = org.json.JSONObject(http("https://api.weather.gov/points/39.7990,-89.6440"))
        val stations = org.json.JSONObject(http(pt.getJSONObject("properties").getString("observationStations")))
        val props = stations.getJSONArray("features").getJSONObject(0).getJSONObject("properties")
        val id = props.getString("stationIdentifier")
        val o = org.json.JSONObject(http("https://api.weather.gov/stations/$id/observations/latest")).getJSONObject("properties")
        fun v(n: String) = o.optJSONObject(n)?.let { if (it.isNull("value")) null else "${it.optDouble("value")} ${it.optString("unitCode")}" }
        println("LIVE station $id (${props.optString("name")}) at ${o.optString("timestamp")}: '${o.optString("textDescription")}' vis=${v("visibility")} wind=${v("windSpeed")} gust=${v("windGust")} temp=${v("temperature")}")
        val vis = o.optJSONObject("visibility")?.let { if (it.isNull("value")) null else it.optDouble("value") }
        println("LIVE conditions: " + ConditionRules.fromObservation(o.optString("textDescription"), vis, null, null, null, UnitSystem.IMPERIAL))
        val alerts = org.json.JSONObject(http("https://api.weather.gov/alerts/active?point=39.7990,-89.6440")).optJSONArray("features")
        println("LIVE alerts here: ${alerts?.length()}")
        assertTrue(id.isNotBlank())
    }

    @Test fun fossgisRoute() {
        assumeTrue(System.getenv("LIVE_SEARCH") == "1")
        val url = RoutingProviders.osrmUrl(RoutingProviders.FOSSGIS, listOf(decatur, springfieldIl), true)
        val routes = RoutingProviders.parseOsrm(http(url))
        println("LIVE FOSSGIS routes: ${routes.size}; first ${"%.1f".format(routes.firstOrNull()?.distanceM?.div(1609.344) ?: 0.0)} mi, " +
            "${"%.0f".format((routes.firstOrNull()?.durationS ?: 0.0) / 60)} min, ${routes.firstOrNull()?.steps?.size} steps: " +
            routes.firstOrNull()?.steps?.take(6)?.joinToString { "${it.type}/${it.modifier}/${it.name.ifBlank { it.ref }}" })
        assertTrue(routes.isNotEmpty() && routes[0].steps.size > 3)
    }

    @Test fun townLookupDebug() {
        assumeTrue(System.getenv("LIVE_SEARCH") == "1")
        val ps = PlaceSearch(http)
        println("LIVE findTown(springfield mo) -> " + ps.findTown("springfield mo", decatur))
        println("LIVE findTown(Springfield) -> " + ps.findTown("Springfield", decatur))
    }
}
