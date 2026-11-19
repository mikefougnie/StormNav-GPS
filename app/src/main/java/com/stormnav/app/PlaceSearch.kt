package com.stormnav.app

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Destination search that behaves more like Waze than a plain geocoder:
 *  - street addresses go to the US Census geocoder, which covers nearly every US address
 *    (including rural ones missing from OpenStreetMap), filling in the driver's town if
 *    they typed only "123 Main St";
 *  - "Springfield Walmart", "walmart in springfield il" find the town nearest the driver,
 *    then the business inside it;
 *  - a bare "Walmart" or "gas" returns the nearest matches;
 *  - everything else goes to Photon (OpenStreetMap search) biased toward the driver.
 * All network access goes through the injected [http] function so this is unit-testable.
 */

enum class PlaceKind { ADDRESS, POI, PLACE, STREET, RECENT, SAVED, PIN }

data class PlaceResult(
    val name: String,
    val detail: String,
    val lat: Double,
    val lon: Double,
    val kind: PlaceKind,
    val category: String = "",
    val score: Double = 0.0
) {
    val label: String get() = if (detail.isBlank()) name else "$name, ${detail.substringBefore(",")}"
}

object GeoMath {
    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6371.0 * asin(sqrt(min(1.0, a)))
    }
    fun km(a: GeoPoint, b: PlaceResult) = km(a.lat, a.lon, b.lat, b.lon)
}

object QueryParse {
    private val connector = Regex("""^(.+?)\s+(?:in|near|at|by|around)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val address = Regex("""^\d+[A-Za-z]?(-\d+)?\s+\S+""")

    val STATES: Map<String, String> = mapOf(
        "al" to "Alabama", "ak" to "Alaska", "az" to "Arizona", "ar" to "Arkansas", "ca" to "California",
        "co" to "Colorado", "ct" to "Connecticut", "de" to "Delaware", "fl" to "Florida", "ga" to "Georgia",
        "hi" to "Hawaii", "id" to "Idaho", "il" to "Illinois", "in" to "Indiana", "ia" to "Iowa",
        "ks" to "Kansas", "ky" to "Kentucky", "la" to "Louisiana", "me" to "Maine", "md" to "Maryland",
        "ma" to "Massachusetts", "mi" to "Michigan", "mn" to "Minnesota", "ms" to "Mississippi", "mo" to "Missouri",
        "mt" to "Montana", "ne" to "Nebraska", "nv" to "Nevada", "nh" to "New Hampshire", "nj" to "New Jersey",
        "nm" to "New Mexico", "ny" to "New York", "nc" to "North Carolina", "nd" to "North Dakota", "oh" to "Ohio",
        "ok" to "Oklahoma", "or" to "Oregon", "pa" to "Pennsylvania", "ri" to "Rhode Island", "sc" to "South Carolina",
        "sd" to "South Dakota", "tn" to "Tennessee", "tx" to "Texas", "ut" to "Utah", "vt" to "Vermont",
        "va" to "Virginia", "wa" to "Washington", "wv" to "West Virginia", "wi" to "Wisconsin", "wy" to "Wyoming",
        "dc" to "District of Columbia"
    )

    /** Words that describe a kind of place; matched against names and OSM categories. */
    private val synonyms = mapOf(
        "gas" to listOf("fuel"), "station" to listOf("fuel"), "food" to listOf("restaurant", "fast_food"),
        "coffee" to listOf("cafe"), "grocery" to listOf("supermarket"), "groceries" to listOf("supermarket"),
        "hotel" to listOf("hotel", "motel"), "motel" to listOf("motel", "hotel"), "hospital" to listOf("hospital"),
        "pharmacy" to listOf("pharmacy", "chemist"), "restaurant" to listOf("restaurant", "fast_food"),
        "bank" to listOf("bank"), "church" to listOf("place_of_worship")
    )

    fun clean(q: String) = q.trim().replace(Regex("\\s+"), " ")

    fun looksLikeAddress(q: String) = address.containsMatchIn(clean(q))

    fun tokens(s: String): List<String> =
        s.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }

    /**
     * Ways to read a query as (what, where). "walmart in springfield il" has one reading;
     * "Springfield Walmart" tries the town first, then last, with 1–3 word town names.
     */
    fun splits(q: String): List<Pair<String, String>> {
        val c = clean(q)
        connector.find(c)?.let { return listOf(it.groupValues[1].trim() to it.groupValues[2].trim()) }
        val w = c.split(" ")
        if (w.size < 2) return emptyList()
        val out = LinkedHashSet<Pair<String, String>>()
        for (k in 1..min(3, w.size - 1)) {
            // A trailing state code belongs with the town: "walmart springfield il".
            val tail = w.takeLast(k)
            if (!(k == 1 && STATES.containsKey(tail.last().lowercase(Locale.US)))) {
                out += w.dropLast(k).joinToString(" ") to tail.joinToString(" ")
            }
            out += w.drop(k).joinToString(" ") to w.take(k).joinToString(" ")
        }
        return out.toList()
    }

    /** "springfield il" -> ("springfield", "Illinois"); "springfield" -> ("springfield", null). */
    fun splitState(where: String): Pair<String, String?> {
        val w = clean(where).trimEnd(',').split(" ")
        if (w.size >= 2) {
            val last = w.last().lowercase(Locale.US).trim(',')
            STATES[last]?.let { return w.dropLast(1).joinToString(" ").trimEnd(',') to it }
            STATES.values.firstOrNull { clean(where).endsWith(it, ignoreCase = true) }?.let {
                return clean(where).dropLast(it.length).trim().trimEnd(',') to it
            }
        }
        return clean(where) to null
    }

    /** Word-level containment, so "gas" doesn't match "Gaslight" (long words may match a prefix). */
    fun hasWord(hay: List<String>, t: String) = hay.any { it == t || (t.length >= 5 && it.startsWith(t)) }

    /** Does a result look like what the driver asked for ("walmart", "gas")? */
    fun matches(what: String, r: PlaceResult): Boolean {
        val hay = tokens(r.name + " " + r.category.replace('_', ' ')) + r.category.lowercase(Locale.US)
        val toks = tokens(what).filterNot { it in STOP }
        return toks.isNotEmpty() && toks.all { t -> hasWord(hay, t) || synonyms[t]?.any { it in hay } == true }
    }

    private val STOP = setOf("the", "store", "near", "me", "in", "at", "by")

    /**
     * Kinds of place the driver can ask for by type rather than name. Values are Overpass
     * tag filters; OpenStreetMap name search can't find "gas" stations called "Speedway".
     */
    private val CATEGORIES: Map<String, String> = mapOf(
        "gas" to """["amenity"="fuel"]""", "gas station" to """["amenity"="fuel"]""", "fuel" to """["amenity"="fuel"]""",
        "food" to """["amenity"~"^(restaurant|fast_food)$"]""", "restaurant" to """["amenity"~"^(restaurant|fast_food)$"]""",
        "fast food" to """["amenity"="fast_food"]""", "coffee" to """["amenity"="cafe"]""", "cafe" to """["amenity"="cafe"]""",
        "hotel" to """["tourism"~"^(hotel|motel)$"]""", "motel" to """["tourism"~"^(hotel|motel)$"]""", "lodging" to """["tourism"~"^(hotel|motel)$"]""",
        "hospital" to """["amenity"="hospital"]""", "pharmacy" to """["amenity"="pharmacy"]""",
        "grocery" to """["shop"="supermarket"]""", "grocery store" to """["shop"="supermarket"]""", "groceries" to """["shop"="supermarket"]""", "supermarket" to """["shop"="supermarket"]""",
        "bank" to """["amenity"="bank"]""", "atm" to """["amenity"="atm"]""", "parking" to """["amenity"="parking"]""",
        "rest area" to """["highway"="rest_area"]""", "rest stop" to """["highway"="rest_area"]""",
        "charging" to """["amenity"="charging_station"]""", "ev charging" to """["amenity"="charging_station"]""", "charging station" to """["amenity"="charging_station"]""",
        "truck stop" to """["amenity"="fuel"]["hgv"="yes"]""", "police" to """["amenity"="police"]""", "church" to """["amenity"="place_of_worship"]"""
    )

    /** Overpass filter for "gas", "gas stations", "nearest gas station near me"; null if not a category. */
    fun category(what: String): String? {
        var w = tokens(what).filterNot { it in STOP || it == "nearest" || it == "closest" }.joinToString(" ")
        CATEGORIES[w]?.let { return it }
        w = w.split(" ").joinToString(" ") { if (it.length > 3 && it.endsWith("s")) it.dropLast(1) else it }
        return CATEGORIES[w]
    }
}

object PlaceRanker {
    fun rank(results: List<PlaceResult>, q: String, bias: GeoPoint?): List<PlaceResult> {
        val toks = QueryParse.tokens(q)
        val isAddr = QueryParse.looksLikeAddress(q)
        val scored = results.map { r ->
            val hay = QueryParse.tokens(r.name + " " + r.detail + " " + r.category.replace('_', ' '))
            val text = if (toks.isEmpty()) 0.0 else toks.count { QueryParse.hasWord(hay, it) }.toDouble() / toks.size
            val dist = bias?.let { ln(1.0 + GeoMath.km(it, r)) * 3.0 } ?: 0.0
            val kind = when (r.kind) {
                PlaceKind.ADDRESS -> if (isAddr) 15.0 else 0.0
                PlaceKind.STREET -> if (isAddr) 5.0 else 0.0
                PlaceKind.POI -> 3.0
                PlaceKind.PLACE -> 2.0
                else -> 0.0
            }
            r.copy(score = r.score + text * 20.0 + kind - dist)
        }
        val kept = mutableListOf<PlaceResult>()
        for (r in scored.sortedByDescending { it.score }) {
            val dup = kept.any { k ->
                GeoMath.km(k.lat, k.lon, r.lat, r.lon) < 0.15 &&
                    (k.name.equals(r.name, true) || k.kind == PlaceKind.ADDRESS || r.kind == PlaceKind.ADDRESS)
            }
            if (!dup) kept += r
        }
        return kept
    }
}

class PlaceSearch(private val http: (String) -> String) {
    companion object {
        const val PHOTON = "https://photon.komoot.io/api/"
        const val PHOTON_REVERSE = "https://photon.komoot.io/reverse"
        /** Public Overpass servers, tried in order (the main one rate-limits busy shared IPs). */
        val OVERPASS_HOSTS = listOf(
            "https://overpass-api.de/api/interpreter?data=",
            "https://overpass.private.coffee/api/interpreter?data=",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter?data="
        )
        const val CENSUS = "https://geocoding.geo.census.gov/geocoder/locations/onelineaddress?benchmark=Public_AR_Current&format=json&address="
        private val TOWN_TYPES = setOf("city", "town", "village", "hamlet", "suburb", "locality", "neighbourhood", "isolated_dwelling")

        private fun s(o: JSONObject, k: String): String = o.optString(k, "").let { if (it == "null") "" else it.trim() }

        fun parsePhoton(json: String): List<PlaceResult> {
            val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
            val out = mutableListOf<PlaceResult>()
            for (i in 0 until features.length()) {
                val f = features.optJSONObject(i) ?: continue
                val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                val p = f.optJSONObject("properties") ?: JSONObject()
                val name = s(p, "name")
                val streetLine = listOf(s(p, "housenumber"), s(p, "street")).filter { it.isNotBlank() }.joinToString(" ")
                val city = s(p, "city").ifBlank { s(p, "town") }.ifBlank { s(p, "village") }.ifBlank { s(p, "county") }
                val state = s(p, "state")
                val key = s(p, "osm_key"); val value = s(p, "osm_value"); val type = s(p, "type")
                val kind = when {
                    key == "place" || (type in TOWN_TYPES && streetLine.isBlank()) -> PlaceKind.PLACE
                    name.isBlank() && streetLine.isNotBlank() -> PlaceKind.ADDRESS
                    type == "street" || key == "highway" -> PlaceKind.STREET
                    else -> PlaceKind.POI
                }
                val title = name.ifBlank { streetLine }
                if (title.isBlank()) continue
                val detail = listOf(if (name.isNotBlank()) streetLine else "", if (city != title) city else "", state)
                    .filter { it.isNotBlank() }.joinToString(", ")
                out += PlaceResult(title, detail, c.getDouble(1), c.getDouble(0), kind, value)
            }
            return out
        }

        fun parseOverpass(json: String): List<PlaceResult> {
            val els = JSONObject(json).optJSONArray("elements") ?: return emptyList()
            val out = mutableListOf<PlaceResult>()
            for (i in 0 until els.length()) {
                val e = els.optJSONObject(i) ?: continue
                val center = e.optJSONObject("center")
                val lat = if (e.has("lat")) e.getDouble("lat") else center?.optDouble("lat") ?: continue
                val lon = if (e.has("lon")) e.getDouble("lon") else center?.optDouble("lon") ?: continue
                val t = e.optJSONObject("tags") ?: JSONObject()
                val cat = listOf("amenity", "shop", "tourism", "highway").map { s(t, it) }.firstOrNull { it.isNotBlank() } ?: ""
                val name = s(t, "name").ifBlank { s(t, "brand") }.ifBlank { cat.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.US) } }
                val street = listOf(s(t, "addr:housenumber"), s(t, "addr:street")).filter { it.isNotBlank() }.joinToString(" ")
                val detail = listOf(street, s(t, "addr:city")).filter { it.isNotBlank() }.joinToString(", ")
                out += PlaceResult(name, detail, lat, lon, PlaceKind.POI, cat)
            }
            return out
        }

        fun parseCensus(json: String): List<PlaceResult> {
            val matches = JSONObject(json).optJSONObject("result")?.optJSONArray("addressMatches") ?: return emptyList()
            val out = mutableListOf<PlaceResult>()
            for (i in 0 until matches.length()) {
                val m = matches.optJSONObject(i) ?: continue
                val coords = m.optJSONObject("coordinates") ?: continue
                val full = s(m, "matchedAddress")
                val street = full.substringBefore(",").trim()
                val rest = full.substringAfter(",", "").trim()
                out += PlaceResult(titleCase(street), titleCaseCity(rest), coords.getDouble("y"), coords.getDouble("x"), PlaceKind.ADDRESS, "address")
            }
            return out
        }

        /** "4521 OAK RIDGE RD" -> "4521 Oak Ridge Rd"; keeps NE/SW style directions upper-case. */
        fun titleCase(s: String) = s.split(" ").joinToString(" ") { w ->
            if (w.length <= 2 && w.all { it.isLetter() } && w.uppercase() in setOf("N", "S", "E", "W", "NE", "NW", "SE", "SW")) w.uppercase()
            else w.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
        }

        /** "SPRINGFIELD, IL, 62701" -> "Springfield, IL 62701". */
        fun titleCaseCity(s: String): String {
            val parts = s.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (parts.isEmpty()) return ""
            val city = titleCase(parts[0])
            return listOf(city, parts.drop(1).joinToString(" ")).filter { it.isNotBlank() }.joinToString(", ")
        }

        fun bboxAround(lat: Double, lon: Double, deg: Double) =
            String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", lon - deg, lat - deg, lon + deg, lat + deg)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun photon(q: String, bias: GeoPoint?, limit: Int = 10, bbox: String? = null, placesOnly: Boolean = false): List<PlaceResult> {
        val url = buildString {
            append(PHOTON).append("?q=").append(enc(q)).append("&limit=").append(limit).append("&lang=en")
            if (bias != null) append(String.format(Locale.US, "&lat=%.5f&lon=%.5f", bias.lat, bias.lon))
            if (bbox != null) append("&bbox=").append(bbox)
            if (placesOnly) append("&osm_tag=place")
        }
        return runCatching { parsePhoton(http(url)) }.getOrDefault(emptyList())
    }

    fun census(q: String): List<PlaceResult> = runCatching { parseCensus(http(CENSUS + enc(q))) }.getOrDefault(emptyList())

    /** "Springfield, Illinois" for the driver's position, used to complete bare street addresses. */
    fun reverseTown(lat: Double, lon: Double): String? = runCatching {
        val url = String.format(Locale.US, "%s?lat=%.5f&lon=%.5f&lang=en", PHOTON_REVERSE, lat, lon)
        val p = JSONObject(http(url)).getJSONArray("features").getJSONObject(0).getJSONObject("properties")
        val city = s(p, "city").ifBlank { s(p, "town") }.ifBlank { s(p, "village") }
        val state = s(p, "state")
        if (city.isBlank() || state.isBlank()) null else "$city, $state"
    }.getOrNull()

    /** "123 Main St, Springfield" (or a place name) for a point; null if unknown. */
    fun reverseAddress(lat: Double, lon: Double): String? = runCatching {
        val url = String.format(Locale.US, "%s?lat=%.6f&lon=%.6f&lang=en", PHOTON_REVERSE, lat, lon)
        val p = JSONObject(http(url)).getJSONArray("features").getJSONObject(0).getJSONObject("properties")
        val street = listOf(s(p, "housenumber"), s(p, "street")).filter { it.isNotBlank() }.joinToString(" ")
        val first = street.ifBlank { s(p, "name") }
        val city = s(p, "city").ifBlank { s(p, "town") }.ifBlank { s(p, "village") }
        listOf(first, city).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
    }.getOrNull()

    /** The town called [where] that is nearest the driver (honouring a state if given). */
    fun findTown(where: String, bias: GeoPoint?): PlaceResult? {
        val (name, state) = QueryParse.splitState(where)
        if (name.isBlank()) return null
        fun pick(list: List<PlaceResult>): PlaceResult? {
            val towns = list.filter { it.kind == PlaceKind.PLACE && it.name.equals(name, ignoreCase = true) }
                .filter { state == null || it.detail.contains(state, ignoreCase = true) }
            return if (bias == null) towns.firstOrNull() else towns.minByOrNull { GeoMath.km(bias, it) }
        }
        // With a state, don't bias toward the driver: a nearer same-named town would crowd it out.
        return if (state != null) {
            pick(photon("$name, $state", null, 15, placesOnly = true)) ?: pick(photon(name, null, 25, placesOnly = true))
        } else {
            pick(photon(name, bias, 15, placesOnly = true))
        }
    }

    /** Places of a type (fuel, cafe, …) within [radiusM] of a point, from OpenStreetMap via Overpass. */
    fun nearbyCategory(filter: String, at: GeoPoint, radiusM: Int): List<PlaceResult> =
        overpass(String.format(Locale.US, "[out:json][timeout:20];nwr%s(around:%d,%.5f,%.5f);out center 40;", filter, radiusM, at.lat, at.lon))

    /**
     * Places to wait out a storm within [radiusM] of a stretch of route: gas stations,
     * restaurants and cafés, hotels and motels, rest areas.
     */
    fun waitPlacesAlong(line: List<GeoPoint>, radiusM: Int = 1500): List<PlaceResult> {
        if (line.isEmpty()) return emptyList()
        val coords = line.joinToString(",") { String.format(Locale.US, "%.5f,%.5f", it.lat, it.lon) }
        val around = "(around:$radiusM,$coords)"
        val ql = "[out:json][timeout:25];(" +
            "nwr[\"amenity\"~\"^(fuel|restaurant|fast_food|cafe)$\"][\"name\"]$around;" +
            "nwr[\"tourism\"~\"^(hotel|motel)$\"]$around;" +
            "nwr[\"highway\"=\"rest_area\"]$around;" +
            ");out center 300;"
        return overpass(ql)
    }

    /** Last Overpass problem (error, or the server's "remark" such as a timeout); for diagnostics. */
    @Volatile var lastOverpassIssue: String? = null

    private fun overpass(ql: String): List<PlaceResult> {
        val data = enc(ql)
        for (host in OVERPASS_HOSTS) {
            try {
                val body = http(host + data)
                val remark = runCatching { JSONObject(body).optString("remark", "") }.getOrDefault("not JSON: " + body.take(200))
                if (remark.isNotBlank()) { lastOverpassIssue = "$host: $remark"; continue }
                lastOverpassIssue = null
                return parseOverpass(body)
            } catch (e: Exception) {
                lastOverpassIssue = "$host: $e"
            }
        }
        return emptyList()
    }

    private fun categoryNear(filter: String, at: GeoPoint): List<PlaceResult> {
        var r = nearbyCategory(filter, at, 12_000)
        if (r.isEmpty()) r = nearbyCategory(filter, at, 40_000)
        return r.map { it.copy(score = 30.0 - GeoMath.km(at, it)) }
    }

    /** Fast search-as-you-type: one Photon request biased to the driver. */
    fun quick(q: String, bias: GeoPoint?): List<PlaceResult> = PlaceRanker.rank(photon(QueryParse.clean(q), bias, 8), q, bias)

    /** Thorough search for when the driver presses Go. Best match first. */
    fun full(query: String, bias: GeoPoint?, userTown: String?): List<PlaceResult> {
        val q = QueryParse.clean(query)
        if (q.isBlank()) return emptyList()
        val out = mutableListOf<PlaceResult>()
        val isAddr = QueryParse.looksLikeAddress(q)

        if (isAddr) {
            var hits = census(q)
            if (hits.isEmpty() && userTown != null && !q.contains(",")) hits = census("$q, $userTown")
            out += hits.map { it.copy(score = 40.0) }
            out += photon(q, bias, 10)
            return PlaceRanker.rank(out, q, bias)
        }

        // "gas", "coffee near me": nearest places of that type.
        QueryParse.category(q)?.let { filter ->
            if (bias != null) {
                var near = categoryNear(filter, bias)
                // Overpass busy: settle for name-search hits that really are that kind of place
                // (a fuel station for "gas", never "Gaslight Diner").
                if (near.isEmpty()) near = photon(q, bias, 20, bboxAround(bias.lat, bias.lon, 0.4))
                    .filter { QueryParse.matches(q, it) }
                return PlaceRanker.rank(near, q, bias)
            }
        }

        // Photon can't read "in"/"near": "walmart in springfield mo" -> "walmart springfield mo".
        out += photon(q.replace(Regex("""\s+(in|near|at|by|around)\s+""", RegexOption.IGNORE_CASE), " "), bias, 10)

        var found = false
        for ((what, where) in QueryParse.splits(q).take(4)) {
            val town = findTown(where, bias) ?: continue
            val center = GeoPoint(town.lat, town.lon)
            val filter = QueryParse.category(what)
            var pois = if (filter != null) categoryNear(filter, center).map { it.copy(score = it.score + 15.0) } else emptyList()
            // Named businesses, or a category when Overpass is busy: name search inside the town.
            if (pois.isEmpty()) pois = photon(what, center, 15, bboxAround(town.lat, town.lon, 0.3))
                .filter { QueryParse.matches(what, it) && GeoMath.km(center, it) <= 35.0 }
                .map { it.copy(score = 45.0 - GeoMath.km(center, it)) }
            if (pois.isNotEmpty()) {
                out += pois
                found = true
                break
            }
        }
        if (!found && bias != null) {
            // "Walmart": the nearest ones around the driver.
            val near = photon(q, bias, 15, bboxAround(bias.lat, bias.lon, 0.6)).filter { QueryParse.matches(q, it) }
            out += near.map { it.copy(score = 20.0) }
        }
        // Bias off for ranking: town+business hits were already scored by distance from the town.
        return PlaceRanker.rank(out, q, if (found) null else bias)
    }
}
