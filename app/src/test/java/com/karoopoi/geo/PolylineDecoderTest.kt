package com.karoopoi.geo

import org.junit.Assert.*
import org.junit.Test

class PolylineDecoderTest {
    @Test
    fun `decode known polyline`() {
        // Google's canonical example polyline for 3 points
        val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        val points = PolylineDecoder.decode(encoded)
        assertEquals(3, points.size)
        assertEquals(38.5, points[0].lat, 0.0001)
        assertEquals(-120.2, points[0].lon, 0.0001)
        assertEquals(40.7, points[1].lat, 0.0001)
        assertEquals(-120.95, points[1].lon, 0.0001)
        assertEquals(43.252, points[2].lat, 0.0001)
        assertEquals(-126.453, points[2].lon, 0.0001)
    }

    @Test
    fun `decode empty string returns empty list`() {
        assertTrue(PolylineDecoder.decode("").isEmpty())
    }

    @Test
    fun `decode truncated string returns empty list`() {
        assertTrue(PolylineDecoder.decode("_p~iF").isEmpty())
    }
}
