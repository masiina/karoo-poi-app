# Karoo POI

POI finder for the Hammerhead Karoo bike computer. Shows upcoming supermarkets, convenience stores, swimming areas, and beaches along your route in custom data fields, with map markers for tap-to-navigate.

## Features

- **Two custom data fields** — "Next Beaches" shows swimming/beach POIs, "Next Stores" shows supermarket/convenience store POIs; each lists the 5 nearest ahead on your route with GPS distance and category icons
- **Map markers** — all route POIs appear as icons on the Karoo map; tap any marker to navigate to it
- **Contextual status messages** — custom fields show "No route loaded" when idle, "Waiting GPS..." when route loaded without GPS lock, then POI items once both are available
- **App icon** — teal location pin with green wave adaptive icon
- **Configurable threshold** — set max distance from route (0–5000 m) in the Settings activity
- **Category toggles** — enable/disable swimming, beach, supermarket, and convenience store POIs independently
- **Finland POI database** — generated from OSM data at build time: 12,749 POIs (6,541 beaches, 2,198 swimming, 1,828 supermarkets, 2,182 convenience stores, ~4 MB SQLite)

## How it works

1. Extension connects to Karoo System Service and listens for `OnNavigationState` (route polyline) and `OnLocationChanged` (GPS position)
2. When navigation state changes, `PoiStateManager` transitions `DisplayState` (`NO_ROUTE` → `WAITING_GPS` → `ACTIVE`) and clears stale data on route removal
3. When location changes, `PoiFilterEngine` finds your position on the route, queries the pre-built SQLite database for POIs in a bounding box along the remaining route (50 km look-ahead window), filters by distance threshold, and sorts by distance along the route
4. `BeachDataType` renders beach/swimming results, `StoreDataType` renders store results in separate RemoteViews custom fields via shared `PoiListDataType` base class
5. `startMap` emitter sends `Symbol.POI` markers to the Karoo map for visual overlay and tap-to-navigate

## Architecture

```
┌─────────────────────────────────────────────┐
│  KarooPoi App                               │
├─────────────────────────────────────────────┤
│  ui/           SettingsActivity, layouts     │
│  extension/    PoiExtension, PoiListDataType,│
│                BeachDataType, StoreDataType, │
│                PoiStateManager, DisplayState  │
│  engine/       PoiFilterEngine, PoiResult    │
│  geo/          GeoUtils, PolylineDecoder     │
│  data/         Room DB (PoiEntity, PoiDao)   │
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
adb install app/build/outputs/apk/debug/app-debug.apk
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
automatically filtered out by the pipeline. To change POI categories or
filtering rules, edit `build_scripts/poi_pipeline.py`.

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
│   │   │   │   ├── data/         # PoiEntity, PoiDao, PoiDatabase
│   │   │   │   ├── engine/       # PoiFilterEngine, PoiResult
│   │   │   │   ├── extension/    # PoiExtension, PoiListDataType,
│   │   │   │   │                   BeachDataType, StoreDataType,
│   │   │   │   │                   PoiStateManager, DisplayState
│   │   │   │   ├── geo/          # LatLng, GeoUtils, PolylineDecoder
│   │   │   │   ├── prefs/        # PoiPreferences
│   │   │   │   └── ui/           # SettingsActivity
│   │   │   └── res/
│   │   │       ├── drawable/     # ic_launcher_foreground.xml (vector)
│   │   │       ├── layout/       # activity_settings, remote_views_poi_list,
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

The SettingsActivity provides:

| Setting | Default | Description |
|---------|---------|-------------|
| Swimming | On | Show swimming areas and pools |
| Beach | On | Show beaches |
| Supermarket | On | Show supermarkets |
| Convenience Store | On | Show convenience stores |
| Max distance | 500 m | POIs further from route are filtered out |

Settings persist across restarts via DataStore.
