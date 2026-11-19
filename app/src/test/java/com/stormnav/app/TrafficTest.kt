package com.stormnav.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficTest {
    @Test fun cleansPastedKeys() {
        assertEquals("abcDEF1234567890abcdEF12", Traffic.cleanKey("  key=\"abcDEF1234567890abcdEF12\"\n"))
        assertTrue(Traffic.looksValid(Traffic.cleanKey("abcDEF1234567890abcdEF12")))
        assertFalse(Traffic.looksValid("short"))
        assertFalse(Traffic.looksValid(""))
    }

    @Test fun tileMathMatchesTomTomExample() {
        // TomTom's docs use tile 12/2044/1360 (central Europe); a point inside it must map back to it.
        val n = 4096.0
        val lon = (2044.5 / n) * 360.0 - 180.0
        val y = 1360.5
        val lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))))
        assertEquals(2044 to 1360, Traffic.tileXY(lat, lon, 12))
        // Springfield, IL at zoom 12.
        val (x, yy) = Traffic.tileXY(39.7990, -89.6440, 12)
        assertEquals(1028, x); assertEquals(1553, yy)
    }

    @Test fun urlsCarryKeyAndPlaceholders() {
        val f = Traffic.flowUrl("KEY123", 7)
        assertTrue(f.startsWith("https://api.tomtom.com/traffic/map/4/tile/flow/relative0/{z}/{x}/{y}.png?"))
        assertTrue(f.contains("key=KEY123"))
        assertTrue(Traffic.incidentsUrl("KEY123", 7).contains("/incidents/s3/{z}/{x}/{y}.png"))
        assertTrue(Traffic.probeUrl("K", 39.7990, -89.6440).contains("/12/1028/1553.png"))
    }

    @Test fun statusCodes() {
        assertEquals(Traffic.Status.OK, Traffic.statusFor(200))
        assertEquals(Traffic.Status.REJECTED, Traffic.statusFor(403))
        assertEquals(Traffic.Status.BUSY, Traffic.statusFor(429))
        assertEquals(Traffic.Status.BUSY, Traffic.statusFor(503))
        assertEquals(Traffic.Status.OFFLINE, Traffic.statusFor(-1))
    }
}
