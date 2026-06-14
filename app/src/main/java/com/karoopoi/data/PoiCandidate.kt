package com.karoopoi.data

import androidx.room.ColumnInfo

data class PoiCandidate(
    @ColumnInfo(name = "osm_id") val osmId: String,
    val name: String?,
    val category: String,
    val lat: Double,
    val lon: Double
)