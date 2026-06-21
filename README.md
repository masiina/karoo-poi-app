# Karoo POI

POI finder for the Hammerhead Karoo bike computer. Shows upcoming supermarkets, convenience stores, swimming areas, beaches, and scenic viewpoints along your route in custom data fields, with map markers for tap-to-navigate. Includes a Nearby finder to search for POIs around your current location.

## Features

- **Three custom data fields** — "Next Beaches" shows swimming/beach POIs, "Next Stores" shows supermarket/convenience store POIs, "Next Viewpoints" shows scenic viewpoint POIs; each lists the 5 nearest ahead on your route with GPS distance and category icons
- **Nearby POI finder** — search for POIs near your current GPS location by category and distance (500m–5km); results show name, distance, compass direction, and a Navigate button that launches the Karoo's native pin-drop navigation
- **Map markers** — all route POIs appear as icons on the Karoo map; tap any marker to navigate to it
- **Contextual status messages** — custom fields show "No route loaded" when idle, "GPS locating..." when route loaded without GPS lock, then POI items once both are available
- **App icon** — teal location pin with green wave adaptive icon
- **Configurable threshold** — set max distance from route (0–5000 m) in the Settings tab
- **Category toggles** — enable/disable beaches & swimming, stores, and viewpoint POIs independently
- **Finland POI database** — generated from OSM data at build time: 9,097 POIs (3,390 beaches, 1,653 convenience stores, 1,596 viewpoints, 1,238 swimming, 1,220 supermarkets, ~3.2 MB SQLite). Build pipeline automatically deduplicates OSM features mapped as both nodes and ways, filters out unnamed stores, and assigns default names to unnamed beaches/swimming/viewpoints

## How it works

1. Extension connects to Karoo System Service and listens for `OnNavigationState` (route polyline) and `OnLocationChanged` (GPS position)
2. When navigation state changes, `PoiStateManager` transitions `DisplayState` (`NO_ROUTE` → `LOADED` → `ACTIVE`) and clears stale data on route removal
3. When location changes, `PoiFilterEngine` finds your position on the route, queries the pre-built SQLite database for POIs in a bounding box along the remaining route (50 km look-ahead window), filters by distance threshold, and sorts by distance along the route
4. `BeachDataType` renders beach/swimming results, `StoreDataType` renders store results, and `ViewpointDataType` renders viewpoint results in separate RemoteViews custom fields via shared `PoiListDataType` base class
5. `startMap` emitter sends `Symbol.POI` markers to the Karoo map for visual overlay and tap-to-navigate
6. The Nearby tab in `SettingsActivity` connects to `KarooSystemService` for GPS location, queries the POI database by bounding box, and dispatches `LaunchPinDrop` to navigate to a selected POI

## Architecture

```
┌─────────────────────────────────────────────┐
│  KarooPoi App                               │
├─────────────────────────────────────────────┤
│  ui/           SettingsActivity (tabbed: Settings + Nearby), │
│                NearbyPoiAdapter, layouts      │
│  extension/    PoiExtension, PoiListDataType,│
│                BeachDataType, StoreDataType, │
│                ViewpointDataType,            │
│                PoiStateManager, DisplayState  │
│  engine/       PoiFilterEngine, PoiResult    │
│  geo/          GeoUtils (distance, bearing),  │
│                LatLng, PolylineDecoder       │
│  data/         Room DB (PoiEntity, PoiDao,   │
│                PoiCandidate)                 │
│  prefs/        DataStore preferences         │
├─────────────────────────────────────────────┤
│  assets/         Empty — pois.db generated at build time │
│  build_scripts/  Pipeline (PBF → SQLite)     │
└─────────────────────────────────────────────┘
```

## Tech stack

- Kotlin, Android SDK 34 (min 26), Java 17
- [karoo-ext 1.1.8](https://github.com/hammerheadnav/karoo-ext) — Hammerhead extension SDK
- Room 2.6.1 + KSP — local SQLite database
- DataStore Preferences — user settings persistence
- RecyclerView — Nearby POI results list
- Coroutines + StateFlow — reactive data pipeline
- JUnit + Robolectric + Espresso — testing

## Prerequisites

- Android SDK 34 (Platform + Build Tools)
- Python 3 with sqlite3
- [osmium-tool](https://osmcode.org/osmium-tool/) 1.14+ (`apt install osmium-tool`)
- OSM PBF extract from [Geofabrik](https://download.geofabrik.de/) (Finland included)

## Building

The POI database is generated at build time from an OSM PBF extract — it is
not checked into git. You must download a PBF before the first build.

```bash
# Generate Gradle wrapper
gradle wrapper

# Download a region extract (required for first build)
mkdir -p data
wget https://download.geofabrik.de/europe/finland-latest.osm.pbf -O data/region.osm.pbf

# Build debug APK (generatePoiDb runs automatically)
./gradlew app:assembleDebug

# Install on connected Karoo
adb install app/build/outputs/apk/debug/karoo-poi-1.3-debug.apk
```

If no PBF is found, the build fails with instructions on how to download one.

## Generating POI database for a different region

```bash
# Download region extract (stored in data/ to avoid re-downloading)
mkdir -p data
wget https://download.geofabrik.de/europe/finland-latest.osm.pbf -O data/finland-latest.osm.pbf

# Build with custom PBF path (pipeline runs automatically during build)
./gradlew app:assembleDebug -Ppoi.pbf=data/finland-latest.osm.pbf
```

The `data/` directory is gitignored — PBF files are large and should be
downloaded separately. Indoor swimming halls and private facilities are
automatically filtered out by the pipeline. Unnamed stores are skipped
(without a name the user can't tell what shop it is). Unnamed
beaches/swimming/viewpoints are assigned default names ("Beach",
"Swimming", "Viewpoint"). The pipeline also deduplicates POIs mapped as
both nodes and ways (same name and category within ~50 m), keeping the
entry with the richest OSM tags. To change POI categories or filtering
rules, edit `build_scripts/poi_pipeline.py`.

## Project structure

```
karoo-poi/
├── app/
│   ├── build.gradle.kts          # App module config + generatePoiDb task
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── assets/           # pois.db generated at build time
│   │   │   ├── java/com/karoopoi/
│   │   │   │   ├── data/         # PoiEntity, PoiCandidate, PoiDao, PoiDatabase
│   │   │   │   ├── engine/       # PoiFilterEngine, PoiResult
│   │   │   │   ├── extension/    # PoiExtension, PoiListDataType,
│   │   │   │   │                   BeachDataType, StoreDataType,
│   │   │   │   │                   ViewpointDataType, PoiStateManager,
│   │   │   │   │                   DisplayState
│   │   │   │   ├── geo/          # LatLng, GeoUtils (distance, bearing),
│   │   │   │   │                   PolylineDecoder
│   │   │   │   ├── prefs/        # PoiPreferences
│   │   │   │   └── ui/           # SettingsActivity (tabbed),
│   │   │   │                       NearbyPoiAdapter
│   │   │   └── res/
│   │   │       ├── drawable/     # ic_launcher_foreground.xml (vector)
│   │   │       ├── layout/       # activity_settings, nearby_content,
│   │   │       │                   nearby_poi_row, remote_views_poi_list,
│   │   │       │                   remote_views_poi_row
│   │   │       ├── mipmap-anydpi-v26/  # ic_launcher.xml, ic_launcher_round.xml
│   │   │       ├── values/       # strings.xml
│   │   │       └── xml/          # extension_info.xml
│   │   ├── test/                 # JUnit tests (GeoUtils, PolylineDecoder,
│   │   │                           PoiFilterEngine)
│   │   └── androidTest/          # Espresso tests (SettingsActivity)
│   └── ...
├── build_scripts/
│   └── poi_pipeline.py           # OSM PBF → SQLite pipeline
├── build.gradle.kts              # Root build config
└── settings.gradle.kts           # Gradle settings + karoo-ext repo
```

## Tests

```bash
# Unit tests (JUnit + Robolectric)
./gradlew app:testDebugUnitTest

# Instrumentation tests (Espresso)
./gradlew app:connectedDebugAndroidTest
```

Current tests cover: Haversine distance and point-to-segment projection (`GeoUtilsTest`), Google encoded polyline decoding (`PolylineDecoderTest`), POI filtering with in-memory Room database (`PoiFilterEngineTest`), and SettingsActivity toggle/slider persistence (`SettingsActivityTest`).

## Settings

The SettingsActivity provides two tabs: **Settings** and **Nearby**.

### Settings tab

| Setting | Default | Description |
|---------|---------|-------------|
| Beaches & Swimming | On | Show swimming areas and beaches |
| Stores | On | Show supermarkets and convenience stores |
| Viewpoint | On | Show scenic viewpoints |
| Max distance | 500 m | POIs further from route are filtered out |

Settings persist across restarts via DataStore.

### Nearby tab

Search for POIs around your current GPS location:

| Control | Description |
|---------|-------------|
| Category toggles | Select which categories to search (Beaches & Swimming, Stores, Viewpoint) |
| Distance slider | Search radius (500–5000 m, default 1000 m) |
| Search button | Queries the POI database and shows results sorted by distance |
| Result rows | POI name, distance, compass direction, and Navigate button |
| Navigate button | Launches the Karoo's native pin-drop navigation to the POI |

The Nearby tab uses `KarooSystemService` for GPS location and `LaunchPinDrop` for navigation.
