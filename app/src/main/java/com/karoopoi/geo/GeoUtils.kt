package com.karoopoi.geo

import kotlin.math.*

object GeoUtils {
    private const val EARTH_RADIUS = 6371000.0

    fun distance(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val aa = sin(dLat / 2).pow(2) + sin(dLon / 2).pow(2) * cos(lat1) * cos(lat2)
        val c = 2 * atan2(sqrt(aa), sqrt(1 - aa))
        return EARTH_RADIUS * c
    }

    /**
     * Initial bearing (forward azimuth) from point [a] to point [b] in degrees [0, 360).
     * 0° = North, 90° = East, 180° = South, 270° = West.
     */
    fun bearing(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val brng = Math.toDegrees(atan2(y, x))
        return (brng + 360.0) % 360.0
    }

    /**
     * Converts a bearing in degrees to a compass direction string.
     * e.g. 0° -> "N", 90° -> "E", 180° -> "S", 270° -> "W", 45° -> "NE"
     */
    fun compassDirection(bearingDeg: Double): String {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = ((bearingDeg + 22.5) / 45.0).toInt() % 8
        return directions[index]
    }

    fun pointToSegmentDistance(p: LatLng, s1: LatLng, s2: LatLng): Double {
        return pointToSegmentProjection(p, s1, s2).first
    }

    fun pointToSegmentProjection(p: LatLng, s1: LatLng, s2: LatLng): Pair<Double, Double> {
        val scale = cos(Math.toRadians((s1.lat + s2.lat) / 2)).coerceAtLeast(1e-12)
        val x1 = 0.0
        val y1 = 0.0
        val x2 = (s2.lon - s1.lon) * scale
        val y2 = s2.lat - s1.lat
        val px = (p.lon - s1.lon) * scale
        val py = p.lat - s1.lat
        val dx = x2 - x1
        val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((px - x1) * dx + (py - y1) * dy) / len2
        val clampedT = t.coerceIn(0.0, 1.0)
        val projX = x1 + clampedT * dx
        val projY = y1 + clampedT * dy
        val deltaLatDeg = projY - py
        val deltaLonScaled = projX - px
        val deltaLonDeg = deltaLonScaled / scale
        val dLatMeters = Math.toRadians(deltaLatDeg) * EARTH_RADIUS
        val dLonMeters = Math.toRadians(deltaLonDeg) * EARTH_RADIUS
        val distance = sqrt(dLatMeters * dLatMeters + dLonMeters * dLonMeters)
        return distance to clampedT
    }
}
