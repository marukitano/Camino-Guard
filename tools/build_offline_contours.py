#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sqlite3
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".cache/offline-contours"
TERRAIN = CACHE / "iberia-terrain-z0-12.pmtiles"
TILE_DIR = CACHE / "contour-tiles"
MBTILES = CACHE / "contours.mbtiles"
ASSET_DIR = ROOT / "android/app/src/main/assets/maps"
OUTPUT = ASSET_DIR / "contours.pmtiles"
METADATA = ASSET_DIR / "contours.metadata.json"

BBOX = (-10.10, 35.70, 3.50, 43.90)
MAPTERHORN_PLANET = "https://download.mapterhorn.com/planet.pmtiles"
CONTOUR_GENERATOR_VERSION = "2.0.8"

def fail(message: str) -> "NoReturn":
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)

def run(command: list[str], *, env=None) -> None:
    print()
    print("+ " + " ".join(command))
    subprocess.run(command, check=True, env=env)

def find_pmtiles() -> Path:
    found = shutil.which("pmtiles")
    if found:
        return Path(found)
    cached = Path.home() / ".cache/camino-guard/pmtiles/pmtiles"
    if cached.is_file() and os.access(cached, os.X_OK):
        return cached
    fail("pmtiles CLI not found. Run tools/fetch_offline_basemap.py first.")

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

def pack_mbtiles(tile_dir: Path, output: Path, minzoom: int, maxzoom: int) -> int:
    output.unlink(missing_ok=True)
    db = sqlite3.connect(output)
    try:
        db.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
        db.execute(
            "CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, "
            "tile_row INTEGER, tile_data BLOB)"
        )
        db.execute(
            "CREATE UNIQUE INDEX tile_index ON tiles "
            "(zoom_level, tile_column, tile_row)"
        )
        meta = {
            "name": "Camino Guard Iberia contours",
            "type": "overlay",
            "version": "1",
            "description": "Offline elevation contours for Camino Guard",
            "format": "pbf",
            "minzoom": str(minzoom),
            "maxzoom": str(maxzoom),
            "bounds": ",".join(map(str, BBOX)),
        }
        db.executemany("INSERT INTO metadata VALUES (?, ?)", meta.items())

        count = 0
        batch = []
        for path in sorted(tile_dir.glob("*/*/*.pbf")):
            z = int(path.parents[1].name)
            x = int(path.parent.name)
            y_xyz = int(path.stem)
            y_tms = (1 << z) - 1 - y_xyz
            batch.append((z, x, y_tms, path.read_bytes()))
            count += 1
            if len(batch) >= 500:
                db.executemany("INSERT INTO tiles VALUES (?, ?, ?, ?)", batch)
                db.commit()
                batch.clear()
            if count % 10000 == 0:
                print(f"Packed {count:,} contour tiles...")
        if batch:
            db.executemany("INSERT INTO tiles VALUES (?, ?, ?, ?)", batch)
        db.commit()
        return count
    finally:
        db.close()


def _read_varint(data: bytes, pos: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while True:
        if pos >= len(data):
            raise ValueError("truncated varint")
        byte = data[pos]
        pos += 1
        value |= (byte & 0x7F) << shift
        if not (byte & 0x80):
            return value, pos
        shift += 7
        if shift > 70:
            raise ValueError("varint too long")


def _protobuf_message_end(data: bytes) -> int:
    # Return the exact end of a protobuf message followed only by zero padding.
    pos = 0
    size = len(data)

    while pos < size:
        field_start = pos

        # contour-generator 2.0.8 can leave an unused zero-filled buffer tail.
        # A protobuf field tag of zero is invalid. Only accept it as padding
        # when every remaining byte is also zero.
        if data[pos] == 0 and not any(data[pos:]):
            return pos

        key, pos = _read_varint(data, pos)
        field_number = key >> 3
        wire_type = key & 0x07

        if field_number == 0:
            if not any(data[field_start:]):
                return field_start
            raise ValueError(
                f"invalid protobuf field tag at byte {field_start}"
            )

        if wire_type == 0:
            _, pos = _read_varint(data, pos)
        elif wire_type == 1:
            pos += 8
        elif wire_type == 2:
            length, pos = _read_varint(data, pos)
            pos += length
        elif wire_type == 5:
            pos += 4
        else:
            raise ValueError(
                f"unsupported protobuf wire type {wire_type} at byte {field_start}"
            )

        if pos > size:
            raise ValueError("protobuf field extends beyond tile buffer")

    return size


def sanitize_zero_padded_mvt_tiles(tile_dir: Path) -> tuple[int, int, int]:
    checked_tiles = 0
    fixed_tiles = 0
    removed_bytes = 0

    for tile_path in sorted(tile_dir.rglob("*.pbf")):
        checked_tiles += 1
        data = tile_path.read_bytes()
        end = _protobuf_message_end(data)

        if end == len(data):
            continue

        padding = data[end:]
        if not padding or any(padding):
            raise RuntimeError(
                f"Refusing to trim non-zero trailing data from {tile_path}"
            )

        tile_path.write_bytes(data[:end])
        fixed_tiles += 1
        removed_bytes += len(padding)

    if checked_tiles == 0:
        raise RuntimeError(f"No generated .pbf contour tiles found in {tile_dir}")

    return checked_tiles, fixed_tiles, removed_bytes


def _encode_varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("varint must be unsigned")
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def _zigzag_decode(value: int) -> int:
    return (value >> 1) ^ -(value & 1)


def _zigzag_encode(value: int) -> int:
    return (value << 1) ^ (value >> 63)


def _parse_proto_fields(data: bytes) -> list[tuple[int, int, object]]:
    fields: list[tuple[int, int, object]] = []
    pos = 0
    while pos < len(data):
        key, pos = _read_varint(data, pos)
        field_number = key >> 3
        wire_type = key & 0x07
        if field_number == 0:
            raise ValueError("invalid protobuf field number 0")
        if wire_type == 0:
            value, pos = _read_varint(data, pos)
            fields.append((field_number, wire_type, value))
        elif wire_type == 1:
            end = pos + 8
            if end > len(data):
                raise ValueError("truncated fixed64")
            fields.append((field_number, wire_type, data[pos:end]))
            pos = end
        elif wire_type == 2:
            length, pos = _read_varint(data, pos)
            end = pos + length
            if end > len(data):
                raise ValueError("truncated length-delimited field")
            fields.append((field_number, wire_type, data[pos:end]))
            pos = end
        elif wire_type == 5:
            end = pos + 4
            if end > len(data):
                raise ValueError("truncated fixed32")
            fields.append((field_number, wire_type, data[pos:end]))
            pos = end
        else:
            raise ValueError(f"unsupported protobuf wire type {wire_type}")
    return fields


def _encode_proto_fields(fields: list[tuple[int, int, object]]) -> bytes:
    out = bytearray()
    for field_number, wire_type, value in fields:
        out += _encode_varint((field_number << 3) | wire_type)
        if wire_type == 0:
            out += _encode_varint(int(value))
        elif wire_type in (1, 5):
            out += bytes(value)
        elif wire_type == 2:
            raw = bytes(value)
            out += _encode_varint(len(raw))
            out += raw
        else:
            raise ValueError(f"unsupported protobuf wire type {wire_type}")
    return bytes(out)


def _decode_line_geometry(data: bytes) -> list[list[tuple[int, int]]]:
    values: list[int] = []
    pos = 0
    while pos < len(data):
        value, pos = _read_varint(data, pos)
        values.append(value)

    parts: list[list[tuple[int, int]]] = []
    current = None
    x = 0
    y = 0
    i = 0

    while i < len(values):
        command = values[i]
        i += 1
        command_id = command & 0x07
        count = command >> 3

        if command_id == 1:
            for _ in range(count):
                if i + 1 >= len(values):
                    raise ValueError("truncated MoveTo command")
                x += _zigzag_decode(values[i])
                y += _zigzag_decode(values[i + 1])
                i += 2
                current = [(x, y)]
                parts.append(current)
        elif command_id == 2:
            if current is None:
                raise ValueError("LineTo before MoveTo")
            for _ in range(count):
                if i + 1 >= len(values):
                    raise ValueError("truncated LineTo command")
                x += _zigzag_decode(values[i])
                y += _zigzag_decode(values[i + 1])
                i += 2
                current.append((x, y))
        elif command_id == 7:
            continue
        else:
            raise ValueError(f"unsupported MVT geometry command {command_id}")

    return parts


def _encode_line_geometry(parts: list[list[tuple[int, int]]]) -> bytes:
    values: list[int] = []
    x = 0
    y = 0
    for part in parts:
        if len(part) < 2:
            continue
        values.append((1 << 3) | 1)
        px, py = part[0]
        values.append(_zigzag_encode(px - x))
        values.append(_zigzag_encode(py - y))
        x, y = px, py

        values.append(((len(part) - 1) << 3) | 2)
        for px, py in part[1:]:
            values.append(_zigzag_encode(px - x))
            values.append(_zigzag_encode(py - y))
            x, y = px, py

    return b"".join(_encode_varint(value) for value in values)


def _near_tile_edge(point: tuple[int, int], extent: int) -> bool:
    x, y = point
    margin = 2
    return (
        x <= margin or y <= margin
        or x >= extent - margin or y >= extent - margin
    )


def _smooth_polyline(
    points: list[tuple[int, int]],
    extent: int,
    *,
    passes: int = 2,
    strength: float = 0.22,
) -> list[tuple[int, int]]:
    # Spline-like smoothing with the same number of points.
    if len(points) < 5:
        return points

    result = list(points)
    for _ in range(passes):
        source = result
        target = [source[0]]
        for i in range(1, len(source) - 1):
            prev_pt = source[i - 1]
            cur_pt = source[i]
            next_pt = source[i + 1]

            if (
                _near_tile_edge(prev_pt, extent)
                or _near_tile_edge(cur_pt, extent)
                or _near_tile_edge(next_pt, extent)
            ):
                target.append(cur_pt)
                continue

            x = round(
                strength * prev_pt[0]
                + (1.0 - 2.0 * strength) * cur_pt[0]
                + strength * next_pt[0]
            )
            y = round(
                strength * prev_pt[1]
                + (1.0 - 2.0 * strength) * cur_pt[1]
                + strength * next_pt[1]
            )
            target.append((x, y))

        target.append(source[-1])
        result = target

    return result


def _smooth_mvt_feature(feature_data: bytes, extent: int) -> tuple[bytes, bool]:
    fields = _parse_proto_fields(feature_data)
    geom_type = None
    geometry_index = None

    for index, (field_number, wire_type, value) in enumerate(fields):
        if field_number == 3 and wire_type == 0:
            geom_type = int(value)
        elif field_number == 4 and wire_type == 2:
            geometry_index = index

    if geom_type != 2 or geometry_index is None:
        return feature_data, False

    field_number, wire_type, geometry = fields[geometry_index]
    parts = _decode_line_geometry(bytes(geometry))
    changed = False
    smoothed_parts = []

    for part in parts:
        smoothed = _smooth_polyline(part, extent)
        if smoothed != part:
            changed = True
        smoothed_parts.append(smoothed)

    if not changed:
        return feature_data, False

    fields[geometry_index] = (
        field_number,
        wire_type,
        _encode_line_geometry(smoothed_parts),
    )
    return _encode_proto_fields(fields), True


def _smooth_mvt_tile(tile_data: bytes) -> tuple[bytes, int]:
    tile_fields = _parse_proto_fields(tile_data)
    changed_features = 0

    for tile_index, (field_number, wire_type, layer_data) in enumerate(tile_fields):
        if field_number != 3 or wire_type != 2:
            continue

        layer_fields = _parse_proto_fields(bytes(layer_data))
        layer_name = None
        extent = 4096

        for lf_number, lf_wire, lf_value in layer_fields:
            if lf_number == 1 and lf_wire == 2:
                layer_name = bytes(lf_value).decode("utf-8", errors="replace")
            elif lf_number == 5 and lf_wire == 0:
                extent = int(lf_value)

        if layer_name != "contours":
            continue

        for layer_index, (lf_number, lf_wire, lf_value) in enumerate(layer_fields):
            if lf_number != 2 or lf_wire != 2:
                continue
            new_feature, changed = _smooth_mvt_feature(bytes(lf_value), extent)
            if changed:
                layer_fields[layer_index] = (lf_number, lf_wire, new_feature)
                changed_features += 1

        tile_fields[tile_index] = (
            field_number,
            wire_type,
            _encode_proto_fields(layer_fields),
        )

    if changed_features == 0:
        return tile_data, 0

    return _encode_proto_fields(tile_fields), changed_features


def smooth_generated_contour_tiles(tile_dir: Path) -> tuple[int, int]:
    tile_paths = sorted(tile_dir.rglob("*.pbf"))
    if not tile_paths:
        raise RuntimeError(f"No generated .pbf contour tiles found in {tile_dir}")

    changed_tiles = 0
    changed_features = 0
    total = len(tile_paths)

    print(
        "Smoothing contour geometry with 2 spline-like passes "
        "(same point count)..."
    )

    for index, tile_path in enumerate(tile_paths, 1):
        data = tile_path.read_bytes()
        smoothed, feature_count = _smooth_mvt_tile(data)

        if feature_count:
            tile_path.write_bytes(smoothed)
            changed_tiles += 1
            changed_features += feature_count

        if index % 5000 == 0 or index == total:
            percent = index * 100.0 / total
            print(
                f"\rContour smoothing: {index:,}/{total:,} "
                f"({percent:5.1f}%) · changed {changed_tiles:,} tiles",
                end="",
                flush=True,
            )

    print()
    return changed_tiles, changed_features

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--increment", type=int, default=25)
    parser.add_argument("--minzoom", type=int, default=7)
    parser.add_argument("--maxzoom", type=int, default=13)
    parser.add_argument("--processes", type=int, default=min(8, os.cpu_count() or 4))
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--keep-work", action="store_true")
    args = parser.parse_args()

    if Path.cwd().resolve() != ROOT:
        fail("Run this tool from the Camino-Guard repository root.")
    if args.increment <= 0:
        fail("--increment must be greater than zero.")
    if not (5 <= args.minzoom <= args.maxzoom <= 16):
        fail("Require 5 <= minzoom <= maxzoom <= 16.")
    if args.processes < 1:
        fail("--processes must be at least 1.")

    for binary in ("node", "npx"):
        if not shutil.which(binary):
            fail(
                f"{binary} is missing. Install Node.js/npm first "
                "(for example: sudo apt install nodejs npm)."
            )

    pmtiles = find_pmtiles()
    CACHE.mkdir(parents=True, exist_ok=True)
    ASSET_DIR.mkdir(parents=True, exist_ok=True)

    if OUTPUT.exists() and not args.force:
        fail(f"{OUTPUT.relative_to(ROOT)} already exists; use --force to rebuild.")

    if not TERRAIN.exists():
        print("Downloading one-time Mapterhorn terrain extract for Iberia...")
        part = Path(str(TERRAIN) + ".part")
        part.unlink(missing_ok=True)
        env = os.environ.copy()
        old_debug = env.get("GODEBUG", "")
        env["GODEBUG"] = f"{old_debug},http2client=0" if old_debug else "http2client=0"
        run([
            str(pmtiles), "extract", MAPTERHORN_PLANET, str(part),
            "--bbox=" + ",".join(map(str, BBOX)),
            "--download-threads=2",
        ], env=env)
        part.replace(TERRAIN)
        run([str(pmtiles), "verify", str(TERRAIN)])
    else:
        print(
            "Reusing cached terrain: "
            f"{TERRAIN.relative_to(ROOT)} ({human_size(TERRAIN.stat().st_size)})"
        )

    if TILE_DIR.exists():
        if args.force:
            shutil.rmtree(TILE_DIR)
        else:
            fail(f"{TILE_DIR.relative_to(ROOT)} already exists; use --force.")
    TILE_DIR.mkdir(parents=True)

    dem_url = "pmtiles://" + str(TERRAIN.resolve())
    print()
    print(
        f"Generating contours: {args.increment} m, "
        f"z{args.minzoom}-z{args.maxzoom}, {args.processes} processes"
    )
    print("Whole-Iberia contour generation can take a long time; that is normal.")

    run([
        "npx", "--yes", f"contour-generator@{CONTOUR_GENERATOR_VERSION}",
        "bbox",
        "--minx", str(BBOX[0]), "--miny", str(BBOX[1]),
        "--maxx", str(BBOX[2]), "--maxy", str(BBOX[3]),
        "--demUrl", dem_url,
        "--sourceMaxZoom", "12",
        "--encoding", "terrarium",
        "--increment", str(args.increment),
        "--outputMinZoom", str(args.minzoom),
        "--outputMaxZoom", str(args.maxzoom),
        "--outputDir", str(TILE_DIR),
        "--processes", str(args.processes),
    ])

    print()
    print("\nSanitizing generated MVT contour tiles...")
    checked_tiles, fixed_tiles, removed_bytes = sanitize_zero_padded_mvt_tiles(TILE_DIR)
    print(
        f"Checked {checked_tiles:,} tiles; trimmed zero-buffer padding from "
        f"{fixed_tiles:,} tiles ({removed_bytes / (1024 * 1024):.1f} MiB)."
    )

    print()
    changed_tiles, changed_features = smooth_generated_contour_tiles(TILE_DIR)
    print(
        f"Smoothed {changed_features:,} contour features "
        f"in {changed_tiles:,} tiles."
    )

    print("Packing generated contour tiles...")
    tile_count = pack_mbtiles(TILE_DIR, MBTILES, args.minzoom, args.maxzoom)
    if tile_count == 0:
        fail("contour-generator produced no PBF tiles.")

    part = Path(str(OUTPUT) + ".part")
    part.unlink(missing_ok=True)
    run([str(pmtiles), "convert", str(MBTILES), str(part)])
    run([str(pmtiles), "verify", str(part)])
    part.replace(OUTPUT)

    size = OUTPUT.stat().st_size
    digest = sha256_file(OUTPUT)
    METADATA.write_text(
        json.dumps({
            "schema": 1,
            "name": "Camino Guard Iberia contours",
            "source": "Mapterhorn terrain data",
            "source_url": MAPTERHORN_PLANET,
            "generator": f"contour-generator {CONTOUR_GENERATOR_VERSION}",
            "encoding": "terrarium",
            "interval_m": args.increment,
            "minzoom": args.minzoom,
            "maxzoom": args.maxzoom,
            "tile_count": tile_count,
            "bbox": list(BBOX),
            "size_bytes": size,
            "sha256": digest,
            "attribution": "Terrain data: Mapterhorn open-data sources",
        }, indent=2) + "\n",
        encoding="utf-8",
    )

    if not args.keep_work:
        shutil.rmtree(TILE_DIR, ignore_errors=True)
        MBTILES.unlink(missing_ok=True)

    print()
    print("Offline contour archive ready.")
    print(f"  file:      {OUTPUT.relative_to(ROOT)}")
    print(f"  size:      {human_size(size)}")
    print(f"  tiles:     {tile_count:,}")
    print(f"  interval:  {args.increment} m")
    print(f"  zoom:      {args.minzoom}-{args.maxzoom}")
    print(f"  sha256:    {digest}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
