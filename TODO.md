# Performance & Code Quality Tasks

## ✅ Completed

- [x] **P1: Cache decoded route in PoiFilterEngine** — Cached decoded polyline and precomputed segment distances.
- [x] **P2: Fix coroutine scope leak in BeachDataType/StoreDataType** — Store scope as class field, cancel on restart, use `emitter.setCancellable`.
- [x] **P3: Fix thread safety of `previousSymbolIds` in PoiExtension** — Changed to `MutableStateFlow<List<String>>` and `AtomicInteger`.
- [x] **P4: Batch DataStore reads in PoiStateManager** — Single `dataStore.data.first()` snapshot instead of 5 sequential reads.
- [x] **P5: Eliminate double emission in PoiExtension.startMap** — Added `distinctUntilChanged()` on combined flow.
- [x] **P6: Deduplicate BeachDataType/StoreDataType** — Extracted shared `PoiListDataType` base class.
- [x] **P7: Deduplicate SettingsActivity switch binding** — Extracted `bindSwitch()` helper.
- [x] **P8: Make PoiPreferences a singleton** — `PoiPreferences.getInstance(context)` with `PoiPreferencesImpl`.
- [x] **P9: Add route windowing to PoiFilterEngine** — 50km look-ahead cap.
- [x] **P10: Push limit into SQL query** — Added `LIMIT` parameter (default 200) to `findInBoundingBox`.
- [x] **P11: Fix dead branch in beach icon lambda** — Simplified to single emoji.
- [x] **P12: Reset symbolCounter when clearing symbols** — Added `symbolCounter.set(0)` in `startMap` and `setCancellable`.
- [x] **P13: Fix PoiEntity id in test** — Added missing `id` parameter to test constructors.

## 🟡 Pending

- [ ] **P14: Consider paginated DAO query** — `findInBoundingBox` returns full `List<PoiEntity>`. For 12K+ results, consider Room's `DataSource.Factory` or pagination support.

---

# Feature Tasks

## 🟡 Medium Priority

- [x] **Show "no route loaded" on custom fields** — Added `DisplayState` enum to `PoiStateManager`. `PoiListDataType` shows contextual message via `combine(displayState, displayItems)`.

- [x] **Show "waiting GPS signal" on custom fields** — `DisplayState.WAITING_GPS` shown when route present but no GPS fix yet. Tracked via `hasGpsFix` in `PoiExtension`.

- [x] **Add app icon** — Replaced plain green rectangle with teal location pin + green wave vector drawable. Adaptive icon foreground `ic_launcher_foreground.xml`.