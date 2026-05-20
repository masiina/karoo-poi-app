package com.karoopoi.geo

import kotlin.math.pow
import kotlin.math.roundToInt

object PolylineEncoder {
    fun encode(points: List<LatLng>, precision: Int = 5): String {
        val factor = 10.0.pow(precision)
        var lat = 0
        var lng = 0
        val sb = StringBuilder()
        for (p in points) {
            val latE5 = (p.lat * factor).roundToInt()
            val lngE5 = (p.lon * factor).roundToInt()
            sb.append(encodeValue(latE5 - lat))
            sb.append(encodeValue(lngE5 - lng))
            lat = latE5
            lng = lngE5
        }
        return sb.toString()
    }

    private fun encodeValue(v: Int): String {
        var value = v shl 1
        if (v < 0) value = value.inv()
        val out = StringBuilder()
        while (value >= 0x20) {
            out.append(((0x20 or (value and 0x1f)) + 63).toChar())
            value = value shr 5
        }
        out.append((value + 63).toChar())
        return out.toString()
    }
}
