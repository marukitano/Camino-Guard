#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
import re
from pathlib import Path

R = 6_371_000.0
SNAP_M = 10.0
PARALLEL_DEG = 35.0


def suffix(section_id):
    m = re.match(r"^\d+([A-Za-z]+)$", str(section_id or ""))
    return m.group(1).lower() if m else ""


def is_master(track):
    if "routing_primary" in track:
        return bool(track.get("routing_primary"))
    s = str(track.get("variant_suffix") or "").lower()
    return (s or suffix(track.get("section_id"))) == "a"


def coord(raw):
    if not isinstance(raw, list) or len(raw) < 2:
        return None
    try:
        lat, lon = float(raw[0]), float(raw[1])
    except (TypeError, ValueError):
        return None
    ele = None
    if len(raw) > 2 and raw[2] is not None:
        try:
            ele = float(raw[2])
        except (TypeError, ValueError):
            ele = None
    return (lat, lon, ele)


def dist(a, b):
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp = p2 - p1
    dl = math.radians(b[1] - a[1])
    h = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2 * R * math.asin(min(1.0, math.sqrt(h)))


def bearing(a, b):
    if dist(a, b) < 0.05:
        return None
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dl = math.radians(b[1] - a[1])
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1)*math.sin(p2) - math.sin(p1)*math.cos(p2)*math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def tangent(points, i):
    if len(points) < 2:
        return None
    if i == 0:
        return bearing(points[0], points[1])
    if i == len(points) - 1:
        return bearing(points[-2], points[-1])
    return bearing(points[i-1], points[i+1])


def parallel(a, b):
    if a is None or b is None:
        return False
    d = abs(a - b) % 360.0
    d = min(d, 360.0 - d)
    d = min(d, abs(180.0 - d))
    return d <= PARALLEL_DEG


def length(points):
    return sum(dist(a, b) for a, b in zip(points, points[1:]))


class MasterIndex:
    def __init__(self, masters):
        self.cell = SNAP_M / 111_320.0
        self.vertices = []
        self.grid = {}

        for track in masters:
            points = [c for raw in track.get("coordinates", []) if (c := coord(raw))]
            for i, point in enumerate(points):
                idx = len(self.vertices)
                self.vertices.append((point, tangent(points, i)))
                self.grid.setdefault(self.key(point), []).append(idx)

    def key(self, point):
        return (
            math.floor(point[0] / self.cell),
            math.floor(point[1] / self.cell),
        )

    def nearest(self, point, direction, endpoint):
        cy, cx = self.key(point)
        best = None
        best_d = SNAP_M + 1e-9

        for dy in range(-2, 3):
            for dx in range(-2, 3):
                for idx in self.grid.get((cy + dy, cx + dx), ()):
                    master_point, master_direction = self.vertices[idx]
                    d = dist(point, master_point)
                    if d > best_d:
                        continue
                    if not endpoint and not parallel(direction, master_direction):
                        continue
                    best = master_point
                    best_d = d

        return None if best is None else (best, best_d)


def normalize_route(route_id, tracks):
    masters = [t for t in tracks if is_master(t)]
    variants = [t for t in tracks if not is_master(t)]

    stats = {
        "route_group_id": route_id,
        "master_tracks": len(masters),
        "variant_tracks": len(variants),
        "variant_points_before": 0,
        "variant_points_after": 0,
        "snapped_points": 0,
        "endpoint_snaps": 0,
        "interior_snaps": 0,
        "duplicates_removed": 0,
        "max_snap_m": 0.0,
    }

    if not masters or not variants:
        return stats

    index = MasterIndex(masters)

    for track in variants:
        original = [c for raw in track.get("coordinates", []) if (c := coord(raw))]
        if len(original) < 2:
            continue

        stats["variant_points_before"] += len(original)
        out = []

        for i, point in enumerate(original):
            endpoint = i == 0 or i == len(original) - 1
            hit = index.nearest(point, tangent(original, i), endpoint)

            if hit is None:
                replacement = point
            else:
                replacement, d = hit
                stats["snapped_points"] += 1
                stats["max_snap_m"] = max(stats["max_snap_m"], d)
                if endpoint:
                    stats["endpoint_snaps"] += 1
                else:
                    stats["interior_snaps"] += 1

            if out and out[-1][0] == replacement[0] and out[-1][1] == replacement[1]:
                stats["duplicates_removed"] += 1
                continue

            out.append(replacement)

        if len(out) < 2:
            out = original

        stats["variant_points_after"] += len(out)

        track["coordinates"] = [
            [round(p[0], 8), round(p[1], 8), None if p[2] is None else round(p[2], 3)]
            for p in out
        ]

        if "points_count" in track:
            track["points_count"] = len(out)
        if "length_m" in track:
            track["length_m"] = round(length(out), 3)

        if isinstance(track.get("elevation"), dict):
            elevations = [p[2] for p in out if p[2] is not None]
            track["elevation"] = {
                "min_m": None if not elevations else round(min(elevations), 3),
                "max_m": None if not elevations else round(max(elevations), 3),
            }

    stats["max_snap_m"] = round(stats["max_snap_m"], 3)
    return stats


def normalize_document(data):
    if isinstance(data.get("routes"), list):
        return [
            normalize_route(
                str(route.get("route_group_id") or route.get("source_id") or "?"),
                route.get("tracks") or [],
            )
            for route in data["routes"]
        ]

    return [
        normalize_route(
            str(data.get("route_group_id") or data.get("source_id") or "?"),
            data.get("tracks") or [],
        )
    ]


def totals(stats):
    keys = (
        "master_tracks", "variant_tracks",
        "variant_points_before", "variant_points_after",
        "snapped_points", "endpoint_snaps", "interior_snaps",
        "duplicates_removed",
    )
    out = {key: sum(int(s.get(key, 0)) for s in stats) for key in keys}
    out["routes"] = len(stats)
    out["max_snap_m"] = max((float(s.get("max_snap_m", 0)) for s in stats), default=0.0)
    return out


def apply_current(root):
    groups = root / "data/processed/groups"
    asset = root / "android/app/src/main/assets/camino/camino-global.json"
    audit_dir = root / "data/processed/audits"
    audit_dir.mkdir(parents=True, exist_ok=True)

    group_stats = []
    if groups.is_dir():
        for path in sorted(groups.glob("*.json")):
            if not path.stem.startswith(("ES", "PT")):
                continue
            data = json.loads(path.read_text(encoding="utf-8"))
            stats = normalize_document(data)
            group_stats.extend(stats)
            path.write_text(
                json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )

    data = json.loads(asset.read_text(encoding="utf-8"))
    asset_stats = normalize_document(data)
    asset.write_text(
        json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )

    payload = {
        "schema": 1,
        "rule": {
            "same_route_group_only": True,
            "master": "routing_primary=true; fallback suffix a",
            "snap_m": SNAP_M,
            "interior_parallel_deg": PARALLEL_DEG,
            "variant_to_variant": False,
            "point_to_segment": False,
            "raw_kml_modified": False,
        },
        "android_asset": {"totals": totals(asset_stats), "routes": asset_stats},
        "processed_groups": {"totals": totals(group_stats), "routes": group_stats},
    }

    (audit_dir / "master-snap-v59.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    a = payload["android_asset"]["totals"]
    lines = [
        "# Master snap audit v59",
        "",
        "Mastertracks bleiben unverändert; Varianten derselben Camino-Familie",
        "snappen nur auf vorhandene Masterpunkte innerhalb 10 m.",
        "",
        f"- Routen: **{a['routes']}**",
        f"- Mastertracks: **{a['master_tracks']}**",
        f"- Variantentracks: **{a['variant_tracks']}**",
        f"- Variantpunkte vorher: **{a['variant_points_before']}**",
        f"- Variantpunkte nachher: **{a['variant_points_after']}**",
        f"- Snaps: **{a['snapped_points']}**",
        f"- Endpunkt-Snaps: **{a['endpoint_snaps']}**",
        f"- innere parallele Snaps: **{a['interior_snaps']}**",
        f"- Dubletten entfernt: **{a['duplicates_removed']}**",
        f"- max. Snap: **{a['max_snap_m']:.3f} m**",
    ]
    (audit_dir / "master-snap-v59.md").write_text(
        "\n".join(lines) + "\n",
        encoding="utf-8",
    )

    return a


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply-current", action="store_true")
    args = parser.parse_args()

    if args.apply_current:
        root = Path(__file__).resolve().parents[1]
        total = apply_current(root)
        print("MASTER SNAP V59")
        for key, value in total.items():
            print(f"  {key}: {value}")
        print("  raw KML: unverändert")
        return 0

    parser.error("use --apply-current")


if __name__ == "__main__":
    raise SystemExit(main())
