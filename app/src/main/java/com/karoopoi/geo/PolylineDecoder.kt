package com.karoopoi.geo

import kotlin.math.pow

object PolylineDecoder {
    fun decode(encoded: String, precision: Int = 5): List<LatLng> {
        val poly = mutableListOf<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0
        val factor = 10.0.pow(precision)

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                if (index >= len) return emptyList()
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dLat

            shift = 0
            result = 0
            do {
                if (index >= len) return poly
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dLng

            poly.add(LatLng(lat / factor, lng / factor))
        }
        return poly
    }
}
