#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE_TERRAIN = ROOT / ".cache/offline-contours/iberia-terrain-z0-12.pmtiles"
ASSET_DIR = ROOT / "android/app/src/main/assets/maps"
OUTPUT = ASSET_DIR / "terrain.pmtiles"
METADATA = ASSET_DIR / "terrain.metadata.json"

MAXZOOM = 9
SOURCE_URL = "https://download.mapterhorn.com/planet.pmtiles"


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

    fail("pmtiles CLI not found. Run tools/fetch_offline_basemap.py first.")


def run(command: list[str]) -> None:
    print()
    print("+ " + " ".join(command))
    subprocess.run(command, check=True)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def human_size(size: int) -> str:
    value = float(size)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if value < 1024 or unit == "TiB":
            return f"{value:.2f} {unit}"
        value /= 1024
    return str(size)


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Create a compact z0-z9 hillshade DEM from Camino Guard's "
            "already-cached Iberia terrain archive."
        )
    )
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    if Path.cwd().resolve() != ROOT:
        fail("Run this tool from the Camino-Guard repository root.")

    if not CACHE_TERRAIN.is_file():
        fail(f"Cached terrain is missing: {CACHE_TERRAIN.relative_to(ROOT)}")

    if OUTPUT.exists() and not args.force:
        fail(
            f"{OUTPUT.relative_to(ROOT)} already exists; "
            "use --force to replace it."
        )

    pmtiles = find_pmtiles()
    ASSET_DIR.mkdir(parents=True, exist_ok=True)

    part = Path(str(OUTPUT) + ".part")
    part.unlink(missing_ok=True)

    print(
        "Creating compact cosmetic hillshade DEM from cached terrain "
        f"(z0-z{MAXZOOM})..."
    )

    run([
        str(pmtiles),
        "extract",
        str(CACHE_TERRAIN),
        str(part),
        f"--maxzoom={MAXZOOM}",
    ])
    run([str(pmtiles), "verify", str(part)])

    size = part.stat().st_size
    digest = sha256_file(part)

    OUTPUT.unlink(missing_ok=True)
    part.replace(OUTPUT)

    METADATA.write_text(
        json.dumps(
            {
                "schema": 1,
                "name": "Camino Guard Iberia cosmetic hillshade DEM",
                "source": "Mapterhorn open-data sources",
                "source_url": SOURCE_URL,
                "encoding": "terrarium",
                "minzoom": 0,
                "maxzoom": MAXZOOM,
                "size_bytes": size,
                "sha256": digest,
                "attribution": "Terrain data: Mapterhorn open-data sources",
                "purpose": "cosmetic hillshade only",
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )

    print()
    print("Compact hillshade terrain ready.")
    print(f"  file:      {OUTPUT.relative_to(ROOT)}")
    print(f"  size:      {human_size(size)}")
    print(f"  zoom:      0-{MAXZOOM}")
    print(f"  sha256:    {digest}")
    print(f"  metadata:  {METADATA.relative_to(ROOT)}")
    print()
    print("The original cached z12 terrain file was NOT modified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
