package com.karoopoi.extension

import android.util.Log
import androidx.room.Room
import com.karoopoi.data.PoiDatabase
import com.karoopoi.engine.PoiFilterEngine
import com.karoopoi.geo.LatLng
import com.karoopoi.prefs.PoiPreferences
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class PoiExtension : KarooExtension("poi", "1") {
    override val types = listOf(BeachDataType(), StoreDataType())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val routePolyline = MutableStateFlow<String?>(null)
    private val hasGpsFix = MutableStateFlow(false)
    private var mapScope: CoroutineScope? = null
    private val previousSymbolIds = MutableStateFlow<List<String>>(emptyList())
    private val symbolCounter = AtomicInteger(0)
    private lateinit var karooSystem: KarooSystemService
    private val db by lazy {
        Room.databaseBuilder(applicationContext, PoiDatabase::class.java, "pois_data.db")
            .createFromAsset("pois.db")
            .addMigrations(PoiDatabase.MIGRATION_1_2)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("PoiExtension", "onCreate called")
        karooSystem = KarooSystemService(this)
        val engine = PoiFilterEngine(db.poiDao())
        val preferences = PoiPreferences.getInstance(applicationContext)

        karooSystem.connect { connected ->
            Log.d("PoiExtension", "karooSystem.connect callback: connected=$connected")
        }

        // Start caching preferences reactively — avoids disk read on every GPS tick
        scope.launch {
            PoiStateManager.observePreferences(preferences)
        }

        karooSystem.addConsumer<OnNavigationState>(
            OnNavigationState.Params,
            onError = { err -> Log.e("PoiExtension", "Navigation consumer error: $err") },
            onComplete = { Log.d("PoiExtension", "Navigation consumer completed") },
            onEvent = { event ->
                Log.d("PoiExtension", "OnNavigationState event received: ${event.state::class.java.simpleName}")
                val polyline = when (val s = event.state) {
                    is OnNavigationState.NavigationState.NavigatingRoute -> {
                        Log.d("PoiExtension", "NavigatingRoute polyline length=${s.routePolyline.length}")
                        s.routePolyline
                    }
                    is OnNavigationState.NavigationState.NavigatingToDestination -> {
                        Log.d("PoiExtension", "NavigatingToDestination polyline length=${s.polyline.length}")
                        s.polyline
                    }
                    else -> {
                        Log.d("PoiExtension", "Navigation state has no polyline")
                        hasGpsFix.value = false
                        routePolyline.value = null
                        PoiStateManager.clearState()
                        null
                    }
                }
                if (polyline != null) {
                    routePolyline.value = polyline
                    PoiStateManager.onRouteLoaded()
                }
            }
        )
        karooSystem.addConsumer<OnLocationChanged>(
            onError = { err -> Log.e("PoiExtension", "Location consumer error: $err") },
            onComplete = { Log.d("PoiExtension", "Location consumer completed") },
            onEvent = { loc ->
                Log.d("PoiExtension", "OnLocationChanged: lat=${loc.lat}, lng=${loc.lng}")
                hasGpsFix.value = true
                scope.launch {
                    PoiStateManager.update(
                        location = LatLng(loc.lat, loc.lng),
                        routePolyline = routePolyline.value,
                        engine = engine,
                        hasGpsFix = hasGpsFix.value
                    )
                }
            }
        )
    }

    override fun startMap(emitter: Emitter<MapEffect>) {
        Log.d("PoiExtension", "startMap called — map layer active")
        mapScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        emitter.onNext(HideSymbols(previousSymbolIds.value.toList()))
        previousSymbolIds.value = emptyList()
        symbolCounter.set(0)

        mapScope!!.launch {
            PoiStateManager.beachPois.combine(PoiStateManager.storePois) { beach, store ->
                beach + store
            }.distinctUntilChanged().collect { pois ->
                if (previousSymbolIds.value.isNotEmpty()) {
                    emitter.onNext(HideSymbols(previousSymbolIds.value.toList()))
                    previousSymbolIds.value = emptyList()
                }
                if (pois.isEmpty()) return@collect
                val ids = mutableListOf<String>()
                val symbols = pois.map { poi ->
                    val id = "poi_${symbolCounter.getAndIncrement()}"
                    ids.add(id)
                    val poiType = when (poi.category) {
                        "supermarket", "convenience" -> Symbol.POI.Types.CONVENIENCE_STORE
                        else -> Symbol.POI.Types.SWIMMING
                    }
                    Symbol.POI(
                        id = id,
                        lat = poi.lat,
                        lng = poi.lon,
                        type = poiType,
                        name = poi.name ?: poi.category,
                        distancesAlongRoute = listOf(poi.distanceAlongRoute)
                    )
                }
                previousSymbolIds.value = ids
                Log.d("PoiExtension", "Showing ${symbols.size} POI markers on map")
                emitter.onNext(ShowSymbols(symbols))
            }
        }

        emitter.setCancellable {
            Log.d("PoiExtension", "Stopping map effect")
            emitter.onNext(HideSymbols(previousSymbolIds.value.toList()))
            previousSymbolIds.value = emptyList()
            symbolCounter.set(0)
            mapScope?.cancel()
            mapScope = null
        }
    }

    override fun onDestroy() {
        Log.d("PoiExtension", "onDestroy called")
        mapScope?.cancel()
        scope.cancel()
        db.close()
        super.onDestroy()
    }
}