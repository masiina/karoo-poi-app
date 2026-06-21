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
    LOADED,    // Route loaded, POIs shown from route start (no GPS yet)
    ACTIVE     // GPS locked, POIs relative to user position
}

object PoiStateManager {
    private const val TAG = "PoiStateManager"
    private const val CATEGORY_SWIMMING = "swimming"
    private const val CATEGORY_BEACH = "beach"
    private const val CATEGORY_SUPERMARKET = "supermarket"
    private const val CATEGORY_CONVENIENCE = "convenience"
    private const val CATEGORY_VIEWPOINT = "viewpoint"

    private val BEACH_CATEGORIES = setOf(CATEGORY_SWIMMING, CATEGORY_BEACH)
    private val STORE_CATEGORIES = setOf(CATEGORY_SUPERMARKET, CATEGORY_CONVENIENCE)
    private val VIEWPOINT_CATEGORIES = setOf(CATEGORY_VIEWPOINT)

    // Minimum movement in meters before recomputing POIs.
    // At 30 km/h (~8 m/s), 30m ≈ 3.5s — display distances still update each tick.
    private const val MIN_UPDATE_METERS = 30.0

    // Hysteresis for detecting passed POIs: a POI is considered "passed" when
    // the straight-line GPS distance to it increases by more than this amount.
    // Prevents false positives from GPS jitter (typically 3-5m).
    private const val PASSED_HYSTERESIS_METERS = 5.0

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

    private val _viewpointDisplayItems = MutableStateFlow<List<PoiDisplayItem>>(emptyList())
    val viewpointDisplayItems: StateFlow<List<PoiDisplayItem>> = _viewpointDisplayItems.asStateFlow()

    private val _viewpointPois = MutableStateFlow<List<PoiResult>>(emptyList())
    val viewpointPois: StateFlow<List<PoiResult>> = _viewpointPois.asStateFlow()

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
    @Volatile
    private var lastViewpointPois: List<PoiResult>? = null

    // Tracks previous GPS distance per POI to detect when the user has passed.
    // Keyed by osmId. Updated every tick in toDisplayItems().
    private val previousGpsDistances = mutableMapOf<String, Double>()

    // Cached preferences — updated reactively, avoiding .first() disk read per tick
    @Volatile
    private var cachedBeachesSwimming: Boolean = true
    @Volatile
    private var cachedStores: Boolean = true
    @Volatile
    private var cachedViewpoint: Boolean = true
    @Volatile
    private var cachedThreshold: Int = 500

    suspend fun observePreferences(preferences: PoiPreferencesImpl) {
        preferences.dataStore.data.collect { prefs ->
            cachedBeachesSwimming = prefs[PoiPreferencesImpl.BEACHES_SWIMMING_KEY] ?: true
            cachedStores = prefs[PoiPreferencesImpl.STORES_KEY] ?: true
            cachedViewpoint = prefs[PoiPreferencesImpl.VIEWPOINT_KEY] ?: true
            cachedThreshold = prefs[PoiPreferencesImpl.THRESHOLD_KEY] ?: 500
        }
    }

    private fun List<PoiResult>.toDisplayItems(location: LatLng, iconForCategory: (String) -> String): List<PoiDisplayItem> {
        return this
            .filter { poi ->
                val gpsDist = GeoUtils.distance(location, LatLng(poi.lat, poi.lon))
                val prevDist = previousGpsDistances[poi.osmId]
                previousGpsDistances[poi.osmId] = gpsDist
                // Exclude POIs the user has passed (GPS distance increasing)
                prevDist == null || gpsDist <= prevDist + PASSED_HYSTERESIS_METERS
            }
            .distinctBy { it.name ?: it.category }
            .take(5)
            .map { poi ->
                val icon = iconForCategory(poi.category)
                val gpsDist = GeoUtils.distance(location, LatLng(poi.lat, poi.lon))
                val dist = if (gpsDist >= 1000) {
                    "${(gpsDist / 1000).toInt()}km"
                } else {
                    "${gpsDist.toInt()}m"
                }
                PoiDisplayItem(icon, poi.name ?: poi.category, dist)
            }
    }

    fun clearState() {
        Log.d(TAG, "clearState called — resetting all state")
        stateGeneration++
        _displayState.value = DisplayState.NO_ROUTE
        _beachDisplayItems.value = emptyList()
        _storeDisplayItems.value = emptyList()
        _viewpointDisplayItems.value = emptyList()
        _beachPois.value = emptyList()
        _storePois.value = emptyList()
        _viewpointPois.value = emptyList()
        lastUpdateLocation = null
        lastBeachPois = null
        lastStorePois = null
        lastViewpointPois = null
        previousGpsDistances.clear()
    }

    fun onRouteLoaded() {
        if (_displayState.value == DisplayState.NO_ROUTE) {
            Log.d(TAG, "Route loaded, transitioning to LOADED")
            stateGeneration++
            _displayState.value = DisplayState.LOADED
        }
    }

    /**
     * Perform an initial POI query using the route start point.
     * Called when a route is loaded, before GPS fix is available,
     * so POIs appear on the map immediately.
     */
    suspend fun initialRouteQuery(
        routeStart: LatLng,
        routePolyline: String,
        engine: PoiFilterEngine
    ) {
        Log.d(TAG, "initialRouteQuery: querying POIs from route start $routeStart")
        val genAtEntry = stateGeneration
        previousGpsDistances.clear()
        val beachesSwimmingEnabled = cachedBeachesSwimming
        val storesEnabled = cachedStores
        val viewpointEnabled = cachedViewpoint
        val threshold = cachedThreshold

        val activeBeachCategories = if (beachesSwimmingEnabled) BEACH_CATEGORIES else emptySet()
        val activeStoreCategories = if (storesEnabled) STORE_CATEGORIES else emptySet()
        val activeViewpointCategories = buildSet {
            if (viewpointEnabled) add(CATEGORY_VIEWPOINT)
        }
        val allCategories = activeBeachCategories + activeStoreCategories + activeViewpointCategories

        if (allCategories.isEmpty()) {
            _displayState.value = DisplayState.LOADED
            _beachDisplayItems.value = emptyList()
            _storeDisplayItems.value = emptyList()
            _viewpointDisplayItems.value = emptyList()
            _beachPois.value = emptyList()
            _storePois.value = emptyList()
            _viewpointPois.value = emptyList()
            return
        }

        try {
            val pois = engine.findNextPois(routeStart, routePolyline, allCategories, threshold)
            if (stateGeneration != genAtEntry) {
                Log.d(TAG, "Stale generation after initial query — discarding results")
                return
            }
            Log.d(TAG, "Initial query found ${pois.size} POIs from route start")

            val beachPois = pois.filter { it.category in BEACH_CATEGORIES }
            val storePois = pois.filter { it.category in STORE_CATEGORIES }
            val viewpointPois = pois.filter { it.category in VIEWPOINT_CATEGORIES }

            _beachPois.value = beachPois
            _storePois.value = storePois
            _viewpointPois.value = viewpointPois

            // Cache for spatial tolerance — so update() can skip re-querying
            // if user barely moved from route start
            lastUpdateLocation = routeStart
            lastCategories = allCategories
            lastThreshold = threshold
            lastBeachPois = beachPois
            lastStorePois = storePois
            lastViewpointPois = viewpointPois

            // Display items use route start as reference point (indicated by "~" prefix)
            _beachDisplayItems.value = beachPois.toDisplayItems(routeStart) { "🏖" }
            _storeDisplayItems.value = storePois.toDisplayItems(routeStart) { "🏪" }
            _viewpointDisplayItems.value = viewpointPois.toDisplayItems(routeStart) { "⛰" }

            // Only transition to LOADED if we were still in NO_ROUTE/LOADED state
            // (don't downgrade from ACTIVE back to LOADED)
            if (_displayState.value != DisplayState.ACTIVE) {
                _displayState.value = DisplayState.LOADED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed initial POI query", e)
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
            _viewpointDisplayItems.value = emptyList()
            _beachPois.value = emptyList()
            _storePois.value = emptyList()
            _viewpointPois.value = emptyList()
            lastUpdateLocation = null
            lastBeachPois = null
            lastStorePois = null
            lastViewpointPois = null
            previousGpsDistances.clear()
            return
        }
        if (!hasGpsFix) {
            // No GPS fix yet — keep showing POIs from initial route query (if any).
            // Don't clear the display; just don't update with stale positioning.
            Log.d(TAG, "Route loaded but no GPS fix, keeping current display")
            if (_displayState.value == DisplayState.NO_ROUTE) {
                _displayState.value = DisplayState.LOADED
            }
            return
        }

        // Read cached preferences — no disk I/O
        val beachesSwimmingEnabled = cachedBeachesSwimming
        val storesEnabled = cachedStores
        val viewpointEnabled = cachedViewpoint
        val threshold = cachedThreshold

        val activeBeachCategories = if (beachesSwimmingEnabled) BEACH_CATEGORIES else emptySet()
        val activeStoreCategories = if (storesEnabled) STORE_CATEGORIES else emptySet()
        val activeViewpointCategories = buildSet {
            if (viewpointEnabled) add(CATEGORY_VIEWPOINT)
        }
        val allCategories = activeBeachCategories + activeStoreCategories + activeViewpointCategories
        Log.d(TAG, "Active categories: $allCategories")

        if (allCategories.isEmpty()) {
            _displayState.value = DisplayState.ACTIVE
            _beachDisplayItems.value = emptyList()
            _storeDisplayItems.value = emptyList()
            _viewpointDisplayItems.value = emptyList()
            _beachPois.value = emptyList()
            _storePois.value = emptyList()
            _viewpointPois.value = emptyList()
            lastUpdateLocation = null
            lastBeachPois = null
            lastStorePois = null
            lastViewpointPois = null
            previousGpsDistances.clear()
            return
        }
        Log.d(TAG, "Threshold: ${threshold}m")

        // Spatial tolerance: skip full recomputation if user barely moved
        val sameCats = allCategories == lastCategories && threshold == lastThreshold
        if (sameCats && lastUpdateLocation != null && lastBeachPois != null && lastStorePois != null && lastViewpointPois != null) {
            val moved = GeoUtils.distance(location, lastUpdateLocation!!)
            if (moved < MIN_UPDATE_METERS) {
                // GPS locked — update display distances and ensure we're ACTIVE.
                // Pre-lock GPS locations are unreliable and could be far from
                // the route start, causing all POIs to be filtered as "passed".
                if (hasGpsFix) {
                    _beachDisplayItems.value = lastBeachPois!!.toDisplayItems(location) { "\uD83C\uDFD6" }
                    _storeDisplayItems.value = lastStorePois!!.toDisplayItems(location) { "\uD83C\uDFEA" }
                    _viewpointDisplayItems.value = lastViewpointPois!!.toDisplayItems(location) { "\uD83C\uDFD4" }
                    _displayState.value = DisplayState.ACTIVE
                }
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

            if (pois.isEmpty()) {
                // GPS returned a bad/cached location before lock — findNextPois
                // found nothing ahead. Keep current display items untouched
                // (from initialRouteQuery or last good update) so the list
                // doesn't go blank.
                Log.d(TAG, "No POIs found — keeping current display (likely pre-lock GPS)")
                return
            }

            val beachPois = pois.filter { it.category in BEACH_CATEGORIES }
            val storePois = pois.filter { it.category in STORE_CATEGORIES }
            val viewpointPois = pois.filter { it.category in VIEWPOINT_CATEGORIES }

            _beachPois.value = beachPois
            _storePois.value = storePois
            _viewpointPois.value = viewpointPois

            // Cache for spatial tolerance
            lastUpdateLocation = location
            lastCategories = allCategories
            lastThreshold = threshold
            lastBeachPois = beachPois
            lastStorePois = storePois
            lastViewpointPois = viewpointPois

            _beachDisplayItems.value = beachPois.toDisplayItems(location) { "\uD83C\uDFD6" }
            _storeDisplayItems.value = storePois.toDisplayItems(location) { "\uD83C\uDFEA" }
            _viewpointDisplayItems.value = viewpointPois.toDisplayItems(location) { "\uD83C\uDFD4" }
            _displayState.value = DisplayState.ACTIVE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update POIs", e)
            _beachDisplayItems.value = emptyList()
            _storeDisplayItems.value = emptyList()
            _viewpointDisplayItems.value = emptyList()
            _beachPois.value = emptyList()
            _storePois.value = emptyList()
            _viewpointPois.value = emptyList()
            lastUpdateLocation = null
            lastBeachPois = null
            lastStorePois = null
            lastViewpointPois = null
            previousGpsDistances.clear()
        }
    }
}