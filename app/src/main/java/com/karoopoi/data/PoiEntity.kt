package com.karoopoi.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pois",
    indices = [
        Index(value = ["osm_id"], unique = true, name = "index_pois_osm_id"),
        Index(value = ["category"], name = "index_pois_category"),
        Index(value = ["lat", "lon"], name = "index_pois_lat_lon"),
        Index(value = ["category", "lat", "lon"], name = "index_pois_category_lat_lon")
    ]
)
data class PoiEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "osm_id") val osmId: String,
    val name: String?,
    val lat: Double,
    val lon: Double,
    val category: String,
    val tags: String?
)