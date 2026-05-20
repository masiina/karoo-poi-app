package com.karoopoi.engine

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.karoopoi.data.PoiDatabase
import com.karoopoi.data.PoiEntity
import com.karoopoi.geo.LatLng
import com.karoopoi.geo.PolylineEncoder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PoiFilterEngineTest {
    private lateinit var db: PoiDatabase
    private lateinit var engine: PoiFilterEngine

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PoiDatabase::class.java).build()
        engine = PoiFilterEngine(db.poiDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `findNextPois returns sorted by distance along route`() = runBlocking {
        val route = PolylineEncoder.encode(listOf(LatLng(0.0, 0.0), LatLng(0.1, 0.0)))
        db.poiDao().insertAll(
            PoiEntity(id = 1, osmId = "1", name = "Far", lat = 0.05, lon = 0.0005, category = "beach", tags = null),
            PoiEntity(id = 2, osmId = "2", name = "Near", lat = 0.01, lon = 0.0005, category = "swimming", tags = null),
            PoiEntity(id = 3, osmId = "3", name = "Off", lat = 0.02, lon = 0.01, category = "beach", tags = null)
        )
        val result = engine.findNextPois(
            currentLocation = LatLng(0.0, 0.0),
            routePolyline = route,
            activeCategories = setOf("beach", "swimming"),
            thresholdMeters = 1000
        )
        assertEquals(2, result.size)
        assertEquals("Near", result[0].name)
        assertEquals("Far", result[1].name)
        assertTrue(result[0].distanceAlongRoute < result[1].distanceAlongRoute)
    }
}
