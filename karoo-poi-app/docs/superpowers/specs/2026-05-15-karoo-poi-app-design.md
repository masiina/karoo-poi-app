# Karoo POI App — Design Spec

**Date:** 2026-05-15
**Status:** Approved

---

## 1. Overview

Android karoo-ext extension for Hammerhead Karoo 3 bicycle computers. Displays the next 5 POIs ahead on the current route. POI data comes from offline pre-processed OpenStreetMap (Geofabrik) extracts. Users configure which categories are active and the maximum distance from the route via a settings screen.

---

## 2. Architecture

### Components

| Component | Responsibility |
|-----------|----------------|
| `PoiListDataType` | Abstract base class for karoo-ext `DataTypeImpl`. Shared rendering logic for RemoteViews custom fields. Subclassed by `BeachDataType` and `StoreDataType`. |
| `BeachDataType` | "Next Beaches" custom field — displays swimming/beach POIs. |
| `StoreDataType` | "Next Stores" custom field — displays supermarket/convenience store POIs. |
| `PoiStateManager` | Singleton. Holds `DisplayState` (`NO_ROUTE`, `WAITING_GPS`, `ACTIVE`) and `StateFlow<List<PoiDisplayItem>>` for each category. Computes POI lists from route + location. |
| `PoiExtension` | `KarooExtension` service. Consumes `OnNavigationState` and `OnLocationChanged` events. Manages `hasGpsFix` tracking, triggers state transitions, and sends `Symbol.POI` map markers. |
| `PoiDatabase` | Pre-built SQLite database (Room), shipped in APK `assets/` and copied to internal storage on first run. Contains POIs for the bundled region. |
| `PoiFilterEngine` | Queries the database for POIs near the upcoming route polyline, filters by user-selected categories and distance threshold, sorts by distance-along-route, and returns the top 5. 50 km look-ahead window. SQL `LIMIT` for memory efficiency. |
| `SettingsActivity` | In-app screen. Toggle active POI categories and adjust the route-distance threshold. |
| `PoiPreferences` | Singleton DataStore wrapper. Persists category toggles and distance threshold. |
| Build Pipeline | External Python script + `osmium-tool` that converts a Geofabrik PBF into the SQLite POI database consumed by the app. |

### Tech Stack

- **Language:** Kotlin
- **SDK:** `karoo-ext` (Android library for Karoo extensions)
- **Build:** Gradle + custom Gradle task for POI DB generation
- **DB:** Android Room (SQLite)
- **Preferences:** Android DataStore
- **External Tools:** `osmium-tool`, Python 3

---

## 3. Data Layer

### 3.1 POI Categories (Preset)

Four categories in two groups:

| Category | OSM Tags | Group |
|----------|----------|-------|
| `swimming` | `leisure=swimming_area`, `sport=swimming` | Beach |
| `beach` | `natural=beach` | Beach |
| `supermarket` | `shop=supermarket` | Store |
| `convenience` | `shop=convenience` | Store |

### 3.2 Database Schema

Table: `pois`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | INTEGER | PRIMARY KEY | Internal row ID |
| `osm_id` | BIGINT | UNIQUE | Original OSM node/way ID |
| `name` | TEXT | | POI name (if tagged) |
| `lat` | REAL | NOT NULL | Latitude (WGS84) |
| `lon` | REAL | NOT NULL | Longitude (WGS84) |
| `category` | TEXT | NOT NULL, INDEXED | `swimming` or `beach` |
| `tags` | TEXT | | Raw OSM tags as JSON (for debugging) |

Indexes:
- `idx_pois_category` on `category`
- `idx_pois_lat_lon` on `(lat, lon)`

### 3.3 Build Pipeline

1. Download target region PBF from [Geofabrik](https://download.geofabrik.de/).
2. `osmium tags-filter` extracts nodes and ways matching the two category tag combinations.
3. Python script computes way centroids, de-duplicates, and writes a CSV.
4. `sqlite3` CLI imports the CSV into `pois.db`.
5. Gradle build task copies `pois.db` into `app/src/main/assets/` so it is bundled in the APK.

### 3.4 Runtime DB Access

On first app launch (or when the bundled DB version changes), the app copies `pois.db` from APK assets to its internal storage directory. All runtime queries use the internal copy.

---

## 4. Custom Field & POI List Logic

### 4.1 Data Type Registration

Two data types registered in `extension_info.xml`:

```xml
<DataType typeId="next_beaches" ... graphical="true" />
<DataType typeId="next_stores" ... graphical="true" />
```

### 4.2 Custom View

Because third-party extension code runs in its own process, Karoo renders the field via Android `RemoteViews`.

Layout: `remote_views_poi_list.xml` (container) + `remote_views_poi_row.xml` (per-POI row with name and distance columns).

Each row shows:
```
🏖 Beach Name    1.2km
```

`PoiListDataType` (abstract base) collects `combine(displayState, displayItems)` to render:
- **ACTIVE + items** → POI rows with icon, name, distance
- **NO_ROUTE** → centered "No route loaded" message
- **WAITING_GPS** → centered "Waiting GPS..." message
- **ACTIVE + empty** → centered "--" (all categories disabled or no POIs within threshold)

### 4.3 Extension Service Update Cycle

`PoiExtension` subscribes to `OnNavigationState` and `OnLocationChanged` via `KarooSystemService`.

**State machine (`DisplayState`):**
- `NO_ROUTE` → Initial state. Route not loaded. Shows "No route loaded".
- `WAITING_GPS` → Route loaded but no GPS fix yet. Shows "Waiting GPS...".
- `ACTIVE` → Both route and GPS available. Shows POI items.

**Transitions:**
- `OnNavigationState` idle → `clearState()` (resets to `NO_ROUTE`, clears all POI data and map symbols)
- `OnNavigationState` navigating → `onRouteLoaded()` (transitions `NO_ROUTE` → `WAITING_GPS`)
- `OnLocationChanged` (first fix) → `hasGpsFix = true`, then `PoiStateManager.update()` transitions to `ACTIVE`

On each location update:

1. **Compute route ahead:** Extract the upcoming route polyline from the current GPS location forward, limited to 50 km look-ahead window.
2. **Bounding box query:** Query the local SQLite DB for all POIs of the *active* categories whose `lat`/`lon` fall within a bounding box that contains the route ahead. SQL `LIMIT` (default 200) reduces memory pressure.
3. **Route-distance filter:** For each candidate POI, compute the shortest perpendicular distance to the route ahead polyline. Discard POIs where the distance exceeds the user-configured threshold.
4. **Sort:** Compute the distance-along-route from the rider's current position to the closest point on the route near each POI. Sort by this along-route distance (ascending).
5. **Limit & publish:** Take the first 5 POIs per category group. Update `StateFlow<List<PoiDisplayItem>>` for each data field. Set `DisplayState = ACTIVE` after items are populated to avoid intermediate empty state.
6. **Map markers:** `startMap` emitter sends `Symbol.POI` for all POIs. Beaches → `SWIMMING` icon, stores → `CONVENIENCE_STORE` icon.

### 4.4 Tap Behavior (Optional)

The `RemoteViews` text can be configured with a `PendingIntent`. Tapping the field opens `PoiListActivity`, a full-screen list showing the same 5 POIs with names and distances. This provides a larger, more readable view but is not required for core functionality.

---

## 5. Settings, Local Storage & Error Handling

### 5.1 Settings UI (`SettingsActivity`)

- **Category toggles:** Four switches (Swimming, Beach, Supermarket, Convenience Store). Enabled categories are included in the field query.
- **Distance threshold:** Horizontal slider, range 0–5000 m, step 100 m. Default: **500 m**.

### 5.2 Local Storage

User preferences are stored with Android **DataStore** (Kotlin preference library).

Keys:
- `category_swimming` → Boolean
- `category_beach` → Boolean
- `category_supermarket` → Boolean
- `category_convenience` → Boolean
- `threshold_meters` → Int (default 500)

### 5.3 Error / Status States

Custom fields show contextual messages based on `DisplayState`:

| Condition | Field Display |
|-----------|---------------|
| No route loaded | Centered "No route loaded" |
| Route loaded, no GPS fix | Centered "Waiting GPS..." |
| All categories disabled or no POIs within threshold | Centered "--" |
| Active POIs available | POI rows with icon, name, distance |

---

## 6. Data Flow, Performance & Testing

### 6.1 End-to-End Data Flow

```
Geofabrik PBF
     |
     v
 osmium-tool tags-filter
     |
     v
 Python CSV extractor
     |
     v
 sqlite3 import  -->  pois.db  -->  APK assets
                          |
                          v
                    App first launch copy
                          |
                          v
               PoiFilterEngine (runtime)
                          |
                          v
               RemoteViews text update
```

### 6.2 Performance Considerations

- **No runtime PBF parsing:** All heavy OSM processing happens at build time.
- **Bbox pre-filtering:** The DB query uses a latitude/longitude bounding box around the route ahead, keeping row retrieval minimal.
- **Route ahead clipping:** Only the upcoming portion of the route is considered, not the entire route from start to finish.
- **Query frequency:** The extension updates on location ticks but can throttle updates (e.g., every 2 seconds or every 50 meters moved) to reduce CPU usage.

### 6.3 Testing Strategy

| Test Type | Scope | Tooling |
|-----------|-------|---------|
| **Unit** | `PoiFilterEngine` logic: mock route polylines, in-memory SQLite. Assert correct top-5 results, correct sort order, threshold filtering. | JUnit, MockK |
| **Integration** | Build pipeline: run the Python/osmium script on a small test PBF, assert the generated SQLite has the expected schema and row count. | Gradle task + assert script |
| **UI** | `SettingsActivity`: toggle categories and move the slider, assert values are persisted and reflected in the field query. | Espresso |

---

## 7. Dependencies

- `io.hammerhead:karoo-ext` (latest stable)
- AndroidX Room (runtime + compiler kapt/KSP)
- AndroidX DataStore (preferences)
- Build-time: `osmium-tool`, Python 3

---

## 8. Out of Scope / Future Work

- Multi-region DB download (currently one region bundled per APK)
- Custom user-defined OSM tags
- Live POI detail map view
- Companion app or cloud sync

