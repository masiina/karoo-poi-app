package com.karoopoi.engine

data class PoiResult(
    val osmId: String,
    val name: String?,
    val category: String,
    val lat: Double,
    val lon: Double,
    val distanceFromRoute: Double,
    val distanceAlongRoute: Double
)
