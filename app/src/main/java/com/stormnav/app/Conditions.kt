package com.stormnav.app

import java.util.Locale

/*
 * Driving-condition warnings (fog, heavy rain, ice, wind, flooding…) from three sources:
 * radar at your exact position, the nearest NWS station's latest observation, and active
 * NWS alerts for where you are. Android-free so the rules can be tested.
 */

/** One warning. level 1 = caution (amber), 2 = danger (red). [key] de-duplicates and gates speech. */
data class Condition(val icon: String, val text: String, val level: Int, val key: String, val source: String)

object ConditionRules {
    private const val MI = 1609.344

    /** Radar intensity at your position right now. */
    fun fromRadar(level: Int): Condition? = when {
        level >= RadarIntensity.SEVERE -> Condition("⛈", "Severe storm over you", 2, "radar-severe", "radar")
        level >= RadarIntensity.HEAVY -> Condition("🌧", "Heavy rain here", 1, "radar-heavy", "radar")
        else -> null
    }

    /**
     * Latest station observation. Wind values in m/s, visibility in metres, temperature °C.
     * Missing values are null (NWS often omits some).
     */
    fun fromObservation(text: String?, visibilityM: Double?, gustMps: Double?, windMps: Double?, tempC: Double?, units: UnitSystem): List<Condition> {
        val out = mutableListOf<Condition>()
        val t = (text ?: "").lowercase(Locale.US)
        val u = if (units == UnitSystem.BOTH) UnitSystem.IMPERIAL else units
        when {
            visibilityM != null && visibilityM < 0.25 * MI ->
                out += Condition("🌫", "Dense fog · visibility ${Units.short(visibilityM, u)}", 2, "fog-dense", "station")
            visibilityM != null && visibilityM < 1.0 * MI ->
                out += Condition("🌫", "${if (t.contains("fog")) "Fog" else "Low visibility"} · ${Units.short(visibilityM, u)}", 1, "fog", "station")
            t.contains("fog") && (visibilityM == null || visibilityM < 3 * MI) ->
                out += Condition("🌫", "Fog reported nearby", 1, "fog", "station")
        }
        when {
            t.contains("freezing rain") || t.contains("freezing drizzle") || t.contains("ice pellets") || t.contains("sleet") ->
                out += Condition("🧊", "Freezing rain/ice · roads may be slick", 2, "ice", "station")
            t.contains("heavy snow") || t.contains("blowing snow") ->
                out += Condition("❄️", "Heavy snow", 2, "snow-heavy", "station")
            t.contains("snow") ->
                out += Condition("❄️", "Snow", 1, "snow", "station")
        }
        if (t.contains("heavy rain")) out += Condition("🌧", "Heavy rain reported nearby", 1, "rain-heavy-obs", "station")
        if (t.contains("thunder")) out += Condition("⛈", "Thunderstorm nearby", 1, "thunder", "station")
        val gust = gustMps ?: windMps
        if (gust != null && gust >= 40 / 2.236936) {
            out += Condition("💨", "High winds · gusts ${Units.wind(gust * 2.236936, u)}", if (gust >= 58 / 2.236936) 2 else 1, "wind", "station")
        }
        // Wet and at/below freezing: black ice risk even without "freezing rain" in the text.
        if (tempC != null && tempC <= 0.5 && (t.contains("rain") || t.contains("drizzle") || t.contains("mist")) && out.none { it.key == "ice" }) {
            out += Condition("🧊", "Near freezing and wet · watch for ice", 1, "ice-risk", "station")
        }
        return out
    }

    /** An active NWS alert for your location, if it matters for driving. */
    fun fromAlert(event: String): Condition? {
        val e = event.lowercase(Locale.US)
        val danger = e.endsWith("warning") || e.contains("emergency")
        val icon = when {
            e.contains("tornado") -> "🌪"
            e.contains("thunderstorm") -> "⛈"
            e.contains("flood") -> "🌊"
            e.contains("fog") -> "🌫"
            e.contains("ice") || e.contains("freezing") || e.contains("sleet") -> "🧊"
            e.contains("snow") || e.contains("winter") || e.contains("blizzard") -> "❄️"
            e.contains("wind") -> "💨"
            e.contains("dust") || e.contains("smoke") -> "🌫"
            else -> return null
        }
        return Condition(icon, event, if (danger) 2 else 1, "alert-" + e.replace(' ', '-'), "alert")
    }

    /** One list, worst first, no duplicates (an alert about fog and a fog observation both stay). */
    fun merge(list: List<Condition>): List<Condition> =
        list.distinctBy { it.key }.sortedWith(compareByDescending<Condition> { it.level }.thenBy { it.source != "alert" })

    /** Wind/visibility values from NWS observation JSON fields like {"value": 12.6, "unitCode": "wmoUnit:km_h-1"}. */
    fun toMps(value: Double?, unitCode: String?): Double? {
        if (value == null || value.isNaN()) return null
        val u = unitCode ?: ""
        return when {
            u.contains("km_h") -> value / 3.6
            u.contains("m_s") -> value
            u.contains("kt") || u.contains("knot") -> value * 0.514444
            u.contains("mi_h") || u.contains("mph") -> value / 2.236936
            else -> value / 3.6 // NWS default is km/h
        }
    }
}
