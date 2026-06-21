package com.karoopoi.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "poi_prefs")

object PoiPreferences {
    private var instance: PoiPreferencesImpl? = null

    fun getInstance(context: Context): PoiPreferencesImpl {
        return instance ?: PoiPreferencesImpl(context.applicationContext).also { instance = it }
    }
}

class PoiPreferencesImpl private constructor(val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    val categoryBeachesSwimming: Flow<Boolean> = dataStore.data.map { it[BEACHES_SWIMMING] ?: true }
    val categoryStores: Flow<Boolean> = dataStore.data.map { it[STORES] ?: true }
    val categoryViewpoint: Flow<Boolean> = dataStore.data.map { it[VIEWPOINT] ?: true }
    val thresholdMeters: Flow<Int> = dataStore.data.map { it[THRESHOLD] ?: 500 }

    suspend fun setBeachesSwimming(value: Boolean) { dataStore.edit { it[BEACHES_SWIMMING] = value } }
    suspend fun setStores(value: Boolean) { dataStore.edit { it[STORES] = value } }
    suspend fun setViewpoint(value: Boolean) { dataStore.edit { it[VIEWPOINT] = value } }
    suspend fun setThreshold(value: Int) { dataStore.edit { it[THRESHOLD] = value } }

    companion object {
        val BEACHES_SWIMMING_KEY = booleanPreferencesKey("category_beaches_swimming")
        val STORES_KEY = booleanPreferencesKey("category_stores")
        val VIEWPOINT_KEY = booleanPreferencesKey("category_viewpoint")
        val THRESHOLD_KEY = intPreferencesKey("threshold_meters")

        private val BEACHES_SWIMMING = BEACHES_SWIMMING_KEY
        private val STORES = STORES_KEY
        private val VIEWPOINT = VIEWPOINT_KEY
        private val THRESHOLD = THRESHOLD_KEY
    }
}