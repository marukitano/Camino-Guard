#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))
import fetch_offline_basemap as base

ASSET_DIR = ROOT / "android/app/src/main/assets/maps"
OUTPUT = ASSET_DIR / "debug-schaffhausen.pmtiles"
METADATA = ASSET_DIR / "debug-schaffhausen.metadata.json"
WORK_DIR = ROOT / ".cache/offline-basemap-debug-schaffhausen"
REGION_JSON = WORK_DIR / "region.geojson"
BBOX = [8.45, 47.60, 8.82, 47.80]

def fail(message: str) -> "NoReturn":
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)

def ring(bounds):
    west, south, east, north = bounds
    return [[west,south],[east,south],[east,north],[west,north],[west,south]]

def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-date")
    parser.add_argument("--maxzoom", type=int, default=15)
    parser.add_argument("--download-threads", type=int, default=8)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    if ROOT != Path.cwd().resolve():
        fail("Run this tool from the Camino-Guard repository root")
    if OUTPUT.exists() and not args.force:
        fail(f"{OUTPUT.relative_to(ROOT)} already exists; use --force")

    WORK_DIR.mkdir(parents=True, exist_ok=True)
    ASSET_DIR.mkdir(parents=True, exist_ok=True)

    region = {
        "type": "FeatureCollection",
        "features": [{
            "type": "Feature",
            "properties": {"name": "Schaffhausen debug area"},
            "geometry": {"type": "Polygon", "coordinates": [ring(BBOX)]},
        }],
    }
    REGION_JSON.write_text(json.dumps(region, indent=2) + "\n", encoding="utf-8")

    build_date, build_url = base.find_build(args.build_date)
    pmtiles, pmtiles_version = base.pmtiles_binary()
    print(f"Using Protomaps build: {build_date}")
    print(f"pmtiles CLI: {pmtiles} ({pmtiles_version})")
    print("Debug bbox:", ",".join(str(v) for v in BBOX))

    part = OUTPUT.with_name(OUTPUT.name + ".part")
    part.unlink(missing_ok=True)

    command = [
        str(pmtiles), "extract", build_url, str(part),
        f"--region={REGION_JSON}",
        f"--maxzoom={args.maxzoom}",
        f"--download-threads={args.download_threads}",
    ]

    try:
        subprocess.run(command, check=True)
        subprocess.run([str(pmtiles), "verify", str(part)], check=True)
    except subprocess.CalledProcessError as exc:
        part.unlink(missing_ok=True)
        fail(f"pmtiles command failed with exit code {exc.returncode}")

    part.replace(OUTPUT)
    metadata = {
        "source": build_url,
        "build_date": build_date,
        "bbox": BBOX,
        "minzoom": 0,
        "maxzoom": args.maxzoom,
        "size_bytes": OUTPUT.stat().st_size,
        "sha256": sha256_file(OUTPUT),
        "purpose": "temporary Schaffhausen GPS/gyro debug basemap",
    }
    METADATA.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")

    print()
    print(f"Wrote: {OUTPUT.relative_to(ROOT)}")
    print(f"Size: {base.human_size(OUTPUT.stat().st_size)}")
    print(f"SHA-256: {metadata['sha256']}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
