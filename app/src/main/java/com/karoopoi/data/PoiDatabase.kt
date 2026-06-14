package com.karoopoi.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PoiEntity::class],
    version = 2,
    exportSchema = false
)
abstract class PoiDatabase : RoomDatabase() {

    abstract fun poiDao(): PoiDao
}