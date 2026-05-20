package com.karoopoi.extension

import android.util.Log
import com.karoopoi.engine.PoiFilterEngine
import com.karoopoi.engine.PoiResult
import com.karoopoi.geo.GeoUtils
import com.karoopoi.geo.LatLng
import com.karoopoi.prefs.PoiPreferencesImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PoiDisplayItem(
    val icon: String,
    val name: String,
    val distance: String
)

enum class DisplayState {
    NO_ROUTE,
    WAITING_GPS,
    ACTIVE
}

object PoiStateManager {
    private const val TAG = "PoiStateManager"
    private const val CATEGORY_SWIMMING = "swimming"
    private const val CATEGORY_BEACH = "beach"
    private const val CATEGORY_SUPERMARKET = "supermarket"
    private const val CATEGORY_CONVENIENCE = "convenience"

    private val BEACH_CATEGORIES = setOf(CATEGORY_SWIMMING, CATEGORY_BEACH)
    private val STORE_CATEGORIES = setOf(CATEGORY_SUPERMARKET, CATEGORY_CONVENIENCE)

    // Minimum movement in meters before recomputing POIs.
    // At 30 km/h (~8 m/s), 30m ≈ 3.5s — display distances still update each tick.
    private const val MIN_UPDATE_METERS = 30.0

    private val _displayState = MutableStateFlow(DisplayState.NO_ROUTE)
    val displayState: StateFlow<DisplayState> = _displayState.asStateFlow()

    private val _beachDisplayItems = MutableStateFlow<List<PoiDisplayItem>>(emptyList())
    val beachDisplayItems: StateFlow<List<PoiDisplayItem>> = _beachDisplayItems.asStateFlow()

    private val _storeDisplayItems = MutableStateFlow<List<PoiDisplayItem>>(emptyList())
    val storeDisplayItems: StateFlow<List<PoiDisplayItem>> = _storeDisplayItems.asStateFlow()

    private val _beachPois = MutableStateFlow<List<PoiResult>>(emptyList())
    val beachPois: StateFlow<List<PoiResult>> = _beachPois.asStateFlow()

    private val _storePois = MutableStateFlow<List<PoiResult>>(emptyList())
    val storePois: StateFlow<List<PoiResult>> = _storePois.asStateFlow()

    // Generation counter — bumped on clearState/onRouteLoaded to invalidate
    // in-flight update() calls that may re-populate stale data after clearing
    @Volatile
    private var stateGeneration = 0L

    // Spatial tolerance cache — avoid re-querying DB when user barely moved
    @Volatile
    private var lastUpdateLocation: LatLng? = null
    @Volatile
    private var lastCategories: Set<String>? = null
    @Volatile
    private var lastThreshold: Int? = null
    @Volatile
    private var lastBeachPois: List<PoiResult>? = null
    @Volatile
    private var lastStorePois: List<PoiResult>? = null

    // Cached preferences — updated reactively, avoiding .first() disk read per tick
    @Volatile
    private var cachedSwimming: Boolean = true
    @Volatile
    private var cachedBeach: Boolean = true
    @Volatile
    private var cachedSupermarket: Boolean = true
    @Volatile
    private var cachedConvenience: Boolean = true
    @Volatile
    private var cachedThreshold: Int = 500

    suspend fun observePreferences(preferences: PoiPreferencesImpl) {
        preferences.dataStore.data.collect { prefs ->
            cachedSwimming = prefs[PoiPreferencesImpl.SWIMMING_KEY] ?: true
            cachedBeach = prefs[PoiPreferencesImpl.BEACH_KEY] ?: true
            cachedSupermarket = prefs[PoiPreferencesImpl.SUPERMARKET_KEY] ?: true
            cachedConvenience = prefs[PoiPreferencesImpl.CONVENIENCE_KEY] ?: true
            cachedThreshold = prefs[PoiPreferencesImpl.THRESHOLD_KEY] ?: 500
        }
    }

    private suspend fun List<PoiResult>.toDisplayItems(location: LatLng, iconForCategory: (String) -> String): List<PoiDisplayItem> {
        return distinctBy { it.name ?: it.category }.take(5).map {
            val icon = iconForCategory(it.category)
            val gpsDist = GeoUtils.distance(location, LatLng(it.lat, it.lon))
            val dist = if (gpsDist >= 1000) {
                "${(gpsDist / 1000).toInt()}km"
            } else {
                "${gpsDist.toInt()}m"
            }
            PoiDisplayItem(icon, it.name ?: it.category, dist)
        }
    }

    fun clearState() {
        Log.d(TAG, "clearState called — resetting all state")
        stateGeneration++
        _displayState.value = DisplayState.NO_ROUTE
        _beachDisplayItems.value = emptyList()
        _storeDisplayItems.value = emptyList()
        _beachPois.value = emptyList()
        _storePois.value = emptyList()
        lastUpdateLocation = null
        lastBeachPois = null
        lastStorePois = null
    }

    fun onRouteLoaded() {
        if (_displayState.value == DisplayState.NO_ROUTE) {
            Log.d(TAG, "Route loaded, transitioning to WAITING_GPS")
            stateGeneration++
            _displayState.value = DisplayState.WAITING_GPS
        }
    }

    suspend fun update(
        location: LatLng,
        routePolyline: String?,
        engine: PoiFilterEngine,
        hasGpsFix: Boolean = false
    ) {
        val genAtEntry = stateGeneration
        Log.d(TAG, "update called: location=$location, routePolyline=${routePolyline?.take(50)}, hasGpsFix=$hasGpsFix")
        if (routePolyline.isNullOrEmpty()) {
            Log.d(TAG, "No route polyline, clearing display")
            _displayState.value = DisplayState.NO_ROUTE
            _beachDisplayItems.value = emptyList()
            _storeDisplayItems.value = emptyList()
            _beachPois.value = emptyList()
            _storePois.value = emptyList()
            lastUpdateLocation = null
            lastBeachPois = null
            lastStorePois = null
            return
        }
        if (!hasGpsFix) {
            Log.d(TAG, "Route loaded but no GPS fix, showing waiting message")
            _displayState.value = DisplayState.WAITING_GPS
            _beachDisplayItems.value = emptyList()
            _storeDisplayItems.value = emptyList()
            _beachPois.value = emptyList()
            _storePois.value = emptyList()
            lastUpdateLocation = null
            lastBeachPois = null
            lastStorePois = null
            return
        }

        // Read cached preferences — no disk I/O
        val swimmingEnabled = cachedSwimming
        val beachEnabled = cachedBeach
        val supermarketEnabled = cachedSupermarket
        val convenienceEnabled = cachedConvenience
        val threshold = cachedThreshold

        val activeBeachCategories = buildSet {
            if (swimmingEnabled) add(CATEGORY_SWIMMING)
            if (beachEnabled) add(CATEGORY_BEACH)
        }
        val activeStoreCategories = buildSet {
            if (supermarketEnabled) add(CATEGORY_SUPERMARKET)
            if (convenienceEnabled) add(CATEGORY_CONVENIENCE)
        }
        val allCategories = activeBeachCategories + activeStoreCategories
        Log.d(TAG, "Active categories: $allCategories")

        if (allCategories.isEmpty()) {
            _displayState.value = DisplayState.ACTIVE
            _beachDisplayItems.value = emptyList()
            _storeDisplayItems.value = emptyList()
            _beachPois.value = emptyList()
            _storePois.value = emptyList()
            lastUpdateLocation = null
            lastBeachPois = null
            lastStorePois = null
            return
        }
        Log.d(TAG, "Threshold: ${threshold}m")

        // Spatial tolerance: skip full recomputation if user barely moved
        val sameCats = allCategories == lastCategories && threshold == lastThreshold
        if (sameCats && lastUpdateLocation != null && lastBeachPois != null && lastStorePois != null) {
            val moved = GeoUtils.distance(location, lastUpdateLocation!!)
            if (moved < MIN_UPDATE_METERS) {
                // Still update display distances (they change with every GPS tick)
                _beachDisplayItems.value = lastBeachPois!!.toDisplayItems(location) { "\uD83C\uDFD6" }
                _storeDisplayItems.value = lastStorePois!!.toDisplayItems(location) { "\uD83C\uDFEA" }
                return  // Skip DB query + projection pipeline
            }
        }

        try {
            val pois = engine.findNextPois(location, routePolyline, allCategories, threshold)
            // Abort if state was cleared (route removed) while we were querying
            if (stateGeneration != genAtEntry) {
                Log.d(TAG, "Stale generation after DB query — discarding results")
                return
            }
            Log.d(TAG, "Found ${pois.size} POIs")

            val beachPois = pois.filter { it.category in BEACH_CATEGORIES }
            val storePois = pois.filter { it.category in STORE_CATEGORIES }

            _beachPois.value = beachPois
            _storePois.value = storePois

            // Cache for spatial tolerance
            lastUpdateLocation = location
            lastCategories = allCategories
            lastThreshold = threshold
            lastBeachPois = beachPois
            lastStorePois = storePois

            _beachDisplayItems.value = beachPois.toDisplayItems(location) { "\uD83C\uDFD6" }
            _storeDisplayItems.value = storePois.toDisplayItems(location) { "\uD83C\uDFEA" }
            _displayState.value = DisplayState.ACTIVE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update POIs", e)
            _beachDisplayItems.value = emptyList()
            _storeDisplayItems.value = emptyList()
            _beachPois.value = emptyList()
            _storePois.value = emptyList()
            lastUpdateLocation = null
            lastBeachPois = null
            lastStorePois = null
        }
    }
}