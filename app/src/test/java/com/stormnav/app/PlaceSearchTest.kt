package com.stormnav.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchTest {
    private val decatur = GeoPoint(39.8403, -88.9548)

    private fun feature(name: String, lat: Double, lon: Double, key: String, value: String, city: String = "", state: String = "Illinois",
                        street: String = "", house: String = "", type: String = "house") =
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":{"name":"$name","osm_key":"$key","osm_value":"$value","type":"$type","city":"$city","state":"$state","street":"$street","housenumber":"$house","countrycode":"US"}}"""

    private fun fc(vararg f: String) = """{"type":"FeatureCollection","features":[${f.joinToString(",")}]}"""

    /** Fake network: answers like Photon and the Census geocoder would. */
    private val fake: (String) -> String = { raw ->
        val url = raw.lowercase()
        when {
            url.contains("overpass-api") -> """{"elements":[
                {"type":"node","id":1,"lat":39.7950,"lon":-89.6500,"tags":{"amenity":"fuel","name":"Speedway","addr:street":"Main St","addr:city":"Springfield"}},
                {"type":"way","id":2,"center":{"lat":39.8100,"lon":-89.6700},"tags":{"amenity":"fuel","brand":"Marathon"}}]}"""
            url.contains("geocoding.geo.census.gov") ->
                if (url.contains("springfield")) """{"result":{"addressMatches":[{"matchedAddress":"700 MAIN ST, SPRINGFIELD, IL, 62701","coordinates":{"x":-89.6440,"y":39.7990}}]}}"""
                else """{"result":{"addressMatches":[]}}"""
            url.contains("osm_tag=place") -> fc(
                feature("Springfield", 37.2090, -93.2923, "place", "city", type = "city", state = "Missouri"),
                feature("Springfield", 39.7990, -89.6440, "place", "city", type = "city"),
                feature("Springfield", 39.9242, -83.8088, "place", "city", type = "city", state = "Ohio"))
            url.contains("bbox=") && url.contains("q=walmart") && url.contains("lat=37.20") -> fc(
                feature("Walmart Supercenter", 37.1950, -93.3100, "shop", "supermarket", "Springfield", state = "Missouri"))
            url.contains("bbox=") && url.contains("q=walmart") && url.contains("lat=39.79") -> fc(
                feature("Walmart Supercenter", 39.7810, -89.6800, "shop", "supermarket", "Springfield", street = "Wabash Ave", house = "3000"),
                feature("Walmart Neighborhood Market", 39.8300, -89.1000, "shop", "supermarket", "Decatur"))
            url.contains("bbox=") && url.contains("q=walmart") -> fc(
                feature("Walmart Supercenter", 39.8500, -88.9300, "shop", "supermarket", "Decatur"))
            url.contains("q=springfield+walmart") -> fc(
                feature("Walmart Supercenter", 37.2200, -93.2800, "shop", "supermarket", "Springfield", state = "Missouri"),
                feature("Springfield", 39.7990, -89.6440, "place", "city", type = "city"))
            else -> fc()
        }
    }

    @Test fun splitsReadTownAndBusiness() {
        val s = QueryParse.splits("Springfield Walmart")
        assertTrue(s.contains("Walmart" to "Springfield"))
        assertEquals(listOf("walmart" to "springfield il"), QueryParse.splits("walmart in springfield il"))
        assertTrue(QueryParse.splits("walmart springfield il").contains("walmart" to "springfield il"))
        assertTrue(QueryParse.splits("Walmart").isEmpty())
    }

    @Test fun stateSplitting() {
        assertEquals("springfield" to "Illinois", QueryParse.splitState("springfield il"))
        assertEquals("Springfield" to "Missouri", QueryParse.splitState("Springfield, Missouri"))
        assertEquals("springfield" to null, QueryParse.splitState("springfield"))
    }

    @Test fun addressDetection() {
        assertTrue(QueryParse.looksLikeAddress("700 Main St"))
        assertTrue(QueryParse.looksLikeAddress("12B Oak Ln, Springfield"))
        assertTrue(!QueryParse.looksLikeAddress("Walmart"))
        assertTrue(!QueryParse.looksLikeAddress("7-Eleven"))
    }

    @Test fun matchesByNameOrCategory() {
        val walmart = PlaceResult("Walmart Supercenter", "", 0.0, 0.0, PlaceKind.POI, "supermarket")
        val gas = PlaceResult("Speedway", "", 0.0, 0.0, PlaceKind.POI, "fuel")
        assertTrue(QueryParse.matches("walmart", walmart))
        assertTrue(QueryParse.matches("gas", gas))
        assertTrue(!QueryParse.matches("walmart", gas))
    }

    @Test fun springfieldWalmartPicksTheNearbySpringfield() {
        val r = PlaceSearch(fake).full("Springfield Walmart", decatur, null)
        val best = r.first()
        assertEquals("Walmart Supercenter", best.name)
        // Illinois's Springfield (near the driver), not Missouri's.
        assertTrue(GeoMath.km(39.7990, -89.6440, best.lat, best.lon) < 10.0)
    }

    @Test fun bareAddressUsesDriversTown() {
        val r = PlaceSearch(fake).full("700 Main St", decatur, "Springfield, Illinois")
        val best = r.first()
        assertEquals(PlaceKind.ADDRESS, best.kind)
        assertEquals("700 Main St", best.name)
        assertEquals("Springfield, IL 62701", best.detail)
    }

    @Test fun bareBrandFindsNearest() {
        val r = PlaceSearch(fake).full("Walmart", decatur, null)
        assertTrue(r.isNotEmpty())
        assertTrue(GeoMath.km(decatur, r.first()) < 10.0)
    }

    @Test fun stateNamedTownBeatsNearerOne() {
        val r = PlaceSearch(fake).full("walmart in springfield mo", decatur, null)
        assertTrue(GeoMath.km(37.2090, -93.2923, r.first().lat, r.first().lon) < 10.0)
    }

    @Test fun categoriesUseOverpassNotNames() {
        assertTrue(QueryParse.category("gas") != null)
        assertTrue(QueryParse.category("gas stations near me") != null)
        assertTrue(QueryParse.category("Coffee") != null)
        assertNull(QueryParse.category("Gaslight Diner"))
        assertTrue(!QueryParse.matches("gas", PlaceResult("Gaslight Diner", "", 0.0, 0.0, PlaceKind.POI, "restaurant")))
        val r = PlaceSearch(fake).full("gas", GeoPoint(39.7990, -89.6440), null)
        assertEquals("Speedway", r.first().name)
        assertEquals("fuel", r.first().category)
        assertEquals("Marathon", r[1].name)
    }

    @Test fun nothingFound() {
        assertTrue(PlaceSearch(fake).full("zzqx", decatur, null).isEmpty())
        assertNull(PlaceSearch(fake).findTown("Atlantis", decatur))
    }

    @Test fun photonParsing() {
        val r = PlaceSearch.parsePhoton(fc(
            feature("", 39.0, -89.0, "building", "house", "Springfield", street = "Main St", house = "12"),
            feature("Speedway", 39.1, -89.1, "amenity", "fuel", "Springfield", street = "Main St", house = "500")))
        assertEquals(PlaceKind.ADDRESS, r[0].kind); assertEquals("12 Main St", r[0].name)
        assertEquals(PlaceKind.POI, r[1].kind); assertEquals("500 Main St, Springfield, Illinois", r[1].detail)
    }

    @Test fun censusTitleCase() {
        assertEquals("4521 Oak Ridge Rd NE", PlaceSearch.titleCase("4521 OAK RIDGE RD NE"))
    }
}
