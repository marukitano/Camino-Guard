#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GROUP_DIR = ROOT / "data/processed/groups"
OUTPUT_DIR = ROOT / "android/app/src/main/assets/camino"
TRACKS_OUTPUT = OUTPUT_DIR / "tracks.geojson"
CATALOG_OUTPUT = OUTPUT_DIR / "catalog.json"

PALETTE = [
    "#d1495b", "#00798c", "#edae49", "#30638e",
    "#6a994e", "#8f5d5d", "#7b2cbf", "#2a9d8f",
    "#e76f51", "#457b9d", "#bc6c25", "#5a189a",
    "#588157", "#c1121f", "#3a86ff", "#9c6644",
]

def fail(message: str) -> "NoReturn":
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)

def main() -> int:
    if Path.cwd().resolve() != ROOT:
        fail("Run this tool from the Camino-Guard repository root.")
    if not GROUP_DIR.is_dir():
        fail("data/processed/groups is missing.")

    group_paths = sorted(
        p for p in GROUP_DIR.glob("*.json")
        if p.stem.startswith(("ES", "PT"))
    )
    if not group_paths:
        fail("No ES/PT processed route groups found.")

    features = []
    catalog = []
    total_tracks = 0
    total_points = 0

    for group_index, path in enumerate(group_paths):
        group = json.loads(path.read_text(encoding="utf-8"))
        group_id = str(group.get("route_group_id") or path.stem)
        if not group_id.startswith(("ES", "PT")) or group_id.startswith("FR"):
            fail(f"Out-of-scope route group selected: {group_id}")

        color = PALETTE[group_index % len(PALETTE)]
        name = group.get("official_name") or group.get("name") or group_id
        group_tracks = 0
        group_points = 0

        for track in group.get("tracks", []):
            coords = []
            for c in track.get("coordinates") or []:
                if not isinstance(c, list) or len(c) < 2:
                    continue
                lat, lon = float(c[0]), float(c[1])
                if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                    fail(f"Invalid coordinate in {group_id}: {c}")
                coords.append([lon, lat])

            if len(coords) < 2:
                continue

            features.append({
                "type": "Feature",
                "properties": {
                    "route_group_id": group_id,
                    "track_id": track.get("track_id"),
                    "section_id": track.get("section_id"),
                    "name": name,
                    "length_m": track.get("length_m"),
                    "color": color,
                    "country": "ES" if group_id.startswith("ES") else "PT",
                },
                "geometry": {"type": "LineString", "coordinates": coords},
            })
            group_tracks += 1
            group_points += len(coords)
            total_tracks += 1
            total_points += len(coords)

        catalog.append({
            "route_group_id": group_id,
            "official_name": name,
            "display_name": group.get("display_name") or name,
            "color": color,
            "track_count": group_tracks,
            "point_count": group_points,
        })

    if any(f["properties"]["route_group_id"].startswith("FR") for f in features):
        fail("Safety check failed: generated output contains an FR route.")

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    TRACKS_OUTPUT.write_text(
        json.dumps({"type": "FeatureCollection", "features": features},
                   ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    CATALOG_OUTPUT.write_text(
        json.dumps({
            "schema": 1,
            "scope": ["ES", "PT"],
            "route_groups": len(catalog),
            "tracks": total_tracks,
            "points": total_points,
            "items": catalog,
        }, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    print("Android Camino track overlay ready.")
    print(f"  route groups: {len(catalog)}")
    print(f"  tracks:       {total_tracks}")
    print(f"  points:       {total_points}")
    print(f"  GeoJSON:      {TRACKS_OUTPUT.stat().st_size / 1024**2:.1f} MiB")
    print("  scope:        ES + PT only")
    print("  FR groups:    0")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
