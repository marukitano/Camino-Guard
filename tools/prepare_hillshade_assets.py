\
#!/usr/bin/env python3
from __future__ import annotations

import math
import os
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PMTILES_INPUT = ROOT / "android/app/src/main/assets/maps/terrain.pmtiles"
FULL_TERRAIN = ROOT / ".cache/offline-contours/iberia-terrain-z0-12.pmtiles"
OUT_DIR = ROOT / "android/app/src/main/assets/terrain-dem"
GLYPH_DIR = ROOT / "android/app/src/main/assets/glyphs/Noto Sans Regular"
GLYPH_FILE = GLYPH_DIR / "0-255.pbf"

BBOX = (-10.10, 35.70, 3.50, 43.90)
MAXZOOM = 9
GLYPH_URL = (
    "https://protomaps.github.io/basemaps-assets/fonts/"
    "Noto%20Sans%20Regular/0-255.pbf"
)


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def find_pmtiles() -> Path:
    found = shutil.which("pmtiles")
    if found:
        return Path(found)
    cached = Path.home() / ".cache/camino-guard/pmtiles/pmtiles"
    if cached.is_file() and os.access(cached, os.X_OK):
        return cached
    fail("pmtiles CLI not found.")


def lon_to_x(lon: float, z: int) -> int:
    return int(math.floor((lon + 180.0) / 360.0 * (1 << z)))


def lat_to_y(lat: float, z: int) -> int:
    lat_rad = math.radians(lat)
    return int(
        math.floor(
            (1.0 - math.asinh(math.tan(lat_rad)) / math.pi)
            / 2.0
            * (1 << z)
        )
    )


def main() -> int:
    if Path.cwd().resolve() != ROOT:
        fail("Run this tool from the Camino-Guard repository root.")

    pmtiles = find_pmtiles()
    if not PMTILES_INPUT.is_file():
        fail(
            f"{PMTILES_INPUT.relative_to(ROOT)} is missing. "
            "Run tools/bundle_cached_terrain.py --force first."
        )

    if OUT_DIR.exists():
        shutil.rmtree(OUT_DIR)
    OUT_DIR.mkdir(parents=True)

    west, south, east, north = BBOX
    candidates = []
    for z in range(MAXZOOM + 1):
        x0 = lon_to_x(west, z)
        x1 = lon_to_x(east, z)
        y0 = lat_to_y(north, z)
        y1 = lat_to_y(south, z)
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                candidates.append((z, x, y))

    written = 0
    total_bytes = 0

    print(
        f"Extracting local hillshade raster tiles: "
        f"{len(candidates)} candidate Z/X/Y tiles..."
    )

    for index, (z, x, y) in enumerate(candidates, 1):
        result = subprocess.run(
            [str(pmtiles), "tile", str(PMTILES_INPUT), str(z), str(x), str(y)],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
        )

        if result.returncode == 0 and result.stdout:
            path = OUT_DIR / str(z) / str(x) / f"{y}.webp"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(result.stdout)
            written += 1
            total_bytes += len(result.stdout)

        if index % 25 == 0 or index == len(candidates):
            percent = index * 100.0 / len(candidates)
            print(
                f"\rHillshade tiles: {index:3d}/{len(candidates)} "
                f"({percent:5.1f}%) · written {written}",
                end="",
                flush=True,
            )

    print()

    if written == 0:
        fail("No hillshade WebP tiles were extracted.")

    PMTILES_INPUT.unlink(missing_ok=True)
    (PMTILES_INPUT.parent / "terrain.metadata.json").unlink(missing_ok=True)

    GLYPH_DIR.mkdir(parents=True, exist_ok=True)
    print("Downloading one tiny offline glyph range for elevation labels...")
    urllib.request.urlretrieve(GLYPH_URL, GLYPH_FILE)

    print()
    print("Offline hillshade assets ready.")
    print(f"  raster tiles: {written}")
    print(f"  raster size:  {total_bytes / 1024**2:.1f} MiB")
    print(f"  directory:    {OUT_DIR.relative_to(ROOT)}")
    print(f"  glyphs:       {GLYPH_FILE.relative_to(ROOT)}")
    print()
    print("Removed terrain.pmtiles + terrain.metadata.json from APK assets.")
    print(
        "The original cached terrain remains at "
        f"{FULL_TERRAIN.relative_to(ROOT)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
