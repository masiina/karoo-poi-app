package com.karoopoi.geo

import org.junit.Assert.*
import org.junit.Test

class GeoUtilsTest {
    @Test
    fun `distance between same point is zero`() {
        val p = LatLng(0.0, 0.0)
        assertEquals(0.0, GeoUtils.distance(p, p), 0.01)
    }

    @Test
    fun `distance between known points`() {
        // Approx 1 degree of latitude ≈ 111 km
        val a = LatLng(0.0, 0.0)
        val b = LatLng(1.0, 0.0)
        val d = GeoUtils.distance(a, b)
        assertEquals(111_000.0, d, 500.0)
    }

    @Test
    fun `pointToSegmentDistance on segment`() {
        val s1 = LatLng(0.0, 0.0)
        val s2 = LatLng(1.0, 0.0)
        val p = LatLng(0.5, 0.0)
        assertEquals(0.0, GeoUtils.pointToSegmentDistance(p, s1, s2), 0.01)
    }

    @Test
    fun `pointToSegmentDistance perpendicular`() {
        val s1 = LatLng(0.0, 0.0)
        val s2 = LatLng(1.0, 0.0)
        val p = LatLng(0.5, 0.001)
        val d = GeoUtils.pointToSegmentDistance(p, s1, s2)
        // 0.001 degrees lon at equator ≈ 111 meters
        assertEquals(111.0, d, 5.0)
    }

    @Test
    fun `pointToSegmentDistance beyond endpoint`() {
        val s1 = LatLng(0.0, 0.0)
        val s2 = LatLng(1.0, 0.0)
        val p = LatLng(2.0, 0.0)
        val d = GeoUtils.pointToSegmentDistance(p, s1, s2)
        assertEquals(111_000.0, d, 500.0)
    }
}
