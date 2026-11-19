package com.stormnav.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionsTest {
    private val imp = UnitSystem.IMPERIAL

    @Test fun fogByVisibility() {
        val dense = ConditionRules.fromObservation("Fog", 300.0, null, null, 8.0, imp)
        assertEquals("fog-dense", dense.single().key)
        assertEquals(2, dense.single().level)
        assertTrue(dense.single().text.contains("0.2 mi") || dense.single().text.contains("ft"))
        val light = ConditionRules.fromObservation("Mist", 1200.0, null, null, 8.0, imp)
        assertEquals("fog", light.single().key)
        assertTrue(ConditionRules.fromObservation("Clear", 16000.0, null, null, 20.0, imp).isEmpty())
    }

    @Test fun iceSnowWindThunder() {
        assertEquals("ice", ConditionRules.fromObservation("Light Freezing Rain", 8000.0, null, null, -1.0, imp).first().key)
        assertEquals("snow-heavy", ConditionRules.fromObservation("Heavy Snow and Fog", 3000.0, null, null, -3.0, imp).first { it.source == "station" && it.key.startsWith("snow") }.key)
        val wind = ConditionRules.fromObservation("Cloudy", null, 22.0, 12.0, 15.0, imp).single()
        assertEquals("wind", wind.key); assertTrue(wind.text.contains("49 mph"))
        assertTrue(ConditionRules.fromObservation("Thunderstorms and Rain", null, null, null, 22.0, imp).any { it.key == "thunder" })
        assertTrue(ConditionRules.fromObservation("Light Rain", null, null, null, 0.0, imp).any { it.key == "ice-risk" })
    }

    @Test fun radarAndAlerts() {
        assertEquals("radar-heavy", ConditionRules.fromRadar(RadarIntensity.HEAVY)!!.key)
        assertNull(ConditionRules.fromRadar(RadarIntensity.MODERATE))
        assertEquals(2, ConditionRules.fromAlert("Flash Flood Warning")!!.level)
        assertEquals(1, ConditionRules.fromAlert("Dense Fog Advisory")!!.level)
        assertEquals("🌫", ConditionRules.fromAlert("Dense Fog Advisory")!!.icon)
        assertNull(ConditionRules.fromAlert("Frost Advisory"))
        assertNull(ConditionRules.fromAlert("Air Quality Alert"))
    }

    @Test fun mergeWorstFirst() {
        val m = ConditionRules.merge(listOf(
            ConditionRules.fromAlert("Dense Fog Advisory")!!,
            ConditionRules.fromRadar(RadarIntensity.SEVERE)!!,
            ConditionRules.fromRadar(RadarIntensity.SEVERE)!!))
        assertEquals(2, m.size)
        assertEquals("radar-severe", m.first().key)
    }

    @Test fun unitConversion() {
        assertEquals(10.0, ConditionRules.toMps(36.0, "wmoUnit:km_h-1")!!, 0.001)
        assertEquals(5.0, ConditionRules.toMps(5.0, "wmoUnit:m_s-1")!!, 0.001)
        assertNull(ConditionRules.toMps(null, "wmoUnit:km_h-1"))
    }
}
