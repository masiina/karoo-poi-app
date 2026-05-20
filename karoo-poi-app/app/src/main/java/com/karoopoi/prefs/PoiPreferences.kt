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

    val categorySwimming: Flow<Boolean> = dataStore.data.map { it[SWIMMING] ?: true }
    val categoryBeach: Flow<Boolean> = dataStore.data.map { it[BEACH] ?: true }
    val categorySupermarket: Flow<Boolean> = dataStore.data.map { it[SUPERMARKET] ?: true }
    val categoryConvenience: Flow<Boolean> = dataStore.data.map { it[CONVENIENCE] ?: true }
    val thresholdMeters: Flow<Int> = dataStore.data.map { it[THRESHOLD] ?: 500 }

    suspend fun setSwimming(value: Boolean) { dataStore.edit { it[SWIMMING] = value } }
    suspend fun setBeach(value: Boolean) { dataStore.edit { it[BEACH] = value } }
    suspend fun setSupermarket(value: Boolean) { dataStore.edit { it[SUPERMARKET] = value } }
    suspend fun setConvenience(value: Boolean) { dataStore.edit { it[CONVENIENCE] = value } }
    suspend fun setThreshold(value: Int) { dataStore.edit { it[THRESHOLD] = value } }

    companion object {
        val SWIMMING_KEY = booleanPreferencesKey("category_swimming")
        val BEACH_KEY = booleanPreferencesKey("category_beach")
        val SUPERMARKET_KEY = booleanPreferencesKey("category_supermarket")
        val CONVENIENCE_KEY = booleanPreferencesKey("category_convenience")
        val THRESHOLD_KEY = intPreferencesKey("threshold_meters")

        private val SWIMMING = SWIMMING_KEY
        private val BEACH = BEACH_KEY
        private val SUPERMARKET = SUPERMARKET_KEY
        private val CONVENIENCE = CONVENIENCE_KEY
        private val THRESHOLD = THRESHOLD_KEY
    }
}