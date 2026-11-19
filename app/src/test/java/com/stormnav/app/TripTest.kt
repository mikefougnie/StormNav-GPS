package com.stormnav.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TripTest {
    @Test fun windParsing() {
        assertEquals(18.0, WeatherParse.windMph("18 mph")!!, 0.0)
        assertEquals(20.0, WeatherParse.windMph("10 to 20 mph")!!, 0.0)
        assertEquals(10.0, WeatherParse.windMph("16.09344 km/h")!!, 0.01)
        assertNull(WeatherParse.windMph(""))
        assertNull(WeatherParse.windMph("calm"))
    }

    @Test fun tempAndRain() {
        assertEquals(77.0, WeatherParse.tempF(25.0, "C")!!, 0.01)
        assertEquals(77.0, WeatherParse.tempF(77.0, "F")!!, 0.0)
        assertNull(WeatherParse.tempF(null, "F"))
        assertEquals(72, WeatherParse.rainChance(72.0))
        assertEquals(100, WeatherParse.rainChance(130.0))
        assertNull(WeatherParse.rainChance(null))
    }

    @Test fun maneuverKinds() {
        assertEquals(ManeuverKind.RIGHT, ManeuverKind.from("turn", "right"))
        assertEquals(ManeuverKind.SLIGHT_LEFT, ManeuverKind.from("off ramp", "slight left"))
        assertEquals(ManeuverKind.ARRIVE, ManeuverKind.from("arrive", "right"))
        assertEquals(ManeuverKind.ROUNDABOUT, ManeuverKind.from("roundabout", "right"))
        assertEquals(ManeuverKind.STRAIGHT, ManeuverKind.from("new name", "straight"))
    }

    @Test fun stormTimerStates() {
        val segs = listOf(StormSegment(10_000.0, 15_000.0, RadarIntensity.HEAVY))
        assertEquals(StormTimer.NoRoute, TripMath.stormTimer(segs, 0.0, 25.0, routeActive = false))
        assertEquals(StormTimer.Unknown, TripMath.stormTimer(null, 0.0, 25.0, routeActive = true))
        assertEquals(StormTimer.Clear, TripMath.stormTimer(emptyList(), 0.0, 25.0, routeActive = true))
        assertEquals(StormTimer.Clear, TripMath.stormTimer(segs, 16_000.0, 25.0, routeActive = true))
        assertTrue(TripMath.stormTimer(segs, 12_000.0, 25.0, routeActive = true) is StormTimer.Inside)
        // 10 km at 27.78 m/s (62 mph) is 6 minutes — the mockup's "Storm Timer 6 min".
        val t = TripMath.stormTimer(segs, 0.0, Units.mphToMps(62.0), routeActive = true) as StormTimer.Minutes
        assertEquals(6, t.minutes)
        assertEquals(RadarIntensity.HEAVY, t.peak)
    }

    @Test fun planningSpeedIgnoresStops() {
        assertEquals(20.0, TripMath.planningSpeed(0.0, 20.0), 0.0)
        assertEquals(20.0, TripMath.planningSpeed(null, 20.0), 0.0)
        assertEquals(25.0, TripMath.planningSpeed(30.0, 20.0), 0.0)
    }

    @Test fun clockAndDuration() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear(); cal.set(2026, 10, 17, 20, 24)
        val ms = cal.timeInMillis
        val fmt = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        assertEquals("8:24 PM", TripMath.clock(ms, false, fmt))
        assertEquals("20:24", TripMath.clock(ms, true, fmt))
        assertEquals("12 min", TripMath.duration(12 * 60.0))
        assertEquals("1 h 05 min", TripMath.duration(65 * 60.0))
    }

    @Test fun shortUnits() {
        assertEquals("1.8 mi", Units.short(2897.0, UnitSystem.IMPERIAL))
        assertEquals("8.4 mi", Units.short(13_518.0, UnitSystem.IMPERIAL))
        assertEquals("500 ft", Units.short(152.0, UnitSystem.IMPERIAL))
        assertEquals("2.9 km", Units.short(2897.0, UnitSystem.METRIC))
        assertEquals("350 m", Units.short(350.0, UnitSystem.METRIC))
        assertEquals("62", Units.speedValue(Units.mphToMps(62.0), UnitSystem.IMPERIAL).toString())
        assertEquals("100", Units.speedValue(27.78, UnitSystem.METRIC).toString())
        assertEquals("77°F", Units.temp(77.0, UnitSystem.IMPERIAL))
        assertEquals("25°C", Units.temp(77.0, UnitSystem.METRIC))
        assertEquals("18 mph", Units.wind(18.0, UnitSystem.IMPERIAL))
    }
}
