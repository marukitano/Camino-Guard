#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# CAMINO_DEBUG_ROUTE_PALETTE_V1
# Same palette/order as build_android_camino_tracks.py.
PALETTE = [
    "#d1495b", "#00798c", "#edae49", "#30638e",
    "#6a994e", "#8f5d5d", "#7b2cbf", "#2a9d8f",
    "#e76f51", "#457b9d", "#bc6c25", "#5a189a",
    "#588157", "#c1121f", "#3a86ff", "#9c6644",
]
GROUP_DIR = ROOT / "data/processed/groups"
OUTPUT = ROOT / "android/app/src/main/assets/camino/debug-all-primary-caminos.json"


def fail(message: str) -> "NoReturn":
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def section_order(section_id: str) -> tuple[int, str]:
    match = re.match(r"^(\d+)(.*)$", section_id or "")
    if not match:
        return (10**9, section_id or "")
    return (int(match.group(1)), match.group(2))


def main() -> int:
    if Path.cwd().resolve() != ROOT:
        fail("Run this tool from the Camino-Guard repository root.")
    if not GROUP_DIR.is_dir():
        fail("data/processed/groups is missing. Run build_processed_cnig.py first.")

    routes = []
    track_count = 0
    point_count = 0

    group_paths = sorted(
        p for p in GROUP_DIR.glob("*.json")
        if p.stem.startswith(("ES", "PT"))
    )

    for group_index, path in enumerate(group_paths):
        group = json.loads(path.read_text(encoding="utf-8"))
        group_id = str(group.get("route_group_id") or path.stem)
        color = PALETTE[group_index % len(PALETTE)]

        tracks = []
        for track in group.get("tracks", []):
            section_id = str(track.get("section_id") or "")
            suffix = str(track.get("variant_suffix") or (section_id[-1:] if section_id else ""))
            if suffix != "a":
                continue

            coordinates = []
            for coordinate in track.get("coordinates") or []:
                if not isinstance(coordinate, list) or len(coordinate) < 2:
                    continue
                lat = float(coordinate[0])
                lon = float(coordinate[1])
                if not (-90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0):
                    fail(f"Invalid coordinate in {group_id}/{section_id}: {coordinate}")

                elevation = None
                if len(coordinate) >= 3 and coordinate[2] is not None:
                    try:
                        elevation = float(coordinate[2])
                    except (TypeError, ValueError):
                        elevation = None

                # Keep the processed CNIG elevation as the third coordinate.
                # Android still interprets the first two values as [lat, lon].
                coordinates.append([lat, lon, elevation])

            if len(coordinates) < 2:
                continue

            tracks.append({
                "section_id": section_id,
                "from_key": str(track.get("from_key") or ""),
                "to_key": str(track.get("to_key") or ""),
                "pseudo_from": bool(track.get("pseudo_from", False)),
                "pseudo_to": bool(track.get("pseudo_to", False)),
                "coordinates": coordinates,
            })
            track_count += 1
            point_count += len(coordinates)

        tracks.sort(key=lambda item: section_order(item["section_id"]))
        if not tracks:
            continue

        routes.append({
            "route_group_id": group_id,
            "name": group.get("name") or group.get("official_name") or group_id,
            "color": color,
            "tracks": tracks,
        })

    if not routes:
        fail("No primary ES/PT Camino tracks were generated.")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(
        json.dumps(
            {
                "schema": 4,
                "purpose": "temporary Android Camino measurement harness",
                "routes": routes,
            },
            ensure_ascii=False,
            separators=(",", ":"),
        ) + "\n",
        encoding="utf-8",
    )

    print("Android Camino debug measurement asset ready.")
    print(f"  route groups: {len(routes)}")
    print(f"  primary tracks: {track_count}")
    print(f"  points: {point_count}")
    print(f"  output: {OUTPUT.relative_to(ROOT)}")
    print(f"  size: {OUTPUT.stat().st_size / 1024**2:.1f} MiB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
