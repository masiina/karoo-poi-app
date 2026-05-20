# Karoo POI App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a karoo-ext Android extension for Karoo 3 that displays the next 5 swimming/beach POIs ahead on the current route using a pre-built OSM SQLite database, with a settings screen for category toggles and route-distance threshold.

**Architecture:** Greenfield Kotlin Android app with karoo-ext library. Build-time Python/osmium pipeline generates a SQLite POI database from Geofabrik PBF, bundled in APK assets. Runtime extension service consumes Karoo location/navigation events, queries the local Room database via a filter engine, and updates a RemoteViews custom field. Settings are persisted with DataStore.

**Tech Stack:** Kotlin, karoo-ext, Android Room, Android DataStore, Gradle, osmium-tool, Python 3

---

## File Structure

| File | Responsibility |
|------|----------------|
| `settings.gradle.kts` | Gradle project settings |
| `build.gradle.kts` | Root build script (plugins) |
| `app/build.gradle.kts` | App module, dependencies, custom DB generation task |
| `app/src/main/AndroidManifest.xml` | App manifest with Extension service |
| `app/src/main/res/values/strings.xml` | UI strings |
| `app/src/main/res/xml/extension_info.xml` | karoo-ext capabilities declaration |
| `app/src/main/res/layout/remote_views_poi_list.xml` | RemoteViews layout for custom field |
| `app/src/main/res/layout/activity_settings.xml` | Settings screen layout |
| `build_scripts/poi_pipeline.py` | Build pipeline: PBF → SQLite |
| `app/src/main/java/com/karoopoi/data/PoiEntity.kt` | Room entity |
| `app/src/main/java/com/karoopoi/data/PoiDao.kt` | Room DAO |
| `app/src/main/java/com/karoopoi/data/PoiDatabase.kt` | Room database |
| `app/src/main/java/com/karoopoi/geo/LatLng.kt` | Simple lat/lon data class |
| `app/src/main/java/com/karoopoi/geo/GeoUtils.kt` | Haversine & point-to-segment distance |
| `app/src/main/java/com/karoopoi/geo/PolylineDecoder.kt` | Google encoded polyline decoder |
| `app/src/main/java/com/karoopoi/engine/PoiResult.kt` | Result data class |
| `app/src/main/java/com/karoopoi/engine/PoiFilterEngine.kt` | Query, filter, sort logic |
| `app/src/main/java/com/karoopoi/prefs/PoiPreferences.kt` | DataStore wrapper |
| `app/src/main/java/com/karoopoi/extension/PoiStateManager.kt` | Shared state between service and view |
| `app/src/main/java/com/karoopoi/extension/PoiDataType.kt` | karoo-ext DataTypeImpl |
| `app/src/main/java/com/karoopoi/extension/PoiExtension.kt` | KarooExtension service |
| `app/src/main/java/com/karoopoi/ui/SettingsActivity.kt` | Settings screen |
| `app/src/test/java/com/karoopoi/geo/PolylineEncoder.kt` | Test helper: encoder |
| `app/src/test/java/com/karoopoi/engine/PoiFilterEngineTest.kt` | Unit tests for filter engine |
| `app/src/androidTest/java/com/karoopoi/ui/SettingsActivityTest.kt` | Espresso UI tests |

---

## Task 1: Project Scaffold & Gradle Setup

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Write `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("USERNAME"))
                password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("TOKEN"))
            }
        }
    }
}
rootProject.name = "karoo-poi"
include(":app")
```

- [ ] **Step 2: Write root `build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("com.google.devtools.ksp") version "1.9.20-1.0.14" apply false
}
```

- [ ] **Step 3: Write `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.karoopoi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.karoopoi"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets["main"].assets.srcDir("$buildDir/generated/assets")
}

dependencies {
    implementation("io.hammerhead:karoo-ext:1.1.8")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("com.google.android.material:material:1.11.0")
}

tasks.register<Exec>("generatePoiDb") {
    group = "build"
    description = "Generate POI SQLite DB from OSM PBF"
    val pbfFile = project.findProperty("poi.pbf")?.toString() ?: "data/region.osm.pbf"
    val outputDb = layout.buildDirectory.file("generated/assets/pois.db").get().asFile
    outputs.file(outputDb)
    doFirst { outputDb.parentFile.mkdirs() }
    commandLine("python3", "build_scripts/poi_pipeline.py", "--pbf", pbfFile, "--output", outputDb)
}

tasks.named("mergeReleaseAssets") { dependsOn("generatePoiDb") }
tasks.named("mergeDebugAssets") { dependsOn("generatePoiDb") }
```

- [ ] **Step 4: Write `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.karoopoi">

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Material3.DayNight.NoActionBar">

        <activity
            android:name=".ui.SettingsActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".extension.PoiExtension"
            android:exported="true">
            <intent-filter>
                <action android:name="io.hammerhead.karooext.KAROO_EXTENSION" />
            </intent-filter>
            <meta-data
                android:name="io.hammerhead.karooext.EXTENSION_INFO"
                android:resource="@xml/extension_info" />
        </service>
    </application>
</manifest>
```

- [ ] **Step 5: Write `app/src/main/res/values/strings.xml`**

```xml
<resources>
    <string name="app_name">Karoo POI</string>
    <string name="extension_name">Karoo POI</string>
    <string name="next_pois_name">Next 5 POIs</string>
    <string name="next_pois_desc">Shows the next swimming and beach POIs ahead on the route</string>
</resources>
```

- [ ] **Step 6: Verify Gradle sync**

Run:
```bash
./gradlew app:dependencies --configuration compileClasspath | grep karoo-ext
```

Expected: `io.hammerhead:karoo-ext:1.1.8` listed.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml
git commit -m "chore: project scaffold and gradle setup"
```

---

## Task 2: Build Pipeline Script

**Files:**
- Create: `build_scripts/poi_pipeline.py`

- [ ] **Step 1: Write `build_scripts/poi_pipeline.py`**

```python
#!/usr/bin/env python3
import argparse
import json
import sqlite3
import subprocess
import os
import tempfile

CATEGORY_MAP = {
    "leisure=swimming_area": "swimming",
    "sport=swimming": "swimming",
    "natural=beach": "beach",
}


def run_export(pbf_path: str, geojson_path: str):
    subprocess.run(
        ["osmium", "export", pbf_path, "-f", "geojsonseq", "-o", geojson_path, "--overwrite"],
        check=True,
    )


def parse_and_write(geojson_path: str, db_path: str):
    conn = sqlite3.connect(db_path)
    conn.execute(
        "CREATE TABLE IF NOT EXISTS pois ("
        "id INTEGER PRIMARY KEY, osm_id TEXT UNIQUE, name TEXT, "
        "lat REAL NOT NULL, lon REAL NOT NULL, category TEXT NOT NULL, tags TEXT)"
    )
    conn.execute("CREATE INDEX IF NOT EXISTS idx_cat ON pois(category)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_latlon ON pois(lat, lon)")
    conn.execute("DELETE FROM pois")

    with open(geojson_path, "r") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            feat = json.loads(line)
            geom = feat.get("geometry", {})
            coords = geom.get("coordinates", [])
            gtype = geom.get("type")

            if gtype == "Point":
                lon, lat = coords[0], coords[1]
            elif gtype == "LineString":
                lons = [c[0] for c in coords]
                lats = [c[1] for c in coords]
                lon = sum(lons) / len(lons)
                lat = sum(lats) / len(lats)
            elif gtype == "Polygon":
                ring = coords[0]
                lons = [c[0] for c in ring]
                lats = [c[1] for c in ring]
                lon = sum(lons) / len(lons)
                lat = sum(lats) / len(lats)
            else:
                continue

            props = feat.get("properties", {})
            tags_json = json.dumps(props)
            osm_id = str(feat.get("id", ""))
            name = props.get("name")

            for tag_key, category in CATEGORY_MAP.items():
                k, v = tag_key.split("=", 1)
                if props.get(k) == v:
                    conn.execute(
                        "INSERT OR IGNORE INTO pois (osm_id, name, lat, lon, category, tags) VALUES (?, ?, ?, ?, ?, ?)",
                        (osm_id, name, lat, lon, category, tags_json),
                    )
                    break

    conn.commit()
    conn.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pbf", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    with tempfile.NamedTemporaryFile(mode="w", suffix=".geojsonseq", delete=False) as tmp:
        tmp_path = tmp.name

    try:
        run_export(args.pbf, tmp_path)
        parse_and_write(tmp_path, args.output)
        print(f"Generated {args.output}")
    finally:
        os.unlink(tmp_path)


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Make executable and verify syntax**

Run:
```bash
chmod +x build_scripts/poi_pipeline.py
python3 -m py_compile build_scripts/poi_pipeline.py
```

Expected: No output (success).

- [ ] **Step 3: Commit**

```bash
git add build_scripts/poi_pipeline.py
git commit -m "build: add POI pipeline script"
```

---

## Task 3: Room Database

**Files:**
- Create: `app/src/main/java/com/karoopoi/data/PoiEntity.kt`
- Create: `app/src/main/java/com/karoopoi/data/PoiDao.kt`
- Create: `app/src/main/java/com/karoopoi/data/PoiDatabase.kt`

- [ ] **Step 1: Write `PoiEntity.kt`**

```kotlin
package com.karoopoi.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pois",
    indices = [
        Index(value = ["category"]),
        Index(value = ["lat", "lon"])
    ]
)
data class PoiEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "osm_id")
    val osmId: String,

    val name: String?,

    val lat: Double,

    val lon: Double,

    val category: String,

    val tags: String?
)
```

- [ ] **Step 2: Write `PoiDao.kt`**

```kotlin
package com.karoopoi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PoiDao {
    @Insert
    suspend fun insertAll(vararg pois: PoiEntity)

    @Query(
        "SELECT * FROM pois WHERE category IN (:categories) " +
        "AND lat BETWEEN :minLat AND :maxLat " +
        "AND lon BETWEEN :minLon AND :maxLon"
    )
    suspend fun findInBoundingBox(
        categories: List<String>,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<PoiEntity>
}
```

- [ ] **Step 3: Write `PoiDatabase.kt`**

```kotlin
package com.karoopoi.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [PoiEntity::class], version = 1, exportSchema = false)
abstract class PoiDatabase : RoomDatabase() {
    abstract fun poiDao(): PoiDao
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/karoopoi/data/
git commit -m "feat: add Room database schema and DAO"
```

---

## Task 4: Geo Utilities & Polyline Decoder

**Files:**
- Create: `app/src/main/java/com/karoopoi/geo/LatLng.kt`
- Create: `app/src/main/java/com/karoopoi/geo/GeoUtils.kt`
- Create: `app/src/main/java/com/karoopoi/geo/PolylineDecoder.kt`

- [ ] **Step 1: Write `LatLng.kt`**

```kotlin
package com.karoopoi.geo

data class LatLng(val lat: Double, val lon: Double)
```

- [ ] **Step 2: Write `GeoUtils.kt`**

```kotlin
package com.karoopoi.geo

import kotlin.math.*

object GeoUtils {
    private const val EARTH_RADIUS = 6371000.0

    fun distance(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val aa = sin(dLat / 2).pow(2) + sin(dLon / 2).pow(2) * cos(lat1) * cos(lat2)
        val c = 2 * atan2(sqrt(aa), sqrt(1 - aa))
        return EARTH_RADIUS * c
    }

    fun pointToSegmentDistance(p: LatLng, s1: LatLng, s2: LatLng): Double {
        val scale = cos(Math.toRadians((s1.lat + s2.lat) / 2))
        val x1 = 0.0
        val y1 = 0.0
        val x2 = (s2.lon - s1.lon) * scale
        val y2 = s2.lat - s1.lat
        val px = (p.lon - s1.lon) * scale
        val py = p.lat - s1.lat
        val dx = x2 - x1
        val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((px - x1) * dx + (py - y1) * dy) / len2
        val clampedT = t.coerceIn(0.0, 1.0)
        val projX = x1 + clampedT * dx
        val projY = y1 + clampedT * dy
        val deltaLatDeg = projY - py
        val deltaLonScaled = projX - px
        val deltaLonDeg = deltaLonScaled / scale
        val dLatMeters = Math.toRadians(deltaLatDeg) * EARTH_RADIUS
        val dLonMeters = Math.toRadians(deltaLonDeg) * EARTH_RADIUS
        return sqrt(dLatMeters * dLatMeters + dLonMeters * dLonMeters)
    }
}
```

- [ ] **Step 3: Write `PolylineDecoder.kt`**

```kotlin
package com.karoopoi.geo

import kotlin.math.pow

object PolylineDecoder {
    fun decode(encoded: String, precision: Int = 5): List<LatLng> {
        val poly = mutableListOf<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0
        val factor = 10.0.pow(precision)

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dLat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dLng

            poly.add(LatLng(lat / factor, lng / factor))
        }
        return poly
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/karoopoi/geo/
git commit -m "feat: add geo utilities and polyline decoder"
```

---

## Task 5: PoiFilterEngine & Unit Tests

**Files:**
- Create: `app/src/main/java/com/karoopoi/engine/PoiResult.kt`
- Create: `app/src/main/java/com/karoopoi/engine/PoiFilterEngine.kt`
- Create: `app/src/test/java/com/karoopoi/geo/PolylineEncoder.kt`
- Create: `app/src/test/java/com/karoopoi/engine/PoiFilterEngineTest.kt`

- [ ] **Step 1: Write `PoiResult.kt`**

```kotlin
package com.karoopoi.engine

data class PoiResult(
    val osmId: String,
    val name: String?,
    val category: String,
    val lat: Double,
    val lon: Double,
    val distanceFromRoute: Double,
    val distanceAlongRoute: Double
)
```

- [ ] **Step 2: Write the failing test `PoiFilterEngineTest.kt`**

```kotlin
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
            PoiEntity(osmId = "1", name = "Far", lat = 0.05, lon = 0.0005, category = "beach", tags = null),
            PoiEntity(osmId = "2", name = "Near", lat = 0.01, lon = 0.0005, category = "swimming", tags = null),
            PoiEntity(osmId = "3", name = "Off", lat = 0.02, lon = 0.01, category = "beach", tags = null)
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
```

- [ ] **Step 3: Run test to confirm it fails**

Run:
```bash
./gradlew app:testDebugUnitTest --tests "com.karoopoi.engine.PoiFilterEngineTest"
```

Expected: `ClassNotFoundException` or `Unresolved reference` for `PoiFilterEngine` and `PolylineEncoder`.

- [ ] **Step 4: Write test helper `PolylineEncoder.kt`**

```kotlin
package com.karoopoi.geo

import kotlin.math.pow
import kotlin.math.roundToInt

object PolylineEncoder {
    fun encode(points: List<LatLng>, precision: Int = 5): String {
        val factor = 10.0.pow(precision)
        var lat = 0
        var lng = 0
        val sb = StringBuilder()
        for (p in points) {
            val latE5 = (p.lat * factor).roundToInt()
            val lngE5 = (p.lon * factor).roundToInt()
            sb.append(encodeValue(latE5 - lat))
            sb.append(encodeValue(lngE5 - lng))
            lat = latE5
            lng = lngE5
        }
        return sb.toString()
    }

    private fun encodeValue(v: Int): String {
        var value = v shl 1
        if (v < 0) value = value.inv()
        val out = StringBuilder()
        while (value >= 0x20) {
            out.append(((0x20 or (value and 0x1f)) + 63).toChar())
            value = value shr 5
        }
        out.append((value + 63).toChar())
        return out.toString()
    }
}
```

- [ ] **Step 5: Write `PoiFilterEngine.kt`**

```kotlin
package com.karoopoi.engine

import com.karoopoi.data.PoiDao
import com.karoopoi.geo.GeoUtils
import com.karoopoi.geo.LatLng
import com.karoopoi.geo.PolylineDecoder

class PoiFilterEngine(private val poiDao: PoiDao) {

    suspend fun findNextPois(
        currentLocation: LatLng,
        routePolyline: String,
        activeCategories: Set<String>,
        thresholdMeters: Int,
        limit: Int = 5
    ): List<PoiResult> {
        if (activeCategories.isEmpty() || routePolyline.isBlank()) return emptyList()
        val route = PolylineDecoder.decode(routePolyline)
        if (route.size < 2) return emptyList()

        var closestIndex = 0
        var closestDist = Double.MAX_VALUE
        for (i in route.indices) {
            val d = GeoUtils.distance(currentLocation, route[i])
            if (d < closestDist) {
                closestDist = d
                closestIndex = i
            }
        }
        val routeAhead = route.subList(closestIndex, route.size)
        if (routeAhead.size < 2) return emptyList()

        val lats = routeAhead.map { it.lat }
        val lons = routeAhead.map { it.lon }
        val padding = thresholdMeters / 111_000.0 + 0.001
        val minLat = lats.minOrNull()!! - padding
        val maxLat = lats.maxOrNull()!! + padding
        val minLon = lons.minOrNull()!! - padding
        val maxLon = lons.maxOrNull()!! + padding

        val candidates = poiDao.findInBoundingBox(
            activeCategories.toList(), minLat, maxLat, minLon, maxLon
        )

        val results = candidates.mapNotNull { entity ->
            val poiPoint = LatLng(entity.lat, entity.lon)
            var minDist = Double.MAX_VALUE
            var bestIndex = 0
            var bestT = 0.0
            for (i in 0 until routeAhead.size - 1) {
                val s1 = routeAhead[i]
                val s2 = routeAhead[i + 1]
                val d = GeoUtils.pointToSegmentDistance(poiPoint, s1, s2)
                if (d < minDist) {
                    minDist = d
                    bestIndex = i
                    val scale = cos(Math.toRadians((s1.lat + s2.lat) / 2))
                    val dx = (s2.lon - s1.lon) * scale
                    val dy = s2.lat - s1.lat
                    val px = (poiPoint.lon - s1.lon) * scale
                    val py = poiPoint.lat - s1.lat
                    val len2 = dx * dx + dy * dy
                    val t = if (len2 == 0.0) 0.0 else ((px * dx) + (py * dy)) / len2
                    bestT = t.coerceIn(0.0, 1.0)
                }
            }
            if (minDist > thresholdMeters) return@mapNotNull null

            var along = 0.0
            for (i in 0 until bestIndex) {
                along += GeoUtils.distance(routeAhead[i], routeAhead[i + 1])
            }
            val s1 = routeAhead[bestIndex]
            val s2 = routeAhead[bestIndex + 1]
            along += GeoUtils.distance(s1, s2) * bestT

            PoiResult(
                osmId = entity.osmId,
                name = entity.name,
                category = entity.category,
                lat = entity.lat,
                lon = entity.lon,
                distanceFromRoute = minDist,
                distanceAlongRoute = along
            )
        }

        return results.sortedBy { it.distanceAlongRoute }.take(limit)
    }
}
```

- [ ] **Step 6: Run tests to confirm they pass**

Run:
```bash
./gradlew app:testDebugUnitTest --tests "com.karoopoi.engine.PoiFilterEngineTest"
```

Expected: All tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/karoopoi/engine/ app/src/test/java/com/karoopoi/
git commit -m "feat: add POI filter engine with tests"
```

---

## Task 6: DataStore Preferences

**Files:**
- Create: `app/src/main/java/com/karoopoi/prefs/PoiPreferences.kt`

- [ ] **Step 1: Write `PoiPreferences.kt`**

```kotlin
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

class PoiPreferences(context: Context) {
    private val dataStore = context.dataStore

    val categorySwimming: Flow<Boolean> = dataStore.data.map { it[SWIMMING] ?: true }
    val categoryBeach: Flow<Boolean> = dataStore.data.map { it[BEACH] ?: true }
    val thresholdMeters: Flow<Int> = dataStore.data.map { it[THRESHOLD] ?: 500 }

    suspend fun setSwimming(value: Boolean) { dataStore.edit { it[SWIMMING] = value } }
    suspend fun setBeach(value: Boolean) { dataStore.edit { it[BEACH] = value } }
    suspend fun setThreshold(value: Int) { dataStore.edit { it[THRESHOLD] = value } }

    companion object {
        private val SWIMMING = booleanPreferencesKey("category_swimming")
        private val BEACH = booleanPreferencesKey("category_beach")
        private val THRESHOLD = intPreferencesKey("threshold_meters")
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/karoopoi/prefs/PoiPreferences.kt
git commit -m "feat: add DataStore preferences"
```

---

## Task 7: Extension Info & RemoteViews Layout

**Files:**
- Create: `app/src/main/res/xml/extension_info.xml`
- Create: `app/src/main/res/layout/remote_views_poi_list.xml`

- [ ] **Step 1: Write `extension_info.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<ExtensionInfo
    id="poi"
    displayName="@string/extension_name"
    icon="@drawable/ic_launcher_foreground"
    scansDevices="false">
    <DataType
        typeId="next_pois"
        description="@string/next_pois_desc"
        displayName="@string/next_pois_name"
        graphical="true"
        icon="@drawable/ic_launcher_foreground" />
</ExtensionInfo>
```

- [ ] **Step 2: Write `remote_views_poi_list.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="4dp">
    <TextView
        android:id="@+id/poi_list_text"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textColor="@android:color/white"
        android:textSize="12sp"
        android:fontFamily="monospace"
        android:lineSpacingMultiplier="1.1" />
</LinearLayout>
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/xml/extension_info.xml app/src/main/res/layout/remote_views_poi_list.xml
git commit -m "feat: add extension info and RemoteViews layout"
```

---

## Task 8: PoiStateManager

**Files:**
- Create: `app/src/main/java/com/karoopoi/extension/PoiStateManager.kt`

- [ ] **Step 1: Write `PoiStateManager.kt`**

```kotlin
package com.karoopoi.extension

import com.karoopoi.engine.PoiFilterEngine
import com.karoopoi.geo.LatLng
import com.karoopoi.prefs.PoiPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

object PoiStateManager {
    private val _displayText = MutableStateFlow("--")
    val displayText: StateFlow<String> = _displayText.asStateFlow()

    suspend fun update(
        location: LatLng,
        routePolyline: String?,
        preferences: PoiPreferences,
        engine: PoiFilterEngine
    ) {
        if (routePolyline.isNullOrEmpty()) {
            _displayText.value = "--"
            return
        }
        val categories = buildSet {
            if (preferences.categorySwimming.first()) add("swimming")
            if (preferences.categoryBeach.first()) add("beach")
        }
        if (categories.isEmpty()) {
            _displayText.value = "--"
            return
        }
        val threshold = preferences.thresholdMeters.first()
        val pois = engine.findNextPois(location, routePolyline, categories, threshold)
        val text = if (pois.isEmpty()) {
            "None nearby"
        } else {
            pois.take(5).joinToString("\n") {
                val icon = if (it.category == "beach") "\uD83C\uDFD6" else "\uD83C\uDFCA"
                val dist = if (it.distanceAlongRoute >= 1000) {
                    "${(it.distanceAlongRoute / 1000).toInt()}km"
                } else {
                    "${it.distanceAlongRoute.toInt()}m"
                }
                "$icon ${it.name ?: it.category} $dist"
            }
        }
        _displayText.value = text
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/karoopoi/extension/PoiStateManager.kt
git commit -m "feat: add POI state manager"
```

---

## Task 9: PoiDataType

**Files:**
- Create: `app/src/main/java/com/karoopoi/extension/PoiDataType.kt`

- [ ] **Step 1: Write `PoiDataType.kt`**

```kotlin
package com.karoopoi.extension

import android.content.Context
import android.widget.RemoteViews
import com.karoopoi.R
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataTypeImpl
import io.hammerhead.karooext.models.ViewConfig
import io.hammerhead.karooext.models.ViewEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PoiDataType(extension: KarooExtension) : DataTypeImpl(extension, "next_pois") {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            PoiStateManager.displayText.collect { text ->
                val views = RemoteViews(context.packageName, R.layout.remote_views_poi_list)
                views.setTextViewText(R.id.poi_list_text, text)
                emitter.updateView(views)
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/karoopoi/extension/PoiDataType.kt
git commit -m "feat: add POI data type"
```

---

## Task 10: PoiExtension Service

**Files:**
- Create: `app/src/main/java/com/karoopoi/extension/PoiExtension.kt`

- [ ] **Step 1: Write `PoiExtension.kt`**

```kotlin
package com.karoopoi.extension

import androidx.room.Room
import com.karoopoi.data.PoiDatabase
import com.karoopoi.engine.PoiFilterEngine
import com.karoopoi.geo.LatLng
import com.karoopoi.prefs.PoiPreferences
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataTypeImpl
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class PoiExtension : KarooExtension("poi", "1") {
    override val types: List<DataTypeImpl> = listOf(PoiDataType(this))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val routePolyline = MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()
        val karooSystem = KarooSystemService(this)
        val db = Room.databaseBuilder(applicationContext, PoiDatabase::class.java, "pois.db")
            .createFromAsset("pois.db")
            .build()
        val engine = PoiFilterEngine(db.poiDao())
        val preferences = PoiPreferences(applicationContext)

        karooSystem.connect {
            scope.launch {
                karooSystem.consumerFlow<OnNavigationState>().collect { event ->
                    val polyline = when (val s = event.state) {
                        is OnNavigationState.NavigationState.NavigatingRoute -> s.routePolyline
                        else -> null
                    }
                    routePolyline.value = polyline
                }
            }
            scope.launch {
                karooSystem.consumerFlow<OnLocationChanged>().collect { loc ->
                    PoiStateManager.update(
                        location = LatLng(loc.lat, loc.lng),
                        routePolyline = routePolyline.value,
                        preferences = preferences,
                        engine = engine
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/karoopoi/extension/PoiExtension.kt
git commit -m "feat: add POI extension service"
```

---

## Task 11: SettingsActivity & Layout

**Files:**
- Create: `app/src/main/java/com/karoopoi/ui/SettingsActivity.kt`
- Create: `app/src/main/res/layout/activity_settings.xml`

- [ ] **Step 1: Write `activity_settings.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Categories"
        android:textStyle="bold"
        android:textSize="18sp" />

    <com.google.android.material.switchmaterial.SwitchMaterial
        android:id="@+id/swim_switch"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Swimming" />

    <com.google.android.material.switchmaterial.SwitchMaterial
        android:id="@+id/beach_switch"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Beach" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:text="Max distance from route"
        android:textStyle="bold"
        android:textSize="18sp" />

    <com.google.android.material.slider.Slider
        android:id="@+id/threshold_slider"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:valueFrom="0"
        android:valueTo="5000"
        android:stepSize="100" />

    <TextView
        android:id="@+id/threshold_value"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="500m" />
</LinearLayout>
```

- [ ] **Step 2: Write `SettingsActivity.kt`**

```kotlin
package com.karoopoi.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.karoopoi.R
import com.karoopoi.prefs.PoiPreferences
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: PoiPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = PoiPreferences(this)

        val swimSwitch = findViewById<SwitchMaterial>(R.id.swim_switch)
        val beachSwitch = findViewById<SwitchMaterial>(R.id.beach_switch)
        val thresholdSlider = findViewById<Slider>(R.id.threshold_slider)
        val thresholdValue = findViewById<android.widget.TextView>(R.id.threshold_value)

        lifecycleScope.launch {
            prefs.categorySwimming.collect { swimSwitch.isChecked = it }
        }
        lifecycleScope.launch {
            prefs.categoryBeach.collect { beachSwitch.isChecked = it }
        }
        lifecycleScope.launch {
            prefs.thresholdMeters.collect {
                thresholdSlider.value = it.toFloat()
                thresholdValue.text = "${it}m"
            }
        }

        swimSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { prefs.setSwimming(checked) }
        }
        beachSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { prefs.setBeach(checked) }
        }
        thresholdSlider.addOnChangeListener { _, value, _ ->
            val intVal = value.toInt()
            thresholdValue.text = "${intVal}m"
            lifecycleScope.launch { prefs.setThreshold(intVal) }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/karoopoi/ui/SettingsActivity.kt app/src/main/res/layout/activity_settings.xml
git commit -m "feat: add settings activity and layout"
```

---

## Task 12: UI Test for Settings

**Files:**
- Create: `app/src/androidTest/java/com/karoopoi/ui/SettingsActivityTest.kt`

- [ ] **Step 1: Write `SettingsActivityTest.kt`**

```kotlin
package com.karoopoi.ui

import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.slider.Slider
import com.karoopoi.R
import org.hamcrest.Matcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsActivityTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(SettingsActivity::class.java)

    @Test
    fun toggleSwimmingPersists() {
        onView(withId(R.id.swim_switch)).perform(click())
        activityRule.scenario.recreate()
        onView(withId(R.id.swim_switch)).check(matches(isNotChecked()))
    }

    @Test
    fun sliderChangesThresholdText() {
        onView(withId(R.id.threshold_slider)).perform(setSliderValue(1500f))
        onView(withId(R.id.threshold_value)).check(matches(withText("1500m")))
    }

    private fun setSliderValue(value: Float): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(Slider::class.java)
        override fun getDescription(): String = "Set Slider value to $value"
        override fun perform(uiController: UiController, view: View) {
            (view as Slider).value = value
        }
    }
}
```

- [ ] **Step 2: Run tests**

Run:
```bash
./gradlew app:connectedDebugAndroidTest --tests "com.karoopoi.ui.SettingsActivityTest"
```

Expected: Tests pass (requires emulator or connected device).

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/karoopoi/ui/SettingsActivityTest.kt
git commit -m "test: add settings UI tests"
```

---

## Plan Self-Review

- [ ] **Spec coverage**: Every requirement in the design spec has at least one task:
  - Custom field → Tasks 7, 8, 9, 10
  - POI list logic → Tasks 4, 5
  - Local storage for categories & distance → Tasks 6, 11
  - OSM/Geofabrik data → Tasks 2, 3, 13 (build pipeline)
  - Settings view → Task 11
- [ ] **Placeholder scan**: No TBDs, TODOs, or vague instructions. Each step has exact file paths, code blocks, and commands.
- [ ] **Type consistency**: `PoiFilterEngine` signature used in Task 5 matches usage in Task 8/10. `PoiPreferences` keys consistent across Tasks 6 and 11.
- [ ] **Missing task**: No dedicated Task 13 for build pipeline integration in Gradle — it is included in Task 1 Step 3 (`generatePoiDb` task) and the pipeline script in Task 2.

No gaps found. Plan ready for execution.

---

## Execution Options

**Plan complete and saved to `docs/superpowers/plans/2026-05-15-karoo-poi-app.md`.**

Two execution options:

1. **Subagent-Driven (recommended)** — Dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — Execute tasks in this session using `executing-plans`, batch execution with checkpoints.

Which approach?
