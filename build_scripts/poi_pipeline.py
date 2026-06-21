#!/usr/bin/env python3
"""Build-time POI pipeline: filter OSM PBF extracts and load into SQLite for the Karoo POI app."""
import argparse
import json
import sqlite3
import subprocess
import os
import sys
import tempfile

# Tags used to extract features from the OSM PBF via osmium tags-filter.
# Includes both categorization tags and auxiliary tags needed for post-filtering.
OSM_EXTRACT_TAGS = [
    "leisure=swimming_area",
    "leisure=bathing_place",
    "sport=swimming",
    "natural=beach",
    "shop=supermarket",
    "shop=convenience",
    "tourism=viewpoint",
]

# Maps OSM tag patterns to POI categories. Only these tag patterns produce POIs.
CATEGORY_MAP = {
    "leisure=swimming_area": "swimming",
    "leisure=bathing_place": "swimming",
    "sport=swimming": "swimming",
    "natural=beach": "beach",
    "shop=supermarket": "supermarket",
    "shop=convenience": "convenience",
    "tourism=viewpoint": "viewpoint",
}

# Tags that indicate an indoor/private swimming facility — excluded from POIs.
EXCLUDED_SWIMMING_LEISURE = {"sports_centre", "sports_hall", "swimming_pool", "water_park"}
EXCLUDED_ACCESS = {"private", "customers"}
# Name substrings (lowercased) strongly indicating an indoor swimming hall.
HALL_NAME_INDICATORS = ["uimahalli", "simhall", "uintikeskus", "simbassäng", "swimming hall", "swimming centre"]


def _is_excluded_swimming(props: dict) -> bool:
    """Return True if a swimming POI should be excluded (indoor or private)."""
    leisure = props.get("leisure")

    # Exclude known indoor/facility leisure types unless explicitly outdoor
    if leisure in EXCLUDED_SWIMMING_LEISURE:
        # Allow through if explicitly marked as outdoor — but still
        # apply access/name checks below
        location = props.get("location")
        if location != "outdoor":
            return True

    # Exclude based on indoor indicators in any swimming POI
    if props.get("location") == "indoor":
        return True
    if props.get("covered") == "yes":
        return True
    if props.get("swimming_pool") == "indoor":
        return True
    if props.get("indoor") == "yes":
        return True
    # A building tag on a swimming POI strongly suggests an indoor facility
    if props.get("building") and props.get("building") not in ("no",):
        return True

    # Exclude non-public access (applies even to outdoor facilities)
    access = props.get("access")
    if access in EXCLUDED_ACCESS:
        return True

    # Exclude by name — names like "uimahalli" / "simhall" indicate indoor halls,
    # but only when there's no explicit outdoor indicator (e.g. leisure=swimming_area).
    name = (props.get("name") or "").lower()
    if leisure not in ("swimming_area", "bathing_place") and name:
        if any(indicator in name for indicator in HALL_NAME_INDICATORS):
            return True

    return False


# Distance threshold for deduplication: POIs with the same name and category
# within this distance are considered duplicates. ~50m accounts for the
# difference between a node marker and a polygon centroid.
DEDUP_LAT_THRESHOLD = 0.0005  # ~55m
DEDUP_LON_THRESHOLD = 0.0015  # ~50m at 70°N, ~83m at 60°N


def deduplicate_pois(conn: sqlite3.Connection) -> int:
    """Remove duplicate POIs with the same name and category within ~50m.

    Handles the common OSM pattern where a feature is mapped as both a node
    (point) and a way (polygon), producing two POIs at nearly the same
    location after centroid computation. Also catches duplicate nodes and
    multi-tagged ways (e.g. a beach tagged as both leisure=swimming_area
    and natural=beach).

    Keeps the entry with the richest OSM tags (longest tags JSON), using
    rowid as a tiebreaker. Returns the number of duplicates removed.
    """
    before = conn.execute("SELECT COUNT(*) FROM pois").fetchone()[0]

    conn.execute(
        "DELETE FROM pois WHERE rowid IN ("
        "  SELECT b.rowid"
        "  FROM pois a"
        "  JOIN pois b ON a.name = b.name"
        "             AND a.category = b.category"
        "             AND a.rowid != b.rowid"
        "             AND ABS(a.lat - b.lat) < ?"
        "             AND ABS(a.lon - b.lon) < ?"
        "  WHERE a.name IS NOT NULL"
        "    AND (COALESCE(length(a.tags), 0) > COALESCE(length(b.tags), 0)"
        "         OR (COALESCE(length(a.tags), 0) = COALESCE(length(b.tags), 0)"
        "             AND a.rowid < b.rowid))"
        ")",
        (DEDUP_LAT_THRESHOLD, DEDUP_LON_THRESHOLD),
    )

    after = conn.execute("SELECT COUNT(*) FROM pois").fetchone()[0]
    removed = before - after
    if removed:
        print(f"Deduplication: removed {removed} duplicate POIs "
              f"({before} -> {after})")
        # VACUUM cannot run inside a transaction; commit the DELETE first.
        conn.commit()
        conn.execute("VACUUM")
    return removed


def run_export(pbf_path: str, geojson_path: str) -> None:
    """Filter PBF by swimming tags and export to GeoJSONSeq."""
    with tempfile.NamedTemporaryFile(suffix=".pbf", delete=False) as filtered_pbf:
        filtered_path = filtered_pbf.name

    tag_filters = list(OSM_EXTRACT_TAGS)

    try:
        subprocess.run(
            [
                "osmium", "tags-filter", pbf_path,
                *tag_filters,
                "-o", filtered_path, "--overwrite",
            ],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        subprocess.run(
            ["osmium", "export", filtered_path, "-f", "geojsonseq", "-u", "type_id", "-o", geojson_path, "--overwrite"],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    finally:
        os.unlink(filtered_path)


def parse_and_write(geojson_path: str, db_path: str) -> None:
    """Parse GeoJSONSeq and insert matching POIs into SQLite."""
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            "CREATE TABLE IF NOT EXISTS pois ("
            "id INTEGER NOT NULL, osm_id TEXT NOT NULL, name TEXT, "
            "lat REAL NOT NULL, lon REAL NOT NULL, category TEXT NOT NULL, tags TEXT, "
            "PRIMARY KEY (id))"
        )
        conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS index_pois_osm_id ON pois(osm_id)")
        conn.execute("CREATE INDEX IF NOT EXISTS index_pois_category ON pois(category)")
        conn.execute("CREATE INDEX IF NOT EXISTS index_pois_lat_lon ON pois(lat, lon)")
        conn.execute("CREATE INDEX IF NOT EXISTS index_pois_category_lat_lon ON pois(category, lat, lon)")
        conn.execute("DELETE FROM pois")

        unknown_counter = 0
        with open(geojson_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    feat = json.loads(line)
                    geom = feat.get("geometry", {})
                    if not geom:
                        continue
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
                    elif gtype == "MultiPolygon":
                        ring = coords[0][0]
                        lons = [c[0] for c in ring]
                        lats = [c[1] for c in ring]
                        lon = sum(lons) / len(lons)
                        lat = sum(lats) / len(lats)
                    else:
                        continue
                except (json.JSONDecodeError, IndexError, KeyError, TypeError, ZeroDivisionError, AttributeError) as exc:
                    print(f"Warning: skipping malformed feature: {exc}", file=sys.stderr)
                    continue

                props = feat.get("properties", {})
                tags_json = json.dumps(props)
                raw_id = str(feat.get("id") or "")
                # osmium -u type_id produces IDs like "n12345", "w311558485", "a8147448"
                # Strip the type prefix to get the numeric OSM ID
                osm_id = raw_id[1:] if raw_id and raw_id[0] in "nwra" and raw_id[1:].isdigit() else raw_id
                if not osm_id:
                    unknown_counter += 1
                    osm_id = f"unknown_{unknown_counter}"
                name = props.get("name")

                for tag_key, category in CATEGORY_MAP.items():
                    k, v = tag_key.split("=", 1)
                    if props.get(k) == v:
                        # Filter out indoor/private swimming facilities
                        if category == "swimming" and _is_excluded_swimming(props):
                            continue
                        # Skip unnamed stores — without a name the user can't tell
                        # what shop it is (K-Market, Sale, R-Kioski, etc.)
                        if category in ("supermarket", "convenience") and not name:
                            continue
                        # Default names for unnamed POIs where the category itself is descriptive
                        if not name:
                            if category == "beach":
                                name = "Beach"
                            elif category == "swimming":
                                name = "Swimming"
                            elif category == "viewpoint":
                                name = "Viewpoint"
                        conn.execute(
                            "INSERT OR IGNORE INTO pois (id, osm_id, name, lat, lon, category, tags) VALUES (NULL, ?, ?, ?, ?, ?, ?)",
                            (osm_id, name, lat, lon, category, tags_json),
                        )
                        break

        deduplicate_pois(conn)


def main() -> None:
    """Run the POI pipeline: filter PBF, export to GeoJSONSeq, load into SQLite."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--pbf", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    try:
        subprocess.run(["osmium", "--version"], capture_output=True, check=True)
    except (subprocess.CalledProcessError, FileNotFoundError):
        print("Error: osmium is not installed or not found in PATH", file=sys.stderr)
        sys.exit(1)

    if not os.path.isfile(args.pbf):
        print(f"Error: PBF file not found or is not a file: {args.pbf}", file=sys.stderr)
        sys.exit(1)

    if not os.access(args.pbf, os.R_OK):
        print(f"Error: PBF file is not readable: {args.pbf}", file=sys.stderr)
        sys.exit(1)

    out_dir = os.path.dirname(args.output) or "."
    if not os.path.isdir(out_dir) or not os.access(out_dir, os.W_OK):
        print(f"Error: output directory does not exist or is not writable: {out_dir}", file=sys.stderr)
        sys.exit(1)

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
