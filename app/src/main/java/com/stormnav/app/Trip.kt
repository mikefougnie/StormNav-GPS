package com.stormnav.app

import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Android-free pieces behind the driving HUD (gauges, ETA, storm timer, turn arrow),
 * kept here so they can be unit-tested on a plain JVM.
 */

/** Current conditions for the gauges, from the first period of the NWS hourly forecast. */
data class WeatherNow(val rainChancePct: Int?, val windMph: Double?, val tempF: Double?)

object WeatherParse {
    private val number = Regex("""(\d+(?:\.\d+)?)""")

    /**
     * NWS hourly windSpeed strings look like "18 mph", "10 to 20 mph" or "15 km/h".
     * Returns the highest number, in mph, or null if nothing can be read.
     */
    fun windMph(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val peak = number.findAll(raw).mapNotNull { it.value.toDoubleOrNull() }.maxOrNull() ?: return null
        return if (raw.contains("km", ignoreCase = true)) peak / 1.609344 else peak
    }

    /** Temperature in °F from an NWS value and its unit letter ("F" or "C"). */
    fun tempF(value: Double?, unit: String?): Double? {
        if (value == null || value.isNaN()) return null
        return if (unit.equals("C", ignoreCase = true)) Units.fahrenheitFromC(value) else value
    }

    /** probabilityOfPrecipitation.value can be null; clamp to 0..100. */
    fun rainChance(value: Double?): Int? =
        if (value == null || value.isNaN()) null else value.roundToInt().coerceIn(0, 100)
}

/** Turn-arrow glyph for the top banner, from OSRM maneuver type + modifier. */
enum class ManeuverKind { STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, ROUNDABOUT, ARRIVE, DEPART;

    companion object {
        fun from(type: String, modifier: String): ManeuverKind = when {
            type == "arrive" -> ARRIVE
            type == "roundabout" || type == "rotary" || type == "roundabout turn" -> ROUNDABOUT
            modifier == "uturn" -> UTURN
            modifier == "sharp left" -> SHARP_LEFT
            modifier == "left" -> LEFT
            modifier == "slight left" -> SLIGHT_LEFT
            modifier == "sharp right" -> SHARP_RIGHT
            modifier == "right" -> RIGHT
            modifier == "slight right" -> SLIGHT_RIGHT
            type == "depart" -> DEPART
            else -> STRAIGHT
        }
    }
}

/** What the purple Storm Timer card shows. */
sealed class StormTimer {
    /** No route, so nothing to time. */
    object NoRoute : StormTimer()
    /** Route known, radar not checked yet or unavailable. */
    object Unknown : StormTimer()
    /** Radar shows no moderate-or-heavier returns ahead on the route. */
    object Clear : StormTimer()
    /** Already inside a wet stretch. */
    data class Inside(val peak: Int) : StormTimer()
    /** Minutes until the next wet stretch, at the expected speed. */
    data class Minutes(val minutes: Int, val peak: Int, val distanceM: Double) : StormTimer()
}

object TripMath {
    /**
     * Speed used for time estimates: the current GPS speed when moving at a highway-ish
     * pace, otherwise the route's planned average, so the numbers don't jump to "999 min"
     * at a red light.
     */
    fun planningSpeed(gpsMps: Double?, routeAvgMps: Double): Double {
        val gps = gpsMps ?: 0.0
        return if (gps >= 8.0) (gps + routeAvgMps) / 2.0 else routeAvgMps
    }

    fun remainingMeters(totalM: Double, progressM: Double): Double = (totalM - progressM).coerceAtLeast(0.0)

    fun remainingSeconds(remainingM: Double, speedMps: Double): Double =
        if (speedMps > 0.5) remainingM / speedMps else 0.0

    fun stormTimer(
        segments: List<StormSegment>?, progressM: Double, speedMps: Double, routeActive: Boolean
    ): StormTimer {
        if (!routeActive) return StormTimer.NoRoute
        if (segments == null) return StormTimer.Unknown
        val next = RadarRouteAnalyzer.ahead(segments, progressM).minByOrNull { it.startM } ?: return StormTimer.Clear
        val dist = next.startM - progressM
        if (dist < 300.0) return StormTimer.Inside(next.peak)
        if (speedMps <= 0.5) return StormTimer.Unknown
        val min = (dist / speedMps / 60.0).roundToInt().coerceAtLeast(1)
        return StormTimer.Minutes(min, next.peak, dist)
    }

    /** "8:24 PM" for an epoch-millis arrival time, or "20:24" when 24-hour is preferred. */
    fun clock(epochMs: Long, use24h: Boolean, cal: Calendar = Calendar.getInstance()): String {
        cal.timeInMillis = epochMs
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        if (use24h) return String.format(Locale.US, "%02d:%02d", h, m)
        val h12 = if (h % 12 == 0) 12 else h % 12
        return String.format(Locale.US, "%d:%02d %s", h12, m, if (h < 12) "AM" else "PM")
    }

    /** "12 min", "1 h 05 min". */
    fun duration(seconds: Double): String {
        val totalMin = (seconds / 60.0).roundToInt().coerceAtLeast(0)
        if (totalMin < 60) return "$totalMin min"
        return String.format(Locale.US, "%d h %02d min", totalMin / 60, totalMin % 60)
    }
}
