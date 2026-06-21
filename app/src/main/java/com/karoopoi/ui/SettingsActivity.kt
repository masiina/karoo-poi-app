package com.karoopoi.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.karoopoi.R
import com.karoopoi.data.PoiDatabase
import com.karoopoi.geo.GeoUtils
import com.karoopoi.geo.LatLng
import com.karoopoi.prefs.PoiPreferences
import com.karoopoi.prefs.PoiPreferencesImpl
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.LaunchPinDrop
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PoiPreferencesImpl
    private val karooSystem by lazy { KarooSystemService(this) }
    private val db by lazy {
        Room.databaseBuilder(applicationContext, PoiDatabase::class.java, "pois_data.db")
            .createFromAsset("pois.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    @Volatile
    private var currentLocation: LatLng? = null

    private lateinit var settingsContainer: View
    private lateinit var nearbyContainer: View

    private lateinit var nearbyBeachesSwitch: SwitchMaterial
    private lateinit var nearbyStoresSwitch: SwitchMaterial
    private lateinit var nearbyViewpointSwitch: SwitchMaterial
    private lateinit var nearbyDistanceSlider: Slider
    private lateinit var nearbyDistanceValue: TextView
    private lateinit var nearbySearchButton: MaterialButton
    private lateinit var nearbyStatus: TextView
    private lateinit var nearbyRecycler: RecyclerView
    private lateinit var nearbyAdapter: NearbyPoiAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = PoiPreferences.getInstance(this)

        setupSettings()
        setupTabs()
        setupNearby()
    }

    override fun onStart() {
        super.onStart()
        karooSystem.connect { connected ->
            if (connected) {
                karooSystem.addConsumer<OnLocationChanged>(
                    onEvent = { loc ->
                        currentLocation = LatLng(loc.lat, loc.lng)
                        if (nearbyStatus.text == "GPS locating...") {
                            nearbyStatus.text = "Ready to search"
                        }
                    }
                )
            }
        }
    }

    override fun onStop() {
        karooSystem.disconnect()
        super.onStop()
    }

    private fun setupSettings() {
        bindSwitch(
            switch = findViewById(R.id.beaches_swimming_switch),
            flow = prefs.categoryBeachesSwimming,
            setter = { prefs.setBeachesSwimming(it) }
        )
        bindSwitch(
            switch = findViewById(R.id.stores_switch),
            flow = prefs.categoryStores,
            setter = { prefs.setStores(it) }
        )
        bindSwitch(
            switch = findViewById(R.id.viewpoint_switch),
            flow = prefs.categoryViewpoint,
            setter = { prefs.setViewpoint(it) }
        )

        val thresholdSlider = findViewById<Slider>(R.id.threshold_slider)
        val thresholdValue = findViewById<TextView>(R.id.threshold_value)

        thresholdSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val intVal = slider.value.toInt()
                lifecycleScope.launch { prefs.setThreshold(intVal) }
            }
        })
        thresholdSlider.addOnChangeListener { _, value, _ ->
            thresholdValue.text = "${value.toInt()}m"
        }

        lifecycleScope.launch {
            prefs.thresholdMeters.collect {
                thresholdSlider.value = it.toFloat()
                thresholdValue.text = "${it}m"
            }
        }
    }

    private fun setupTabs() {
        settingsContainer = findViewById(R.id.settings_container)
        nearbyContainer = findViewById(R.id.nearby_container)

        val tabLayout = findViewById<TabLayout>(R.id.tab_layout)
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showSettings()
                    1 -> showNearby()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupNearby() {
        nearbyBeachesSwitch = findViewById(R.id.nearby_beaches_switch)
        nearbyStoresSwitch = findViewById(R.id.nearby_stores_switch)
        nearbyViewpointSwitch = findViewById(R.id.nearby_viewpoint_switch)
        nearbyDistanceSlider = findViewById(R.id.nearby_distance_slider)
        nearbyDistanceValue = findViewById(R.id.nearby_distance_value)
        nearbySearchButton = findViewById(R.id.nearby_search_button)
        nearbyStatus = findViewById(R.id.nearby_status)
        nearbyRecycler = findViewById(R.id.nearby_recycler)

        nearbyAdapter = NearbyPoiAdapter { poi -> navigateToPoi(poi) }
        nearbyRecycler.layoutManager = LinearLayoutManager(this)
        nearbyRecycler.adapter = nearbyAdapter

        nearbyDistanceValue.text = "${nearbyDistanceSlider.value.toInt()}m"
        nearbyDistanceSlider.addOnChangeListener { _, value, _ ->
            nearbyDistanceValue.text = "${value.toInt()}m"
        }

        nearbySearchButton.setOnClickListener { searchNearby() }
    }

    private fun showSettings() {
        settingsContainer.visibility = View.VISIBLE
        nearbyContainer.visibility = View.GONE
    }

    private fun showNearby() {
        settingsContainer.visibility = View.GONE
        nearbyContainer.visibility = View.VISIBLE
    }

    private fun searchNearby() {
        val location = currentLocation
        if (location == null) {
            nearbyStatus.text = "GPS locating..."
            nearbyAdapter.submitList(emptyList())
            return
        }

        val categories = mutableListOf<String>()
        if (nearbyBeachesSwitch.isChecked) {
            categories.addAll(BEACH_CATEGORIES)
        }
        if (nearbyStoresSwitch.isChecked) {
            categories.addAll(STORE_CATEGORIES)
        }
        if (nearbyViewpointSwitch.isChecked) {
            categories.addAll(VIEWPOINT_CATEGORIES)
        }

        if (categories.isEmpty()) {
            nearbyStatus.text = "Select a category"
            nearbyAdapter.submitList(emptyList())
            return
        }

        val radiusMeters = nearbyDistanceSlider.value.toInt()
        nearbyStatus.text = "Searching..."

        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                queryNearbyPois(location, categories, radiusMeters)
            }

            if (results.isEmpty()) {
                nearbyStatus.text = "No POIs found"
            } else {
                nearbyStatus.text = "Found ${results.size} POI(s)"
            }
            nearbyAdapter.submitList(results)
        }
    }

    private suspend fun queryNearbyPois(
        location: LatLng,
        categories: List<String>,
        radiusMeters: Int
    ): List<NearbyPoi> {
        val latDelta = radiusMeters / 111000.0
        val lonDelta = radiusMeters / (111000.0 * cos(Math.toRadians(location.lat)))

        val candidates = db.poiDao().findInBoundingBoxLightweight(
            categories = categories,
            minLat = location.lat - latDelta,
            maxLat = location.lat + latDelta,
            minLon = location.lon - lonDelta,
            maxLon = location.lon + lonDelta,
            limit = 50
        )

        return candidates.map { candidate ->
            val poiLocation = LatLng(candidate.lat, candidate.lon)
            val distance = GeoUtils.distance(location, poiLocation)
            val bearing = GeoUtils.bearing(location, poiLocation)
            NearbyPoi(
                name = candidate.name ?: "Unnamed",
                distance = distance,
                bearing = bearing,
                compassDir = GeoUtils.compassDirection(bearing),
                category = candidate.category,
                lat = candidate.lat,
                lon = candidate.lon
            )
        }.sortedBy { it.distance }
    }

    private fun navigateToPoi(poi: NearbyPoi) {
        val pin = Symbol.POI(
            id = "poi-${System.currentTimeMillis()}",
            lat = poi.lat,
            lng = poi.lon,
            type = poiTypeForCategory(poi.category),
            name = poi.name,
        )
        karooSystem.dispatch(LaunchPinDrop(pin))
    }

    private fun poiTypeForCategory(category: String): String {
        return when (category) {
            "swimming", "beach" -> Symbol.POI.Types.SWIMMING
            "supermarket", "convenience" -> Symbol.POI.Types.CONVENIENCE_STORE
            "viewpoint" -> Symbol.POI.Types.VIEWPOINT
            else -> Symbol.POI.Types.GENERIC
        }
    }

    private fun bindSwitch(
        switch: SwitchMaterial,
        flow: Flow<Boolean>,
        setter: suspend (Boolean) -> Unit
    ) {
        switch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { setter(checked) }
        }
        lifecycleScope.launch {
            flow.collect { enabled ->
                switch.setOnCheckedChangeListener(null)
                switch.isChecked = enabled
                switch.setOnCheckedChangeListener { _, checked ->
                    lifecycleScope.launch { setter(checked) }
                }
            }
        }
    }

    companion object {
        private val BEACH_CATEGORIES = listOf("swimming", "beach")
        private val STORE_CATEGORIES = listOf("supermarket", "convenience")
        private val VIEWPOINT_CATEGORIES = listOf("viewpoint")
    }
}
