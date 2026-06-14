package com.karoopoi.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pois")
data class PoiEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "osm_id") val osmId: String,
    val name: String?,
    val lat: Double,
    val lon: Double,
    val category: String,
    val tags: String?
)