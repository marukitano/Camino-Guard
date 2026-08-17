#!/usr/bin/env python3
"""Fetch Camino Guard's bundled Spain/Portugal offline vector basemap.

The script downloads a geographic extract from the current Protomaps daily
planet PMTiles archive. The extraction region is:

* Geofabrik's Spain country polygon
* Geofabrik's Portugal country polygon
* a small buffered bounding box around CNIG ES01a stage 01a
  (Saint-Jean-Pied-de-Port -> Roncesvalles)

No French route groups are used to define the basemap region.

The resulting PMTiles file is written directly into Android assets so any APK
built afterwards contains the map data. The generated PMTiles and metadata are
ignored by Git because the archive is intentionally large and reproducible.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import platform
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import urllib.error
import urllib.request
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ASSET_DIR = ROOT / "android/app/src/main/assets/maps"
OUTPUT = ASSET_DIR / "iberia.pmtiles"
METADATA = ASSET_DIR / "iberia.metadata.json"
WORK_DIR = ROOT / ".cache/offline-basemap"
REGION_JSON = WORK_DIR / "iberia-region.geojson"

SPAIN_POLY_URL = "https://download.geofabrik.de/europe/spain.poly"
PORTUGAL_POLY_URL = "https://download.geofabrik.de/europe/portugal.poly"
PROTOMAPS_BUILD_TEMPLATE = "https://build.protomaps.com/{date}.pmtiles"
PMTILES_RELEASE_API = "https://api.github.com/repos/protomaps/go-pmtiles/releases/latest"

FRANCES_FIRST_STAGE = (
    ROOT
    / "data/raw/cnig/ES01a/ES01a_01a_saint_jean_pied_de_port-roncesvalles.kml"
)

USER_AGENT = "Camino-Guard-offline-basemap/1"


def fail(message: str) -> "NoReturn":
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def request(url: str, *, method: str = "GET"):
    req = urllib.request.Request(
        url,
        method=method,
        headers={"User-Agent": USER_AGENT},
    )
    return urllib.request.urlopen(req, timeout=45)


def download_text(url: str) -> str:
    with request(url) as response:
        return response.read().decode("utf-8")


def download_file(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    part = destination.with_name(destination.name + ".part")
    part.unlink(missing_ok=True)
    print(f"Downloading {url}")
    try:
        with request(url) as response, part.open("wb") as output:
            shutil.copyfileobj(response, output, length=1024 * 1024)
    except Exception:
        part.unlink(missing_ok=True)
        raise
    part.replace(destination)


def parse_poly(text: str, label: str) -> list[list[list[float]]]:
    """Return non-hole rings from a Geofabrik .poly file as GeoJSON rings."""
    lines = [line.rstrip() for line in text.splitlines()]
    if len(lines) < 3:
        fail(f"{label}: invalid .poly file")

    rings: list[list[list[float]]] = []
    index = 1  # line zero is dataset name

    while index < len(lines):
        token = lines[index].strip()
        index += 1
        if not token:
            continue
        if token == "END":
            break

        is_hole = token.startswith("!")
        ring: list[list[float]] = []

        while index < len(lines):
            line = lines[index].strip()
            index += 1
            if line == "END":
                break
            if not line:
                continue
            parts = line.split()
            if len(parts) < 2:
                fail(f"{label}: malformed coordinate line: {line!r}")
            ring.append([float(parts[0]), float(parts[1])])

        # For an extraction region, ignoring holes is deliberately conservative:
        # it can include a little extra data but cannot omit wanted map data.
        if not is_hole and len(ring) >= 3:
            if ring[0] != ring[-1]:
                ring.append(ring[0])
            rings.append(ring)

    if not rings:
        fail(f"{label}: no outer rings found in .poly file")
    return rings


def camino_border_bbox(margin_degrees: float = 0.15) -> list[float]:
    if not FRANCES_FIRST_STAGE.is_file():
        fail(f"Missing CNIG stage: {FRANCES_FIRST_STAGE.relative_to(ROOT)}")

    try:
        root = ET.parse(FRANCES_FIRST_STAGE).getroot()
    except ET.ParseError as exc:
        fail(f"Could not parse first Camino Frances KML: {exc}")

    coords: list[tuple[float, float]] = []
    for element in root.iter():
        if not element.tag.endswith("coordinates") or not element.text:
            continue
        for token in element.text.split():
            pieces = token.split(",")
            if len(pieces) < 2:
                continue
            try:
                lon = float(pieces[0])
                lat = float(pieces[1])
            except ValueError:
                continue
            coords.append((lon, lat))

    if not coords:
        fail("First Camino Frances KML contains no coordinates")

    min_lon = min(point[0] for point in coords) - margin_degrees
    min_lat = min(point[1] for point in coords) - margin_degrees
    max_lon = max(point[0] for point in coords) + margin_degrees
    max_lat = max(point[1] for point in coords) + margin_degrees
    return [min_lon, min_lat, max_lon, max_lat]


def bbox_ring(bounds: list[float]) -> list[list[float]]:
    min_lon, min_lat, max_lon, max_lat = bounds
    return [
        [min_lon, min_lat],
        [max_lon, min_lat],
        [max_lon, max_lat],
        [min_lon, max_lat],
        [min_lon, min_lat],
    ]


def build_region() -> tuple[dict, list[float]]:
    print("Fetching Spain and Portugal extraction polygons from Geofabrik...")
    spain = parse_poly(download_text(SPAIN_POLY_URL), "Spain")
    portugal = parse_poly(download_text(PORTUGAL_POLY_URL), "Portugal")
    border = camino_border_bbox()

    features = []
    for country, rings in (("Spain", spain), ("Portugal", portugal)):
        for number, ring in enumerate(rings, start=1):
            features.append(
                {
                    "type": "Feature",
                    "properties": {"name": f"{country} {number}"},
                    "geometry": {"type": "Polygon", "coordinates": [ring]},
                }
            )

    features.append(
        {
            "type": "Feature",
            "properties": {
                "name": "Saint-Jean-Pied-de-Port to Roncesvalles corridor"
            },
            "geometry": {
                "type": "Polygon",
                "coordinates": [bbox_ring(border)],
            },
        }
    )

    return {"type": "FeatureCollection", "features": features}, border


def url_exists(url: str) -> bool:
    try:
        with request(url, method="HEAD") as response:
            return 200 <= response.status < 400
    except urllib.error.HTTPError as exc:
        if exc.code in (403, 405):
            # Some object stores reject HEAD. Open a one-byte range instead.
            req = urllib.request.Request(
                url,
                headers={
                    "User-Agent": USER_AGENT,
                    "Range": "bytes=0-0",
                },
            )
            try:
                with urllib.request.urlopen(req, timeout=45) as response:
                    return response.status in (200, 206)
            except Exception:
                return False
        return False
    except Exception:
        return False


def find_build(explicit: str | None) -> tuple[str, str]:
    if explicit:
        try:
            dt.datetime.strptime(explicit, "%Y%m%d")
        except ValueError:
            fail("--build-date must be YYYYMMDD")
        url = PROTOMAPS_BUILD_TEMPLATE.format(date=explicit)
        if not url_exists(url):
            fail(f"Protomaps build does not exist: {url}")
        return explicit, url

    today = dt.date.today()
    print("Finding the newest available Protomaps daily build...")
    for days_back in range(0, 14):
        candidate = today - dt.timedelta(days=days_back)
        build_date = candidate.strftime("%Y%m%d")
        url = PROTOMAPS_BUILD_TEMPLATE.format(date=build_date)
        if url_exists(url):
            return build_date, url

    fail("Could not find a Protomaps daily build from the last 14 days")


def pmtiles_binary() -> tuple[Path, str]:
    existing = shutil.which("pmtiles")
    if existing:
        return Path(existing), "system"

    system = platform.system().lower()
    machine = platform.machine().lower()
    if system != "linux" or machine not in {"x86_64", "amd64"}:
        fail(
            "Automatic pmtiles CLI bootstrap currently supports Linux x86_64 only. "
            "Install pmtiles manually and rerun."
        )

    cache_root = Path.home() / ".cache/camino-guard/pmtiles"
    cached = cache_root / "pmtiles"
    version_file = cache_root / "version.txt"
    if cached.is_file() and os.access(cached, os.X_OK):
        version = version_file.read_text().strip() if version_file.is_file() else "cached"
        return cached, version

    print("pmtiles CLI not found; fetching latest official release metadata...")
    with request(PMTILES_RELEASE_API) as response:
        release = json.load(response)

    tag = str(release.get("tag_name") or "unknown")
    assets = release.get("assets") or []
    selected = None
    for asset in assets:
        name = str(asset.get("name") or "")
        lower = name.lower()
        if "linux" not in lower:
            continue
        if "x86_64" not in lower and "amd64" not in lower:
            continue
        if not (lower.endswith(".zip") or lower.endswith(".tar.gz") or lower.endswith(".tgz")):
            continue
        selected = asset
        break

    if not selected:
        names = ", ".join(str(asset.get("name")) for asset in assets)
        fail(f"Could not identify Linux x86_64 pmtiles release asset. Assets: {names}")

    asset_url = selected["browser_download_url"]
    archive_name = selected["name"]
    cache_root.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="camino-pmtiles-") as temp_dir_str:
        temp_dir = Path(temp_dir_str)
        archive = temp_dir / archive_name
        download_file(asset_url, archive)
        unpacked = temp_dir / "unpacked"
        unpacked.mkdir()

        lower = archive_name.lower()
        if lower.endswith(".zip"):
            with zipfile.ZipFile(archive) as zf:
                zf.extractall(unpacked)
        else:
            with tarfile.open(archive, "r:*") as tf:
                tf.extractall(unpacked)

        candidates = [
            path for path in unpacked.rglob("pmtiles")
            if path.is_file()
        ]
        if not candidates:
            fail("Downloaded pmtiles release archive did not contain a 'pmtiles' binary")

        shutil.copy2(candidates[0], cached)

    cached.chmod(cached.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    version_file.write_text(tag + "\n", encoding="utf-8")
    return cached, tag


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def human_size(size: int) -> str:
    value = float(size)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if value < 1024.0 or unit == "TiB":
            return f"{value:.2f} {unit}"
        value /= 1024.0
    return f"{size} B"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--maxzoom",
        type=int,
        default=15,
        help="maximum vector tile zoom to extract (default: 15)",
    )
    parser.add_argument(
        "--build-date",
        help="pin a Protomaps daily build as YYYYMMDD instead of newest available",
    )
    parser.add_argument(
        "--download-threads",
        type=int,
        default=8,
        help="parallel range downloads for pmtiles extract (default: 8)",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="replace an existing local basemap",
    )
    args = parser.parse_args()

    if ROOT != Path.cwd().resolve():
        fail("Run this tool from the Camino-Guard repository root")
    if not (ROOT / ".git").is_dir():
        fail("Repository .git directory not found")
    if not 0 <= args.maxzoom <= 15:
        fail("--maxzoom must be between 0 and 15 for the Protomaps basemap")
    if args.download_threads < 1:
        fail("--download-threads must be at least 1")
    if OUTPUT.exists() and not args.force:
        fail(f"{OUTPUT.relative_to(ROOT)} already exists; use --force to replace it")

    WORK_DIR.mkdir(parents=True, exist_ok=True)
    ASSET_DIR.mkdir(parents=True, exist_ok=True)

    region, border_bounds = build_region()
    REGION_JSON.write_text(
        json.dumps(region, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"Region written to {REGION_JSON.relative_to(ROOT)}")
    print(
        "French-border corridor bbox: "
        + ",".join(f"{value:.5f}" for value in border_bounds)
    )

    build_date, build_url = find_build(args.build_date)
    print(f"Using Protomaps build: {build_date}")
    print(f"Source: {build_url}")

    pmtiles, pmtiles_version = pmtiles_binary()
    print(f"pmtiles CLI: {pmtiles} ({pmtiles_version})")

    temp_output = OUTPUT.with_name(OUTPUT.name + ".part")
    temp_output.unlink(missing_ok=True)

    command = [
        str(pmtiles),
        "extract",
        build_url,
        str(temp_output),
        f"--region={REGION_JSON}",
        f"--maxzoom={args.maxzoom}",
        f"--download-threads={args.download_threads}",
    ]

    print()
    print("Extracting Spain + Portugal + Camino Frances border corridor...")
    print("This can transfer several gigabytes and may take a while.")
    print()

    try:
        subprocess.run(command, check=True)
        subprocess.run([str(pmtiles), "verify", str(temp_output)], check=True)
    except subprocess.CalledProcessError as exc:
        temp_output.unlink(missing_ok=True)
        fail(f"pmtiles command failed with exit code {exc.returncode}")

    temp_output.replace(OUTPUT)
    size = OUTPUT.stat().st_size
    digest = sha256_file(OUTPUT)

    metadata = {
        "schema": 1,
        "name": "Camino Guard Iberia offline basemap",
        "source": "Protomaps daily OpenStreetMap basemap",
        "source_url": build_url,
        "source_build_date": build_date,
        "pmtiles_cli": pmtiles_version,
        "minzoom": 0,
        "maxzoom": args.maxzoom,
        "size_bytes": size,
        "sha256": digest,
        "region": {
            "spain": SPAIN_POLY_URL,
            "portugal": PORTUGAL_POLY_URL,
            "frances_border_stage": str(FRANCES_FIRST_STAGE.relative_to(ROOT)),
            "frances_border_bbox": border_bounds,
        },
        "attribution": "Protomaps © OpenStreetMap contributors",
    }
    METADATA.write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    print()
    print("Offline basemap ready.")
    print(f"  file:   {OUTPUT.relative_to(ROOT)}")
    print(f"  size:   {human_size(size)}")
    print(f"  sha256: {digest}")
    print(f"  zoom:   0-{args.maxzoom}")
    print()
    print("The PMTiles file is ignored by Git but will be bundled into the APK.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
