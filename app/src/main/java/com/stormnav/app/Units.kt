package com.stormnav.app

import kotlin.math.roundToInt

/** Which unit system the driving HUD uses. BOTH keeps the 0.4.0 "metric (imperial)" style. */
enum class UnitSystem { IMPERIAL, METRIC, BOTH }

/** Metric + imperial formatting. */
object Units {
    private const val M_PER_MILE = 1609.344
    private const val FT_PER_M = 3.28084
    private const val MPH_PER_MPS = 2.236936

    /** "350 m (1150 ft)", "4.2 km (2.6 mi)", "42 km (26 mi)". Input in meters. */
    fun distance(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        if (m < 1000.0) {
            val metric = (m / 10.0).roundToInt() * 10
            val feet = (metric * FT_PER_M / 10.0).roundToInt() * 10
            return "$metric m ($feet ft)"
        }
        return "${oneOrZeroDecimals(m / 1000.0)} km (${oneOrZeroDecimals(m / M_PER_MILE)} mi)"
    }

    /** Compact single-system distance for the HUD: "1.8 mi", "500 ft", "2.9 km", "350 m". */
    fun short(meters: Double, system: UnitSystem): String {
        val m = meters.coerceAtLeast(0.0)
        return when (system) {
            UnitSystem.METRIC ->
                if (m < 1000.0) "${(m / 10.0).roundToInt() * 10} m" else "${oneOrZeroDecimals(m / 1000.0)} km"
            UnitSystem.IMPERIAL -> {
                val miles = m / M_PER_MILE
                // Under ~0.1 mi, US nav apps switch to feet rounded to 50.
                if (miles < 0.1) "${((m * FT_PER_M) / 50.0).roundToInt() * 50} ft" else "${oneOrZeroDecimals(miles)} mi"
            }
            UnitSystem.BOTH -> distance(m)
        }
    }

    /** Speed number only (for the speedometer dial). */
    fun speedValue(mps: Double, system: UnitSystem): Int =
        if (system == UnitSystem.METRIC) (mps * 3.6).roundToInt() else (mps * MPH_PER_MPS).roundToInt()

    fun speedUnit(system: UnitSystem): String = if (system == UnitSystem.METRIC) "km/h" else "mph"

    fun mphToMps(mph: Double) = mph / MPH_PER_MPS

    /** "18 mph", "29 km/h", or "18 mph (29 km/h)". */
    fun wind(mph: Double, system: UnitSystem): String {
        val kmh = (mph * 1.609344).roundToInt()
        return when (system) {
            UnitSystem.IMPERIAL -> "${mph.roundToInt()} mph"
            UnitSystem.METRIC -> "$kmh km/h"
            UnitSystem.BOTH -> "${mph.roundToInt()} mph ($kmh km/h)"
        }
    }

    /** "77°F", "25°C", or "77°F (25°C)" from a Fahrenheit reading. */
    fun temp(f: Double, system: UnitSystem): String = when (system) {
        UnitSystem.IMPERIAL -> "${f.roundToInt()}°F"
        UnitSystem.METRIC -> "${celsiusFromF(f).roundToInt()}°C"
        UnitSystem.BOTH -> tempFromF(f)
    }

    /** Spoken form for text-to-speech. */
    fun spokenDistance(meters: Double, system: UnitSystem = UnitSystem.BOTH): String {
        val m = meters.coerceAtLeast(0.0)
        val metric = if (m < 1000.0) "${(m / 10.0).roundToInt() * 10} meters" else "${oneOrZeroDecimals(m / 1000.0)} kilometers"
        val imperial = if (m / M_PER_MILE < 0.1) "${((m * FT_PER_M) / 50.0).roundToInt() * 50} feet"
            else "${oneOrZeroDecimals(m / M_PER_MILE)} miles"
        return when (system) {
            UnitSystem.METRIC -> metric
            UnitSystem.IMPERIAL -> imperial
            UnitSystem.BOTH -> "$metric, $imperial"
        }
    }

    fun celsiusFromF(f: Double) = (f - 32.0) * 5.0 / 9.0
    fun fahrenheitFromC(c: Double) = c * 9.0 / 5.0 + 32.0

    /** "72°F (22°C)" from a Fahrenheit reading. */
    fun tempFromF(f: Double) = "${f.roundToInt()}°F (${celsiusFromF(f).roundToInt()}°C)"

    /** "22°C (72°F)" from a Celsius reading. */
    fun tempFromC(c: Double) = "${c.roundToInt()}°C (${fahrenheitFromC(c).roundToInt()}°F)"

    private fun oneOrZeroDecimals(v: Double): String =
        if (v < 10.0) String.format(java.util.Locale.US, "%.1f", v) else v.roundToInt().toString()
}
