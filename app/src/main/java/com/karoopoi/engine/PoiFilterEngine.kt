package com.karoopoi.engine

import com.karoopoi.data.PoiCandidate
import com.karoopoi.data.PoiDao
import com.karoopoi.geo.GeoUtils
import com.karoopoi.geo.LatLng
import com.karoopoi.geo.PolylineDecoder
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

class PoiFilterEngine(private val poiDao: PoiDao) {

    private var cachedPolyline: String? = null
    private var cachedRoute: List<LatLng>? = null
    private var cachedSegmentDistances: DoubleArray? = null
    private var cachedPrefixDistances: DoubleArray? = null
    private var lastUserSeg: Int? = null

    suspend fun findNextPois(
        currentLocation: LatLng,
        routePolyline: String,
        activeCategories: Set<String>,
        thresholdMeters: Int,
        limit: Int = Int.MAX_VALUE
    ): List<PoiResult> {
        if (activeCategories.isEmpty() || routePolyline.isBlank()) return emptyList()

        val route: List<LatLng>
        val segmentDistances: DoubleArray
        val prefixDistances: DoubleArray
        if (routePolyline == cachedPolyline && cachedRoute != null) {
            route = cachedRoute!!
            segmentDistances = cachedSegmentDistances!!
            prefixDistances = cachedPrefixDistances!!
        } else {
            val decoded = PolylineDecoder.decode(routePolyline)
            if (decoded.size < 2) return emptyList()
            val dists = DoubleArray(decoded.size - 1) { i ->
                GeoUtils.distance(decoded[i], decoded[i + 1])
            }
            val prefixes = DoubleArray(dists.size + 1)
            prefixes[0] = 0.0
            for (i in dists.indices) {
                prefixes[i + 1] = prefixes[i] + dists[i]
            }
            cachedRoute = decoded
            cachedSegmentDistances = dists
            cachedPrefixDistances = prefixes
            cachedPolyline = routePolyline
            lastUserSeg = null
            route = decoded
            segmentDistances = dists
            prefixDistances = prefixes
        }

        if (route.size < 2) return emptyList()

        // Find user position: scan outward from last known segment
        val startSeg = lastUserSeg?.coerceIn(0, route.size - 2) ?: 0
        var userSeg = startSeg
        var userT = 0.0
        var userDist = Double.MAX_VALUE

        // Expand outward from last known segment for early termination
        var forward = startSeg
        var backward = startSeg - 1
        var found = false
        while (forward < route.size - 1 || backward >= 0) {
            if (forward < route.size - 1) {
                val (d, t) = GeoUtils.pointToSegmentProjection(currentLocation, route[forward], route[forward + 1])
                if (d < userDist) {
                    userDist = d; userSeg = forward; userT = t; found = true
                }
                forward++
                if (found && d > userDist * 3 && forward - userSeg > 10) break
            }
            if (backward >= 0) {
                val (d, t) = GeoUtils.pointToSegmentProjection(currentLocation, route[backward], route[backward + 1])
                if (d < userDist) {
                    userDist = d; userSeg = backward; userT = t; found = true
                }
                backward--
                if (found && d > userDist * 3 && userSeg - backward > 10) break
            }
        }
        lastUserSeg = userSeg

        // If user is extremely far from route, fall back gracefully
        if (userDist > 500) {
            // Full rescan needed — GPS jumped or rerouted
            userSeg = 0; userT = 0.0; userDist = Double.MAX_VALUE
            for (i in 0 until route.size - 1) {
                val (d, t) = GeoUtils.pointToSegmentProjection(currentLocation, route[i], route[i + 1])
                if (d < userDist) { userDist = d; userSeg = i; userT = t }
            }
            lastUserSeg = userSeg
        }

        val routeAhead = route.subList(userSeg, route.size)
        if (routeAhead.size < 2) return emptyList()

        // Window the route ahead to limit look-ahead distance
        // Single-pass: compute window AND bounding box together
        val maxLookAheadKm = 50.0
        val maxLookAheadMeters = maxLookAheadKm * 1000
        var windowEnd = routeAhead.size
        var accumulated = 0.0
        var minLat = Double.MAX_VALUE
        var maxLat = Double.MIN_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = Double.MIN_VALUE
        var latSum = 0.0
        // Include the first point (userSeg)
        var p = routeAhead[0]
        if (p.lat < minLat) minLat = p.lat
        if (p.lat > maxLat) maxLat = p.lat
        if (p.lon < minLon) minLon = p.lon
        if (p.lon > maxLon) maxLon = p.lon
        latSum += p.lat
        var count = 1
        for (i in 0 until routeAhead.size - 1) {
            accumulated += segmentDistances[userSeg + i]
            p = routeAhead[i + 1]
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
            latSum += p.lat
            count++
            if (accumulated > maxLookAheadMeters) {
                windowEnd = i + 2
                break
            }
        }
        val windowedAhead = routeAhead.subList(0, windowEnd)

        val avgLat = latSum / count
        val latPadding = thresholdMeters / 111_000.0 * 1.1
        val lonPadding = latPadding / cos(Math.toRadians(avgLat))
        minLat -= latPadding
        maxLat += latPadding
        minLon -= lonPadding
        maxLon += lonPadding

        val candidates = poiDao.findInBoundingBoxLightweight(
            activeCategories.toList(), minLat, maxLat, minLon, maxLon, limit
        )

        val results = candidates.mapNotNull { candidate ->
            val poiPoint = LatLng(candidate.lat, candidate.lon)
            var minDist = Double.MAX_VALUE
            var bestIndex = 0
            var bestT = 0.0
            for (i in 0 until windowedAhead.size - 1) {
                val s1 = windowedAhead[i]
                val s2 = windowedAhead[i + 1]

                // Quick midpoint reject: skip segments whose midpoint is too far
                // for the POI to possibly be within the current best distance
                if (minDist < Double.MAX_VALUE) {
                    val midLat = (s1.lat + s2.lat) * 0.5
                    val midLon = (s1.lon + s2.lon) * 0.5
                    val approxDistDeg = sqrt(
                        (candidate.lat - midLat).pow(2) + (candidate.lon - midLon).pow(2)
                    )
                    val segLen = segmentDistances[userSeg + i]
                    if (approxDistDeg * 111_000.0 > minDist + segLen) continue
                }

                val (d, t) = GeoUtils.pointToSegmentProjection(poiPoint, s1, s2)
                if (d < minDist) {
                    minDist = d
                    bestIndex = i
                    bestT = t
                }
            }
            if (minDist > thresholdMeters) return@mapNotNull null

            // O(1) distance along route using prefix sums
            val along = prefixDistances[userSeg + bestIndex] + segmentDistances[userSeg + bestIndex] * bestT
                    - prefixDistances[userSeg] - segmentDistances[userSeg] * userT
            if (along < 0) return@mapNotNull null  // exclude POIs behind user position

            PoiResult(
                osmId = candidate.osmId,
                name = candidate.name,
                category = candidate.category,
                lat = candidate.lat,
                lon = candidate.lon,
                distanceFromRoute = minDist,
                distanceAlongRoute = along
            )
        }

        return results.sortedBy { it.distanceAlongRoute }.take(limit)
    }
}