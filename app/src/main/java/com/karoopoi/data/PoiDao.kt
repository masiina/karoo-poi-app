package com.karoopoi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PoiDao {

    @Insert
    suspend fun insertAll(pois: List<PoiEntity>)

    @Query("""
        SELECT osm_id, name, category, lat, lon
        FROM pois
        WHERE category IN (:categories)
          AND lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
        LIMIT :limit
    """)
    suspend fun findInBoundingBoxLightweight(
        categories: List<String>,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        limit: Int
    ): List<PoiCandidate>
}