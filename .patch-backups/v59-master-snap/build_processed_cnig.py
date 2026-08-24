#!/usr/bin/env python3
"""
Build processed Camino-Guard data from the raw CNIG KML collection.

This converter intentionally keeps the raw data unchanged. It creates a more
human-friendly processed layer with route catalogs, place catalogs, per-group
JSON exports, and an optional ASCII tree view.

Examples, run from the Camino-Guard repository root:

    python3 tools/build_processed_cnig.py
    python3 tools/build_processed_cnig.py --network-tree santiago
    python3 tools/build_processed_cnig.py --tree-groups ES10a,ES10b,ES10c,ES10d,ES10e --tree-root baena --tree-reverse
    python3 tools/build_processed_cnig.py --tree-groups ES10a,ES10b,ES10c,ES10d,ES10e --tree-root baena --tree-reverse --tree-output data/processed/trees/mozarabe_baena.txt

The processed output is currently JSON-first and audit-friendly. Later, we can
still generate a more stylized subway-map-like visualization from the same
logical graph.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import math
import sys
from collections import defaultdict, deque
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

PSEUDO_PLACE_KEYS = {
    "variante",
    "variant",
    "variante1",
    "variante2",
    "ramal",
    "alternativa",
}

SMALL_WORDS = {
    "a",
    "al",
    "and",
    "au",
    "aux",
    "by",
    "da",
    "das",
    "de",
    "del",
    "des",
    "do",
    "dos",
    "du",
    "e",
    "el",
    "en",
    "et",
    "i",
    "la",
    "las",
    "le",
    "les",
    "los",
    "of",
    "or",
    "os",
    "sur",
    "the",
    "und",
    "y",
}


@dataclass(frozen=True)
class LogicalEdge:
    from_key: str
    to_key: str
    from_display: str
    to_display: str
    track_ids: tuple[str, ...]
    route_codes: tuple[str, ...]
    section_ids: tuple[str, ...]
    length_m_min: float
    length_m_max: float
    track_count: int
    contains_variant: bool


@dataclass
class TreeGraph:
    outgoing: dict[str, list[LogicalEdge]]
    incoming: dict[str, list[LogicalEdge]]
    display_names: dict[str, str]


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def require_repo_root() -> Path:
    root = Path.cwd().resolve()

    if not (root / ".git").is_dir():
        fail("Run this command from the Camino-Guard repository root.")

    return root


def load_analyzer(repo_root: Path):
    module_path = repo_root / "tools" / "analyze_cnig.py"

    if not module_path.is_file():
        fail("tools/analyze_cnig.py not found. Apply patch_010 first.")

    spec = importlib.util.spec_from_file_location(
        "camino_guard_analyze_cnig",
        module_path,
    )

    if spec is None or spec.loader is None:
        fail("Could not load tools/analyze_cnig.py")

    module = importlib.util.module_from_spec(spec)

    # Python 3.12 dataclasses resolve type information through sys.modules
    # while the imported module is being executed. Register the dynamically
    # loaded analyzer before exec_module(), just like the normal import
    # machinery does.
    sys.modules[spec.name] = module

    try:
        spec.loader.exec_module(module)
    except Exception:
        sys.modules.pop(spec.name, None)
        raise

    return module


def friendly_route_name(official_name: str | None, route_code: str) -> str:
    if not official_name:
        return route_code

    if " - " not in official_name:
        return official_name.strip()

    parts = [part.strip() for part in official_name.split(" - ") if part.strip()]

    if len(parts) <= 1:
        return official_name.strip()

    return " - ".join(parts[1:])


def smart_title_words(text: str) -> str:
    words = text.replace("_", " ").split()

    if not words:
        return ""

    result: list[str] = []

    for index, word in enumerate(words):
        lower = word.lower()

        if lower in SMALL_WORDS and index != 0:
            result.append(lower)
            continue

        pieces = lower.split("-")
        titled_pieces = [piece.capitalize() if piece else piece for piece in pieces]
        result.append("-".join(titled_pieces))

    return " ".join(result)


def display_place(track_places, analyzer) -> str:
    return smart_title_words(analyzer.display_place_name(track_places.from_raw))


def display_key(key: str, graph, analyzer) -> str:
    display = graph.display_names.get(key, key)
    return smart_title_words(display)


def is_pseudo_place(key: str) -> bool:
    """Return True for semantic labels that describe a route variant, not a town."""
    normalized = key.strip().lower()

    if normalized in PSEUDO_PLACE_KEYS:
        return True

    return any(
        normalized.startswith(prefix)
        for prefix in (
            "variante",
            "variant",
            "ramal",
            "alternativa",
            "alternative",
        )
    )


def sort_track_key(track) -> tuple[Any, ...]:
    section = track.section_id
    digits = ""
    suffix = ""

    for char in section:
        if char.isdigit() and not suffix:
            digits += char
        else:
            suffix += char

    number = int(digits) if digits else math.inf
    return (number, suffix.lower(), section.lower(), track.label.lower())


def portable_track_path(path: Path) -> str:
    """
    Return a repository-relative raw CNIG path for persisted processed data.

    Track objects are loaded from absolute local filesystem paths, but processed
    JSON must not contain machine-specific prefixes such as /home/<user>/...
    or shell placeholders such as $HOME.  The stable repository-relative part
    starts at data/raw/cnig/.
    """
    parts = path.parts

    for index in range(len(parts) - 2):
        if parts[index:index + 3] == ("data", "raw", "cnig"):
            return Path(*parts[index:]).as_posix()

    raise ValueError(
        f"Track path is not inside data/raw/cnig: {path}"
    )


def sort_group_tracks(groups: dict[str, list[Any]]) -> None:
    for route_code in groups:
        groups[route_code].sort(key=sort_track_key)


def load_official_names(analyzer) -> dict[str, str]:
    try:
        print("Reading official Camino names from CNIG...")
        return analyzer.parse_group_names(analyzer.fetch_cnig_index())
    except Exception as exc:  # pragma: no cover - graceful fallback in real repo
        print(
            f"WARNING: Could not fetch official names ({exc}); continuing without them.",
            file=sys.stderr,
        )
        return {}


def load_groups(repo_root: Path, analyzer) -> tuple[dict[str, list[Any]], dict[str, str]]:
    raw_root = repo_root / "data" / "raw" / "cnig"

    if not raw_root.is_dir():
        fail("data/raw/cnig is missing.")

    print("Reading local CNIG tracks...")
    paths = sorted(raw_root.rglob("*.kml"))
    tracks, errors = analyzer.load_tracks(paths)

    if errors:
        fail(f"{len(errors)} KML file(s) could not be parsed. First error: {errors[0]}")

    groups: dict[str, list[Any]] = defaultdict(list)

    for track in tracks:
        groups[track.route_code].append(track)

    sort_group_tracks(groups)
    return dict(groups), load_official_names(analyzer)


def component_edge_counts(topology, graph) -> dict[int, int]:
    counts: dict[int, int] = {}

    for index, component in enumerate(topology.components, 1):
        count = 0

        for edge in graph.parseable_tracks:
            if edge.from_key in component and edge.to_key in component:
                count += 1

        counts[index] = count

    return counts


def collect_place_catalog(groups: dict[str, list[Any]], analyzer) -> dict[str, Any]:
    all_tracks = [track for route_tracks in groups.values() for track in route_tracks]
    graph = analyzer.build_place_graph(all_tracks)

    membership: dict[str, set[str]] = defaultdict(set)

    for route_code, route_tracks in groups.items():
        for track in route_tracks:
            places = analyzer.parse_track_places(track)

            if places is None:
                continue

            membership[places.from_key].add(route_code)
            membership[places.to_key].add(route_code)

    places = []

    for key in sorted(graph.display_names):
        places.append(
            {
                "place_key": key,
                "display_name": smart_title_words(graph.display_names[key]),
                "raw_display_name": graph.display_names[key],
                "pseudo_place": is_pseudo_place(key),
                "route_groups": sorted(membership.get(key, set())),
            }
        )

    return {
        "count": len(places),
        "items": places,
    }


def component_summary(route_code: str, component_index: int, component: set[str], topology, graph, analyzer) -> dict[str, Any]:
    sources = sorted(key for key in component if key in topology.sources)
    sinks = sorted(key for key in component if key in topology.sinks)
    branches = sorted(key for key in component if key in topology.branch_places)
    merges = sorted(key for key in component if key in topology.merge_places)

    if len(sources) == 1 and len(sinks) == 1:
        span = f"{display_key(sources[0], graph, analyzer)} → {display_key(sinks[0], graph, analyzer)}"
    else:
        source_label = " / ".join(display_key(key, graph, analyzer) for key in sources) or "?"
        sink_label = " / ".join(display_key(key, graph, analyzer) for key in sinks) or "?"
        span = f"{source_label} → {sink_label}"

    return {
        "component_id": f"{route_code}-C{component_index}",
        "display_span": span,
        "place_keys": sorted(component),
        "place_names": [display_key(key, graph, analyzer) for key in sorted(component)],
        "sources": [display_key(key, graph, analyzer) for key in sources],
        "source_keys": sources,
        "sinks": [display_key(key, graph, analyzer) for key in sinks],
        "sink_keys": sinks,
        "branch_places": [display_key(key, graph, analyzer) for key in branches],
        "merge_places": [display_key(key, graph, analyzer) for key in merges],
        "pseudo_places": [display_key(key, graph, analyzer) for key in sorted(component) if is_pseudo_place(key)],
        "place_count": len(component),
    }


def make_track_record(track, analyzer) -> dict[str, Any]:
    places = analyzer.parse_track_places(track)
    coords = analyzer.parse_coordinates(track.path)

    section_suffix = ""
    for char in track.section_id:
        if char.isalpha():
            section_suffix += char

    record = {
        "track_id": f"{track.route_code}:{track.section_id}",
        "route_group_id": track.route_code,
        "section_id": track.section_id,
        "variant_suffix": section_suffix.lower(),
        "raw_label": track.label,
        "length_m": round(track.length_m, 3),
        "points_count": track.points,
        "raw_path": portable_track_path(track.path),
        "coordinates": [
            [round(lat, 8), round(lon, 8), None if ele is None else round(ele, 3)]
            for lat, lon, ele in coords
        ],
        "elevation": {
            "min_m": None if track.min_ele is None else round(track.min_ele, 3),
            "max_m": None if track.max_ele is None else round(track.max_ele, 3),
        },
    }

    if places is not None:
        record.update(
            {
                "from_key": places.from_key,
                "from": smart_title_words(analyzer.display_place_name(places.from_raw)),
                "to_key": places.to_key,
                "to": smart_title_words(analyzer.display_place_name(places.to_raw)),
                "pseudo_from": is_pseudo_place(places.from_key),
                "pseudo_to": is_pseudo_place(places.to_key),
            }
        )
    else:
        record.update(
            {
                "from_key": None,
                "from": None,
                "to_key": None,
                "to": None,
                "pseudo_from": False,
                "pseudo_to": False,
            }
        )

    return record


def make_route_record(route_code: str, tracks: list[Any], official_name: str | None, analyzer) -> dict[str, Any]:
    graph = analyzer.build_place_graph(tracks)
    topology = analyzer.build_semantic_topology(tracks)
    components = []
    edge_counts = component_edge_counts(topology, graph)

    for index, component in enumerate(topology.components, 1):
        summary = component_summary(route_code, index, component, topology, graph, analyzer)
        summary["track_count"] = edge_counts[index]
        components.append(summary)

    friendly_name = friendly_route_name(official_name, route_code)

    if len(components) == 1:
        display_name = f"{friendly_name} — {components[0]['display_span']}"
    elif components:
        major_component = max(
            components,
            key=lambda component: (
                component["track_count"],
                component["place_count"],
            ),
        )
        display_name = (
            f"{friendly_name} — {major_component['display_span']} "
            f"(+{len(components) - 1} components)"
        )
    else:
        display_name = friendly_name

    return {
        "route_group_id": route_code,
        "source_id": route_code,
        "official_name": official_name,
        "name": friendly_name,
        "display_name": display_name,
        "topology_class": topology.classification,
        "track_count": len(tracks),
        "place_count": topology.places,
        "component_count": topology.weak_components,
        "parallel_pairs": topology.parallel_pairs,
        "cyclic_components": topology.cyclic_sccs,
        "components": components,
        "tracks": [make_track_record(track, analyzer) for track in tracks],
    }


def build_catalog(route_records: list[dict[str, Any]]) -> dict[str, Any]:
    items = []

    for record in route_records:
        items.append(
            {
                "route_group_id": record["route_group_id"],
                "source_id": record["source_id"],
                "official_name": record["official_name"],
                "name": record["name"],
                "display_name": record["display_name"],
                "topology_class": record["topology_class"],
                "track_count": record["track_count"],
                "place_count": record["place_count"],
                "component_count": record["component_count"],
                "components": [
                    {
                        "component_id": component["component_id"],
                        "display_span": component["display_span"],
                        "track_count": component["track_count"],
                        "place_count": component["place_count"],
                        "sources": component["sources"],
                        "sinks": component["sinks"],
                    }
                    for component in record["components"]
                ],
            }
        )

    return {
        "count": len(items),
        "items": items,
    }


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def build_processed_output(repo_root: Path, analyzer, groups: dict[str, list[Any]], official_names: dict[str, str], output_root: Path, place_merge_distance_m: float) -> tuple[dict[str, Any], list[dict[str, Any]], dict[str, Any]]:
    route_records = [
        make_route_record(route_code, groups[route_code], official_names.get(route_code), analyzer)
        for route_code in sorted(groups)
    ]

    metadata = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "source": "CNIG Camino KML",
        "generator": "tools/build_processed_cnig.py",
        "route_groups": len(route_records),
        "raw_tracks": sum(len(groups[route_code]) for route_code in groups),
    }

    print("Building global Camino place network...")
    network = build_global_network(
        groups,
        official_names,
        analyzer,
        place_merge_distance_m=place_merge_distance_m,
    )

    write_json(output_root / "metadata.json", metadata)
    write_json(output_root / "catalog.json", {**metadata, **build_catalog(route_records)})
    write_json(output_root / "places.json", {**metadata, **collect_place_catalog(groups, analyzer)})
    write_json(output_root / "network.json", {**metadata, **network})
    write_json(
        output_root / "place_alias_merges.json",
        {
            **metadata,
            "merge_distance_m": place_merge_distance_m,
            "display_name_rule": "longest_name",
            "count": len(network.get("applied_alias_merges", [])),
            "items": network.get("applied_alias_merges", []),
        },
    )
    write_json(
        output_root / "place_alias_candidates.json",
        {
            **metadata,
            "merge_distance_m": place_merge_distance_m,
            "note": "Residual conservative alias candidates not automatically merged.",
            "count": len(network.get("alias_candidates", [])),
            "items": network.get("alias_candidates", []),
        },
    )
    write_json(
        output_root / "variant_endpoint_attachments.json",
        {
            **metadata,
            "note": (
                "Non-primary variant endpoints attached to canonical primary "
                "places by semantic name/alias. Geometry remains unchanged."
            ),
            "count": len(network.get("variant_endpoint_attachments", [])),
            "items": network.get("variant_endpoint_attachments", []),
        },
    )

    groups_dir = output_root / "groups"

    for record in route_records:
        write_json(groups_dir / f"{record['route_group_id']}.json", record)

    return metadata, route_records, network


def geometry_family_clusters(
    entries: list[Any],
    analyzer,
) -> list[list[Any]]:
    """
    Merge only source tracks whose physical geometry is IDENTICAL or SAME.

    NEAR tracks remain separate because they may represent a deliberate minor
    alternative. ALTERNATIVE tracks always remain separate.
    """
    if len(entries) <= 1:
        return [list(entries)]

    parent = list(range(len(entries)))

    def find(index: int) -> int:
        while parent[index] != index:
            parent[index] = parent[parent[index]]
            index = parent[index]
        return index

    def union(a: int, b: int) -> None:
        root_a = find(a)
        root_b = find(b)
        if root_a != root_b:
            parent[root_b] = root_a

    for i in range(len(entries)):
        for j in range(i + 1, len(entries)):
            comparison = analyzer.compare_track_geometry(
                entries[i].track,
                entries[j].track,
            )
            if comparison.classification in {"IDENTICAL", "SAME"}:
                union(i, j)

    clusters: dict[int, list[Any]] = defaultdict(list)
    for index, entry in enumerate(entries):
        clusters[find(index)].append(entry)

    result = list(clusters.values())
    result.sort(
        key=lambda cluster: min(
            (
                entry.track.route_code,
                sort_track_key(entry.track),
            )
            for entry in cluster
        )
    )
    return result


def representative_track_entry(entries: list[Any]) -> Any:
    return min(
        entries,
        key=lambda entry: (
            -entry.track.points,
            entry.track.length_m,
            entry.track.route_code,
            sort_track_key(entry.track),
        ),
    )


def build_primary_orientation_map(
    groups: dict[str, list[Any]],
    analyzer,
) -> dict[str, bool]:
    """Return the best known KML orientation for strict primary ``a`` tracks."""
    result: dict[str, bool] = {}

    for route_code in sorted(groups):
        solution = analyzer.solve_primary_orientation(groups[route_code])
        for track, reversed_track in zip(
            solution.tracks,
            solution.reversed_flags,
        ):
            result[track.path.as_posix()] = reversed_track

    return result


def observation_distance_m(a: dict[str, Any], b: dict[str, Any], analyzer) -> float:
    return analyzer.haversine_m(
        a["lat"],
        a["lon"],
        b["lat"],
        b["lon"],
    )


def cluster_same_name_observations(
    observations: list[dict[str, Any]],
    max_distance_m: float,
    analyzer,
) -> list[list[dict[str, Any]]]:
    """
    Cluster occurrences of one normalized place name spatially.

    Single-link clustering is deliberate here: CNIG endpoints around one town
    may be recorded at different entrances/exits. The generous default radius
    is 5 km, but names are never merged across a larger spatial gap unless a
    chain of same-name endpoint observations connects them.
    """
    if len(observations) <= 1:
        return [list(observations)]

    parent = list(range(len(observations)))

    def find(index: int) -> int:
        while parent[index] != index:
            parent[index] = parent[parent[index]]
            index = parent[index]
        return index

    def union(a: int, b: int) -> None:
        root_a = find(a)
        root_b = find(b)
        if root_a != root_b:
            parent[root_b] = root_a

    for i in range(len(observations)):
        for j in range(i + 1, len(observations)):
            if observation_distance_m(
                observations[i],
                observations[j],
                analyzer,
            ) <= max_distance_m:
                union(i, j)

    grouped: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for index, observation in enumerate(observations):
        grouped[find(index)].append(observation)

    clusters = list(grouped.values())
    clusters.sort(
        key=lambda cluster: (
            sum(item["lat"] for item in cluster) / len(cluster),
            sum(item["lon"] for item in cluster) / len(cluster),
            min(item["track_id"] for item in cluster),
        )
    )
    return clusters


def cluster_centers_by_name(
    observations_by_name: dict[str, list[dict[str, Any]]],
    max_distance_m: float,
    analyzer,
) -> dict[str, list[tuple[float, float]]]:
    centers: dict[str, list[tuple[float, float]]] = {}

    for name_key, observations in observations_by_name.items():
        centers[name_key] = []
        for cluster in cluster_same_name_observations(
            observations,
            max_distance_m,
            analyzer,
        ):
            centers[name_key].append(
                (
                    sum(item["lat"] for item in cluster) / len(cluster),
                    sum(item["lon"] for item in cluster) / len(cluster),
                )
            )

    return centers


def distance_to_nearest_center_m(
    point: tuple[float, float],
    centers: list[tuple[float, float]],
    analyzer,
) -> float | None:
    if not centers:
        return None

    return min(
        analyzer.haversine_m(
            point[0],
            point[1],
            center[0],
            center[1],
        )
        for center in centers
    )


def choose_non_primary_orientation(
    entry,
    centers_by_name: dict[str, list[tuple[float, float]]],
    analyzer,
) -> bool:
    """
    Pick FWD/REV for a non-primary track from already established place
    clusters. Geometry orientation affects spatial identity only; it never
    changes the semantic FROM->TO direction from the filename.
    """
    scored: list[tuple[float, int, bool]] = []

    for reversed_track in (False, True):
        from_point = analyzer.oriented_point(
            entry.track,
            reversed_track,
            "start",
        )
        to_point = analyzer.oriented_point(
            entry.track,
            reversed_track,
            "end",
        )
        distances: list[float] = []

        from_distance = distance_to_nearest_center_m(
            from_point,
            centers_by_name.get(entry.from_key, []),
            analyzer,
        )
        if from_distance is not None:
            distances.append(from_distance)

        to_distance = distance_to_nearest_center_m(
            to_point,
            centers_by_name.get(entry.to_key, []),
            analyzer,
        )
        if to_distance is not None:
            distances.append(to_distance)

        # No known endpoint gives no evidence. Prefer raw FWD deterministically.
        if not distances:
            score = 0.0 if not reversed_track else 1.0
            evidence = 0
        else:
            score = sum(distances)
            evidence = len(distances)

        scored.append((score, -evidence, reversed_track))

    return min(
        scored,
        key=lambda item: (
            item[0],
            item[1],
            1 if item[2] else 0,
        ),
    )[2]


def build_spatial_place_identity(
    groups: dict[str, list[Any]],
    analyzer,
    merge_distance_m: float,
) -> dict[str, Any]:
    """
    Resolve semantic place names to spatial identities, with primary tracks
    defining the canonical places whenever possible.

    Strict ``a`` tracks establish the spatial place clusters. Non-primary
    variants are then attached to an already established primary place when
    their semantic endpoint name matches it, even if the variant geometry ends
    far away. This prevents a short/truncated variant from creating a second
    fake Cifuentes, Vitoria, etc.

    If the variant spelling differs, a conservative normalized-name containment
    match (e.g. ``vitoria`` -> ``vitoriagasteiz``) may attach it to a primary
    place. Variant-only real place names are still allowed when no primary
    place can be identified. Pseudo labels such as ``Variante Norte`` never
    become place nodes.
    """
    orientation_by_path = build_primary_orientation_map(groups, analyzer)
    entries = []

    for route_code in sorted(groups):
        for track in groups[route_code]:
            places = analyzer.parse_track_places(track)
            if places is None:
                continue
            if is_pseudo_place(places.from_key) or is_pseudo_place(places.to_key):
                continue
            entries.append(places)

    primary_observations: dict[str, list[dict[str, Any]]] = defaultdict(list)
    deferred_entries = []

    def make_observation(entry, role: str, reversed_track: bool) -> dict[str, Any]:
        if role == "from":
            name_key = entry.from_key
            raw_name = entry.from_raw
            point = analyzer.oriented_point(entry.track, reversed_track, "start")
        else:
            name_key = entry.to_key
            raw_name = entry.to_raw
            point = analyzer.oriented_point(entry.track, reversed_track, "end")

        return {
            "occurrence_id": f"{portable_track_path(entry.track.path)}::{role}",
            "track_id": f"{entry.track.route_code}:{entry.track.section_id}",
            "route_group_id": entry.track.route_code,
            "role": role,
            "name_key": name_key,
            "raw_name": raw_name,
            "display_name": smart_title_words(
                analyzer.display_place_name(raw_name)
            ),
            "lat": point[0],
            "lon": point[1],
            "primary": analyzer.primary_section_number(entry.track.section_id) is not None,
            "reversed_geometry": reversed_track,
        }

    for entry in entries:
        path_key = entry.track.path.as_posix()
        if analyzer.primary_section_number(entry.track.section_id) is not None:
            reversed_track = orientation_by_path.get(path_key, False)
            primary_observations[entry.from_key].append(
                make_observation(entry, "from", reversed_track)
            )
            primary_observations[entry.to_key].append(
                make_observation(entry, "to", reversed_track)
            )
        else:
            deferred_entries.append(entry)

    occurrence_to_place: dict[str, str] = {}
    place_records: dict[str, dict[str, Any]] = {}
    split_names: list[dict[str, Any]] = []
    primary_places_by_name: dict[str, list[str]] = defaultdict(list)

    # First, strict primary tracks define canonical spatial identities.
    for name_key in sorted(primary_observations):
        clusters = cluster_same_name_observations(
            primary_observations[name_key],
            merge_distance_m,
            analyzer,
        )

        if len(clusters) > 1:
            split_names.append(
                {
                    "name_key": name_key,
                    "display_names": sorted(
                        {
                            item["display_name"]
                            for cluster in clusters
                            for item in cluster
                        }
                    ),
                    "cluster_count": len(clusters),
                    "identity_source": "primary_tracks",
                }
            )

        for index, cluster in enumerate(clusters, 1):
            place_key = (
                name_key
                if len(clusters) == 1
                else f"{name_key}@{index}"
            )
            lat = sum(item["lat"] for item in cluster) / len(cluster)
            lon = sum(item["lon"] for item in cluster) / len(cluster)
            aliases = sorted({item["display_name"] for item in cluster})
            route_group_ids = sorted({item["route_group_id"] for item in cluster})

            place_records[place_key] = {
                "place_key": place_key,
                "name_key": name_key,
                "display_name": aliases[0] if aliases else smart_title_words(name_key),
                "aliases": aliases,
                "position": {
                    "lat": round(lat, 7),
                    "lon": round(lon, 7),
                    "source": "mean_of_primary_semantic_track_endpoints",
                },
                "observation_count": len(cluster),
                "primary_observation_count": len(cluster),
                "attached_variant_endpoint_count": 0,
                "route_group_ids": route_group_ids,
                "spatial_cluster_index": index,
                "spatial_cluster_count_for_name": len(clusters),
                "identity_source": "primary_tracks",
            }
            primary_places_by_name[name_key].append(place_key)

            for observation in cluster:
                occurrence_to_place[observation["occurrence_id"]] = place_key

    primary_centers: dict[str, list[tuple[float, float]]] = {
        name_key: [
            (
                place_records[place_key]["position"]["lat"],
                place_records[place_key]["position"]["lon"],
            )
            for place_key in place_keys
        ]
        for name_key, place_keys in primary_places_by_name.items()
    }

    def nearest_place_key(
        observation: dict[str, Any],
        candidate_keys: list[str],
    ) -> tuple[str, float]:
        ranked = []
        for place_key in candidate_keys:
            record = place_records[place_key]
            distance = analyzer.haversine_m(
                observation["lat"],
                observation["lon"],
                record["position"]["lat"],
                record["position"]["lon"],
            )
            ranked.append((distance, place_key))
        ranked.sort(key=lambda item: (item[0], item[1]))
        return ranked[0][1], ranked[0][0]

    def primary_alias_candidates(observation: dict[str, Any]) -> list[str]:
        key = observation["name_key"]
        if len(key) < 5:
            return []

        candidates = []
        for place_key, record in place_records.items():
            other = record["name_key"]
            if len(other) < 5:
                continue
            if key in other or other in key:
                candidates.append(place_key)

        # Prefer places already used by the same CNIG route group when that
        # narrows the candidate set. Geometry is only a tie-breaker after name
        # semantics because variant endpoints can be physically truncated.
        same_route = [
            place_key
            for place_key in candidates
            if observation["route_group_id"]
            in place_records[place_key].get("route_group_ids", [])
        ]
        return same_route or candidates

    variant_endpoint_attachments: list[dict[str, Any]] = []
    variant_only_observations: dict[str, list[dict[str, Any]]] = defaultdict(list)
    non_primary_orientation: dict[str, bool] = {}

    for entry in deferred_entries:
        reversed_track = choose_non_primary_orientation(
            entry,
            primary_centers,
            analyzer,
        )
        non_primary_orientation[entry.track.path.as_posix()] = reversed_track

        for role in ("from", "to"):
            observation = make_observation(entry, role, reversed_track)
            exact_candidates = primary_places_by_name.get(
                observation["name_key"],
                [],
            )

            reason = None
            candidates: list[str] = []
            if exact_candidates:
                candidates = list(exact_candidates)
                reason = "variant_endpoint_exact_name_primary_place"
            else:
                candidates = primary_alias_candidates(observation)
                if candidates:
                    reason = "variant_endpoint_name_alias_primary_place"

            if not candidates:
                variant_only_observations[observation["name_key"]].append(
                    observation
                )
                continue

            place_key, distance = nearest_place_key(observation, candidates)
            occurrence_to_place[observation["occurrence_id"]] = place_key
            record = place_records[place_key]
            record["attached_variant_endpoint_count"] = (
                int(record.get("attached_variant_endpoint_count", 0)) + 1
            )
            record["route_group_ids"] = sorted(
                set(record.get("route_group_ids", []))
                | {observation["route_group_id"]}
            )
            record["aliases"] = sorted(
                set(record.get("aliases", []))
                | {observation["display_name"]},
                key=lambda value: (value.lower(), value),
            )

            variant_endpoint_attachments.append(
                {
                    "occurrence_id": observation["occurrence_id"],
                    "track_id": observation["track_id"],
                    "route_group_id": observation["route_group_id"],
                    "role": observation["role"],
                    "semantic_name": observation["display_name"],
                    "semantic_name_key": observation["name_key"],
                    "attached_place_key": place_key,
                    "attached_place_name": record["display_name"],
                    "distance_from_variant_geometry_endpoint_m": round(distance, 1),
                    "outside_normal_merge_radius": distance > merge_distance_m,
                    "reason": reason,
                }
            )

    # A real place that exists only in a non-primary route is still preserved.
    # It just cannot override or duplicate an already established primary place.
    for name_key in sorted(variant_only_observations):
        clusters = cluster_same_name_observations(
            variant_only_observations[name_key],
            merge_distance_m,
            analyzer,
        )

        if len(clusters) > 1:
            split_names.append(
                {
                    "name_key": name_key,
                    "display_names": sorted(
                        {
                            item["display_name"]
                            for cluster in clusters
                            for item in cluster
                        }
                    ),
                    "cluster_count": len(clusters),
                    "identity_source": "variant_only_tracks",
                }
            )

        for index, cluster in enumerate(clusters, 1):
            place_key = (
                name_key
                if len(clusters) == 1
                else f"{name_key}@{index}"
            )
            if place_key in place_records:
                fail(
                    "Internal place-key collision while creating variant-only "
                    f"identity {place_key!r}."
                )

            lat = sum(item["lat"] for item in cluster) / len(cluster)
            lon = sum(item["lon"] for item in cluster) / len(cluster)
            aliases = sorted({item["display_name"] for item in cluster})
            route_group_ids = sorted({item["route_group_id"] for item in cluster})
            place_records[place_key] = {
                "place_key": place_key,
                "name_key": name_key,
                "display_name": aliases[0] if aliases else smart_title_words(name_key),
                "aliases": aliases,
                "position": {
                    "lat": round(lat, 7),
                    "lon": round(lon, 7),
                    "source": "mean_of_variant_only_semantic_track_endpoints",
                },
                "observation_count": len(cluster),
                "primary_observation_count": 0,
                "attached_variant_endpoint_count": len(cluster),
                "route_group_ids": route_group_ids,
                "spatial_cluster_index": index,
                "spatial_cluster_count_for_name": len(clusters),
                "identity_source": "variant_only_tracks",
            }

            for observation in cluster:
                occurrence_to_place[observation["occurrence_id"]] = place_key

    return {
        "occurrence_to_place": occurrence_to_place,
        "places": place_records,
        "split_names": sorted(
            split_names,
            key=lambda item: (item["name_key"], item["identity_source"]),
        ),
        "primary_orientation_by_path": orientation_by_path,
        "non_primary_orientation_by_path": non_primary_orientation,
        "variant_endpoint_attachments": sorted(
            variant_endpoint_attachments,
            key=lambda item: (
                item["track_id"],
                item["role"],
            ),
        ),
    }

def build_alias_candidates(
    place_records: dict[str, dict[str, Any]],
    max_distance_m: float,
    analyzer,
) -> list[dict[str, Any]]:
    """
    Find conservative different-name aliases inside the spatial radius.

    To avoid merging merely nearby towns, candidates require one normalized
    name to contain the other (e.g. ``vitoria`` and ``vitoriagasteiz``) in
    addition to spatial proximity. Patch 014 applies these candidates
    automatically after a complete-link distance safety check.
    """
    places = list(place_records.values())
    candidates: list[dict[str, Any]] = []

    for i in range(len(places)):
        for j in range(i + 1, len(places)):
            a = places[i]
            b = places[j]
            if a["name_key"] == b["name_key"]:
                continue

            a_key = a["name_key"]
            b_key = b["name_key"]
            if a_key not in b_key and b_key not in a_key:
                continue

            distance = analyzer.haversine_m(
                a["position"]["lat"],
                a["position"]["lon"],
                b["position"]["lat"],
                b["position"]["lon"],
            )
            if distance > max_distance_m:
                continue

            shared_routes = sorted(
                set(a["route_group_ids"])
                & set(b["route_group_ids"])
            )
            candidates.append(
                {
                    "place_a": a["place_key"],
                    "name_a": a["display_name"],
                    "place_b": b["place_key"],
                    "name_b": b["display_name"],
                    "distance_m": round(distance, 1),
                    "shared_route_group_ids": shared_routes,
                    "reason": "nearby_name_containment",
                    "automatic_merge": False,
                }
            )

    candidates.sort(
        key=lambda item: (
            item["distance_m"],
            item["name_a"].lower(),
            item["name_b"].lower(),
        )
    )
    return candidates


def merge_alias_candidates(
    place_records: dict[str, dict[str, Any]],
    occurrence_to_place: dict[str, str],
    max_distance_m: float,
    analyzer,
) -> dict[str, Any]:
    """
    Merge conservative nearby different-name aliases automatically.

    Candidate discovery still requires normalized-name containment. Merging is
    deliberately complete-link: every source place in a merged cluster must be
    within ``max_distance_m`` of every other source place. This prevents a
    transitive A-B-C chain from joining places whose endpoints are more than the
    configured radius apart.

    The longest human-readable name becomes the canonical display name. All
    shorter names remain in ``aliases`` for lookup and provenance.
    """
    candidates = build_alias_candidates(
        place_records,
        max_distance_m,
        analyzer,
    )

    parent = {key: key for key in place_records}
    members = {key: {key} for key in place_records}

    def find(key: str) -> str:
        while parent[key] != key:
            parent[key] = parent[parent[key]]
            key = parent[key]
        return key

    def distance_between_keys(a_key: str, b_key: str) -> float:
        a = place_records[a_key]
        b = place_records[b_key]
        return analyzer.haversine_m(
            a["position"]["lat"],
            a["position"]["lon"],
            b["position"]["lat"],
            b["position"]["lon"],
        )

    def combined_cluster_fits(a_root: str, b_root: str) -> bool:
        for a_key in members[a_root]:
            for b_key in members[b_root]:
                if distance_between_keys(a_key, b_key) > max_distance_m:
                    return False
        return True

    # Nearest aliases first makes the result deterministic and conservative.
    for candidate in candidates:
        a_key = candidate["place_a"]
        b_key = candidate["place_b"]
        a_root = find(a_key)
        b_root = find(b_key)

        if a_root == b_root:
            continue

        if not combined_cluster_fits(a_root, b_root):
            continue

        # Deterministic root selection. Canonical display-name selection happens
        # later and is independent from the union-find root.
        keep_root, drop_root = sorted((a_root, b_root))
        parent[drop_root] = keep_root
        members[keep_root].update(members[drop_root])
        del members[drop_root]

    clusters: dict[str, list[str]] = defaultdict(list)
    for key in sorted(place_records):
        clusters[find(key)].append(key)

    old_to_canonical: dict[str, str] = {}
    merged_records: dict[str, dict[str, Any]] = {}
    applied_merges: list[dict[str, Any]] = []

    for cluster_keys in sorted(clusters.values(), key=lambda value: tuple(value)):
        source_records = [place_records[key] for key in cluster_keys]

        all_aliases = sorted(
            {
                alias
                for record in source_records
                for alias in (
                    list(record.get("aliases", []))
                    + [record.get("display_name", "")]
                )
                if alias
            },
            key=lambda value: (value.lower(), value),
        )

        # User-facing rule: choose the longer place name. Deterministic lexical
        # tie-break keeps generated JSON stable.
        display_name = max(
            all_aliases,
            key=lambda value: (
                len(value.strip()),
                len(analyzer.normalize_place_name(value)),
                value.lower(),
            ),
        )
        preferred_record = max(
            source_records,
            key=lambda record: (
                len(record.get("display_name", "").strip()),
                len(record.get("name_key", "")),
                record.get("display_name", "").lower(),
                record["place_key"],
            ),
        )
        canonical_key = preferred_record["place_key"]

        total_weight = sum(
            max(1, int(record.get("observation_count", 1)))
            for record in source_records
        )
        lat = sum(
            record["position"]["lat"]
            * max(1, int(record.get("observation_count", 1)))
            for record in source_records
        ) / total_weight
        lon = sum(
            record["position"]["lon"]
            * max(1, int(record.get("observation_count", 1)))
            for record in source_records
        ) / total_weight

        route_group_ids = sorted(
            {
                route_code
                for record in source_records
                for route_code in record.get("route_group_ids", [])
            }
        )
        name_keys = sorted(
            {
                record.get("name_key")
                for record in source_records
                if record.get("name_key")
            }
        )
        source_place_keys = sorted(record["place_key"] for record in source_records)

        merged = {
            **preferred_record,
            "place_key": canonical_key,
            "name_key": analyzer.normalize_place_name(display_name),
            "name_keys": name_keys,
            "display_name": display_name,
            "aliases": all_aliases,
            "position": {
                "lat": round(lat, 7),
                "lon": round(lon, 7),
                "source": "weighted_mean_of_merged_place_endpoints",
            },
            "observation_count": total_weight,
            "route_group_ids": route_group_ids,
            "merged_place_keys": source_place_keys,
            "automatic_alias_merge": len(source_records) > 1,
        }
        merged_records[canonical_key] = merged

        for old_key in source_place_keys:
            old_to_canonical[old_key] = canonical_key

        if len(source_records) > 1:
            max_pair_distance = 0.0
            for i in range(len(source_place_keys)):
                for j in range(i + 1, len(source_place_keys)):
                    max_pair_distance = max(
                        max_pair_distance,
                        distance_between_keys(
                            source_place_keys[i],
                            source_place_keys[j],
                        ),
                    )

            applied_merges.append(
                {
                    "canonical_place_key": canonical_key,
                    "display_name": display_name,
                    "aliases": all_aliases,
                    "source_place_keys": source_place_keys,
                    "source_name_keys": name_keys,
                    "max_source_separation_m": round(max_pair_distance, 1),
                    "merge_radius_m": max_distance_m,
                    "reason": "nearby_name_containment",
                    "display_name_rule": "longest_name",
                    "automatic_merge": True,
                }
            )

    remapped_occurrences = {
        occurrence_id: old_to_canonical[place_key]
        for occurrence_id, place_key in occurrence_to_place.items()
    }

    # Anything still discoverable after merging is intentionally left for
    # audit. In normal data this should usually be zero.
    remaining_candidates = build_alias_candidates(
        merged_records,
        max_distance_m,
        analyzer,
    )

    return {
        "places": merged_records,
        "occurrence_to_place": remapped_occurrences,
        "old_to_canonical": old_to_canonical,
        "applied_merges": sorted(
            applied_merges,
            key=lambda item: (
                item["display_name"].lower(),
                item["canonical_place_key"],
            ),
        ),
        "remaining_candidates": remaining_candidates,
    }


def geometry_family_clusters(
    entries: list[Any],
    analyzer,
) -> list[list[Any]]:
    """
    Merge only source tracks whose physical geometry is IDENTICAL or SAME.

    NEAR tracks remain separate because they may represent a deliberate minor
    alternative. ALTERNATIVE tracks always remain separate.
    """
    if len(entries) <= 1:
        return [list(entries)]

    parent = list(range(len(entries)))

    def find(index: int) -> int:
        while parent[index] != index:
            parent[index] = parent[parent[index]]
            index = parent[index]
        return index

    def union(a: int, b: int) -> None:
        root_a = find(a)
        root_b = find(b)
        if root_a != root_b:
            parent[root_b] = root_a

    for i in range(len(entries)):
        for j in range(i + 1, len(entries)):
            comparison = analyzer.compare_track_geometry(
                entries[i].track,
                entries[j].track,
            )
            if comparison.classification in {"IDENTICAL", "SAME"}:
                union(i, j)

    clusters: dict[int, list[Any]] = defaultdict(list)
    for index, entry in enumerate(entries):
        clusters[find(index)].append(entry)

    result = list(clusters.values())
    result.sort(
        key=lambda cluster: min(
            (
                entry.track.route_code,
                sort_track_key(entry.track),
            )
            for entry in cluster
        )
    )
    return result


def representative_track_entry(entries: list[Any]) -> Any:
    return min(
        entries,
        key=lambda entry: (
            -entry.track.points,
            entry.track.length_m,
            entry.track.route_code,
            sort_track_key(entry.track),
        ),
    )


def build_global_network(
    groups: dict[str, list[Any]],
    official_names: dict[str, str],
    analyzer,
    place_merge_distance_m: float = 5000.0,
) -> dict[str, Any]:
    """
    Build the global human-facing Camino place network with spatial identity.

    Equal normalized names are merged only inside ``place_merge_distance_m``.
    The default is deliberately generous at 5 km. Conservative different-name
    aliases (normalized-name containment plus the same spatial radius) are also
    merged automatically; the longest human-readable name wins.
    """
    spatial = build_spatial_place_identity(
        groups,
        analyzer,
        place_merge_distance_m,
    )
    alias_merge = merge_alias_candidates(
        spatial["places"],
        spatial["occurrence_to_place"],
        place_merge_distance_m,
        analyzer,
    )
    place_records = alias_merge["places"]
    occurrence_to_place = alias_merge["occurrence_to_place"]

    detached_variant_tracks: list[dict[str, Any]] = []
    collapsed_alias_connector_tracks: list[dict[str, Any]] = []
    eligible_pairs: dict[tuple[str, str], list[Any]] = defaultdict(list)

    for route_code in sorted(groups):
        for track in groups[route_code]:
            entry = analyzer.parse_track_places(track)
            if entry is None:
                continue

            if is_pseudo_place(entry.from_key) or is_pseudo_place(entry.to_key):
                detached_variant_tracks.append(
                    {
                        "track_id": f"{track.route_code}:{track.section_id}",
                        "route_group_id": track.route_code,
                        "route_name": friendly_route_name(
                            official_names.get(track.route_code),
                            track.route_code,
                        ),
                        "section_id": track.section_id,
                        "raw_label": track.label,
                        "parsed_from_key": entry.from_key,
                        "parsed_to_key": entry.to_key,
                        "length_m": round(track.length_m, 3),
                        "reason": "pseudo_place_endpoint",
                    }
                )
                continue

            from_occurrence = f"{portable_track_path(track.path)}::from"
            to_occurrence = f"{portable_track_path(track.path)}::to"
            from_key = occurrence_to_place.get(from_occurrence)
            to_key = occurrence_to_place.get(to_occurrence)

            if from_key is None or to_key is None:
                fail(
                    "Spatial place identity missing for "
                    f"{track.route_code}:{track.section_id}."
                )

            if from_key == to_key:
                collapsed_alias_connector_tracks.append(
                    {
                        "track_id": f"{track.route_code}:{track.section_id}",
                        "route_group_id": track.route_code,
                        "section_id": track.section_id,
                        "raw_label": track.label,
                        "place_key": from_key,
                        "display_name": place_records[from_key]["display_name"],
                        "length_m": round(track.length_m, 3),
                        "reason": "endpoints_collapsed_by_place_alias_merge",
                    }
                )
                continue

            eligible_pairs[(from_key, to_key)].append(entry)

    edges: list[dict[str, Any]] = []
    outgoing_edge_ids: dict[str, list[str]] = defaultdict(list)
    incoming_edge_ids: dict[str, list[str]] = defaultdict(list)

    for (from_key, to_key), entries in sorted(eligible_pairs.items()):
        clusters = geometry_family_clusters(entries, analyzer)
        pair_id = f"{from_key}>{to_key}"
        family_count = len(clusters)

        for family_index, cluster in enumerate(clusters, 1):
            representative = representative_track_entry(cluster)
            source_track_ids = sorted(
                f"{entry.track.route_code}:{entry.track.section_id}"
                for entry in cluster
            )
            route_group_ids = sorted(
                {entry.track.route_code for entry in cluster}
            )
            route_names = sorted(
                {
                    friendly_route_name(
                        official_names.get(route_code),
                        route_code,
                    )
                    for route_code in route_group_ids
                }
            )
            variant_track_ids = sorted(
                f"{entry.track.route_code}:{entry.track.section_id}"
                for entry in cluster
                if entry.track.section_id
                and not entry.track.section_id.lower().endswith("a")
            )
            edge_id = f"{pair_id}#{family_index}"

            if family_count > 1:
                family_kind = "ALTERNATIVE"
            elif len(cluster) > 1:
                family_kind = "SHARED"
            else:
                family_kind = "SINGLE"

            edge = {
                "edge_id": edge_id,
                "logical_pair_id": pair_id,
                "from_key": from_key,
                "from_name_key": place_records[from_key]["name_key"],
                "from": place_records[from_key]["display_name"],
                "to_key": to_key,
                "to_name_key": place_records[to_key]["name_key"],
                "to": place_records[to_key]["display_name"],
                "family_index": family_index,
                "parallel_family_count": family_count,
                "family_kind": family_kind,
                "source_track_ids": source_track_ids,
                "source_track_count": len(source_track_ids),
                "variant_source_track_ids": variant_track_ids,
                "route_group_ids": route_group_ids,
                "route_names": route_names,
                "representative_track_id": (
                    f"{representative.track.route_code}:"
                    f"{representative.track.section_id}"
                ),
                "length_m": round(representative.track.length_m, 3),
                "length_m_min": round(
                    min(entry.track.length_m for entry in cluster),
                    3,
                ),
                "length_m_max": round(
                    max(entry.track.length_m for entry in cluster),
                    3,
                ),
            }
            edges.append(edge)
            outgoing_edge_ids[from_key].append(edge_id)
            incoming_edge_ids[to_key].append(edge_id)

    graph_places = set(outgoing_edge_ids) | set(incoming_edge_ids)

    undirected: dict[str, set[str]] = defaultdict(set)
    for edge in edges:
        undirected[edge["from_key"]].add(edge["to_key"])
        undirected[edge["to_key"]].add(edge["from_key"])

    component_id_by_place: dict[str, int] = {}
    components: list[list[str]] = []
    visited: set[str] = set()

    for start_place in sorted(graph_places):
        if start_place in visited:
            continue
        queue = deque([start_place])
        visited.add(start_place)
        component: list[str] = []

        while queue:
            current = queue.popleft()
            component.append(current)
            for neighbour in sorted(undirected.get(current, set())):
                if neighbour in visited:
                    continue
                visited.add(neighbour)
                queue.append(neighbour)

        component.sort()
        components.append(component)

    components.sort(key=lambda item: (-len(item), item[0] if item else ""))
    for index, component in enumerate(components, 1):
        for key in component:
            component_id_by_place[key] = index

    nodes: list[dict[str, Any]] = []
    for key in sorted(graph_places):
        base = place_records[key]
        incoming = sorted(incoming_edge_ids.get(key, []))
        outgoing = sorted(outgoing_edge_ids.get(key, []))
        nodes.append(
            {
                **base,
                "route_names": sorted(
                    {
                        friendly_route_name(
                            official_names.get(route_code),
                            route_code,
                        )
                        for route_code in base["route_group_ids"]
                    }
                ),
                "incoming_edge_ids": incoming,
                "outgoing_edge_ids": outgoing,
                "incoming_count": len(incoming),
                "outgoing_count": len(outgoing),
                "is_merge": len(incoming) >= 2,
                "is_branch": len(outgoing) >= 2,
                "component_id": component_id_by_place.get(key),
            }
        )

    route_groups = []
    for route_code in sorted(groups):
        graph = analyzer.build_place_graph(groups[route_code])
        topology = analyzer.build_semantic_topology(groups[route_code])
        components_summary = []
        edge_counts = component_edge_counts(topology, graph)

        for index, component in enumerate(topology.components, 1):
            summary = component_summary(
                route_code,
                index,
                component,
                topology,
                graph,
                analyzer,
            )
            summary["track_count"] = edge_counts[index]
            components_summary.append(summary)

        friendly = friendly_route_name(
            official_names.get(route_code),
            route_code,
        )
        if components_summary:
            major = max(
                components_summary,
                key=lambda component: (
                    component["track_count"],
                    component["place_count"],
                ),
            )
            display = f"{friendly} — {major['display_span']}"
            if len(components_summary) > 1:
                display += f" (+{len(components_summary) - 1} components)"
        else:
            display = friendly

        route_groups.append(
            {
                "route_group_id": route_code,
                "source_id": route_code,
                "official_name": official_names.get(route_code),
                "name": friendly,
                "display_name": display,
                "topology_class": topology.classification,
            }
        )

    alternative_pairs = sum(
        1
        for entries in eligible_pairs.values()
        if len(geometry_family_clusters(entries, analyzer)) > 1
    )
    merged_source_tracks = sum(
        max(0, edge["source_track_count"] - 1)
        for edge in edges
    )
    alias_candidates = [
        candidate
        for candidate in alias_merge["remaining_candidates"]
        if (
            candidate["place_a"] in graph_places
            or candidate["place_b"] in graph_places
        )
    ]

    return {
        "schema_version": 4,
        "spatial_identity": {
            "same_name_merge_distance_m": place_merge_distance_m,
            "minimum_supported_merge_distance_m": 1000.0,
            "position_source": "primary_track_endpoints_with_variant_fallback",
            "variant_endpoint_policy": (
                "non-primary endpoints attach to canonical primary places by "
                "exact semantic name or conservative name containment before "
                "variant-only places are created"
            ),
            "different_names_auto_merged": True,
            "different_name_merge_rule": "nearby_name_containment",
            "different_name_display_rule": "longest_name",
            "alias_merge_distance_m": place_merge_distance_m,
            "split_normalized_names": spatial["split_names"],
        },
        "stats": {
            "nodes": len(nodes),
            "edge_families": len(edges),
            "logical_pairs": len(eligible_pairs),
            "alternative_pairs": alternative_pairs,
            "merged_duplicate_source_tracks": merged_source_tracks,
            "detached_variant_tracks": len(detached_variant_tracks),
            "weak_components": len(components),
            "spatially_split_normalized_names": len(spatial["split_names"]),
            "automatic_alias_merges": len(alias_merge["applied_merges"]),
            "alias_candidates": len(alias_candidates),
            "collapsed_alias_connector_tracks": len(collapsed_alias_connector_tracks),
            "variant_endpoints_attached_to_primary_places": len(
                spatial["variant_endpoint_attachments"]
            ),
            "variant_endpoints_forced_outside_radius": sum(
                1
                for item in spatial["variant_endpoint_attachments"]
                if item["outside_normal_merge_radius"]
            ),
        },
        "route_groups": route_groups,
        "nodes": nodes,
        "edges": edges,
        "applied_alias_merges": alias_merge["applied_merges"],
        "alias_candidates": alias_candidates,
        "variant_endpoint_attachments": spatial["variant_endpoint_attachments"],
        "collapsed_alias_connector_tracks": sorted(
            collapsed_alias_connector_tracks,
            key=lambda item: item["track_id"],
        ),
        "detached_variant_tracks": sorted(
            detached_variant_tracks,
            key=lambda item: item["track_id"],
        ),
        "components": [
            {
                "component_id": index,
                "place_count": len(component),
                "place_keys": component,
            }
            for index, component in enumerate(components, 1)
        ],
    }


def resolve_network_root(
    network: dict[str, Any],
    query: str,
    analyzer,
) -> str:
    normalized = analyzer.normalize_place_name(query)
    if normalized == "santiago":
        normalized = "santiagodecompostela"

    node_by_key = {
        node["place_key"]: node
        for node in network["nodes"]
    }

    if normalized in node_by_key:
        return normalized

    name_matches = sorted(
        node["place_key"]
        for node in network["nodes"]
        if node.get("name_key") == normalized
    )
    if len(name_matches) == 1:
        return name_matches[0]

    alias_matches = []
    for node in network["nodes"]:
        aliases = {
            analyzer.normalize_place_name(alias)
            for alias in node.get("aliases", [])
        }
        if normalized in aliases:
            alias_matches.append(node["place_key"])

    alias_matches = sorted(set(alias_matches))
    if len(alias_matches) == 1:
        return alias_matches[0]

    fuzzy = sorted(
        node["place_key"]
        for node in network["nodes"]
        if (
            normalized in node.get("name_key", "")
            or node.get("name_key", "") in normalized
        )
    )
    if len(fuzzy) == 1:
        return fuzzy[0]

    candidates = name_matches or alias_matches or fuzzy
    if candidates:
        readable = ", ".join(
            (
                f"{node_by_key[key]['display_name']} "
                f"[{node_by_key[key]['position']['lat']:.4f}, "
                f"{node_by_key[key]['position']['lon']:.4f}]"
            )
            for key in candidates[:12]
        )
        fail(
            f"Network tree root {query!r} is ambiguous after spatial place "
            f"identity. Candidates: {readable}"
        )

    fail(f"Network tree root {query!r} was not found.")


def network_edge_caption(edges: list[dict[str, Any]]) -> str:
    route_names = sorted(
        {
            name
            for edge in edges
            for name in edge.get("route_names", [])
        }
    )
    min_length = min(edge["length_m_min"] for edge in edges)
    max_length = max(edge["length_m_max"] for edge in edges)

    if abs(max_length - min_length) < 0.5:
        distance = f"{min_length / 1000.0:.1f} km"
    else:
        distance = (
            f"{min_length / 1000.0:.1f}-"
            f"{max_length / 1000.0:.1f} km"
        )

    route_text = " / ".join(route_names) if route_names else "official CNIG route"
    alternative = (
        f"; {len(edges)} alternatives"
        if len(edges) > 1
        else ""
    )
    return f"{distance}; {route_text}{alternative}"


def render_network_tree(
    network: dict[str, Any],
    root_key: str,
    reverse: bool = True,
) -> str:
    nodes = {
        node["place_key"]: node
        for node in network["nodes"]
    }
    edges = {
        edge["edge_id"]: edge
        for edge in network["edges"]
    }

    if root_key not in nodes:
        fail(f"Resolved network root {root_key!r} does not exist.")

    lines = [nodes[root_key]["display_name"]]
    expanded: set[str] = {root_key}

    def child_groups(node_key: str) -> list[tuple[str, list[dict[str, Any]]]]:
        edge_ids = (
            nodes[node_key]["incoming_edge_ids"]
            if reverse
            else nodes[node_key]["outgoing_edge_ids"]
        )
        grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)

        for edge_id in edge_ids:
            edge = edges[edge_id]
            child = edge["from_key"] if reverse else edge["to_key"]
            grouped[child].append(edge)

        result = list(grouped.items())
        result.sort(
            key=lambda item: (
                nodes[item[0]]["display_name"].lower(),
                min(edge["length_m_min"] for edge in item[1]),
            )
        )
        return result

    def walk(
        node_key: str,
        prefix: str,
        active_path: set[str],
    ) -> None:
        children = child_groups(node_key)

        for index, (child, child_edges) in enumerate(children):
            last = index == len(children) - 1
            branch = "└─ " if last else "├─ "
            next_prefix = prefix + ("   " if last else "│  ")
            caption = network_edge_caption(child_edges)
            label = nodes[child]["display_name"]

            if child in active_path:
                lines.append(
                    f"{prefix}{branch}{label}  "
                    f"({caption}; cycle)"
                )
                continue

            if child in expanded:
                lines.append(
                    f"{prefix}{branch}{label}  "
                    f"({caption}; shared node — shown above)"
                )
                continue

            lines.append(
                f"{prefix}{branch}{label}  ({caption})"
            )
            expanded.add(child)
            walk(
                child,
                next_prefix,
                active_path | {child},
            )

    walk(root_key, "", {root_key})
    return "\n".join(lines) + "\n"


def build_tree_graph(selected_groups: dict[str, list[Any]], analyzer) -> TreeGraph:
    tracks = [track for route_tracks in selected_groups.values() for track in route_tracks]
    pair_groups = analyzer.semantic_pair_groups(tracks)
    place_graph = analyzer.build_place_graph(tracks)

    outgoing: dict[str, list[LogicalEdge]] = defaultdict(list)
    incoming: dict[str, list[LogicalEdge]] = defaultdict(list)

    for (from_key, to_key), entries in sorted(pair_groups.items(), key=lambda item: item[0]):
        entries = sorted(entries, key=lambda entry: (entry.track.route_code, sort_track_key(entry.track)))
        edge = LogicalEdge(
            from_key=from_key,
            to_key=to_key,
            from_display=smart_title_words(place_graph.display_names.get(from_key, from_key)),
            to_display=smart_title_words(place_graph.display_names.get(to_key, to_key)),
            track_ids=tuple(f"{entry.track.route_code}:{entry.track.section_id}" for entry in entries),
            route_codes=tuple(entry.track.route_code for entry in entries),
            section_ids=tuple(entry.track.section_id for entry in entries),
            length_m_min=min(entry.track.length_m for entry in entries),
            length_m_max=max(entry.track.length_m for entry in entries),
            track_count=len(entries),
            contains_variant=any(entry.track.section_id[-1].lower() != 'a' for entry in entries if entry.track.section_id),
        )
        outgoing[from_key].append(edge)
        incoming[to_key].append(edge)

    for edges in outgoing.values():
        edges.sort(key=lambda edge: (edge.to_display.lower(), edge.length_m_min, edge.track_ids))
    for edges in incoming.values():
        edges.sort(key=lambda edge: (edge.from_display.lower(), edge.length_m_min, edge.track_ids))

    return TreeGraph(outgoing=dict(outgoing), incoming=dict(incoming), display_names={k: smart_title_words(v) for k, v in place_graph.display_names.items()})


def edge_caption(edge: LogicalEdge) -> str:
    ids = ", ".join(edge.track_ids)
    if edge.length_m_min == edge.length_m_max:
        distance = f"{edge.length_m_min / 1000.0:.1f} km"
    else:
        distance = f"{edge.length_m_min / 1000.0:.1f}-{edge.length_m_max / 1000.0:.1f} km"
    alt = " [parallel]" if edge.track_count > 1 else ""
    return f"{distance}; {ids}{alt}"


def render_ascii_tree(graph: TreeGraph, root_key: str, reverse: bool = False) -> str:
    if root_key not in graph.display_names:
        known = ", ".join(sorted(graph.display_names)[:20])
        fail(f"Tree root {root_key!r} not found. Example keys: {known}")

    lines = [graph.display_names[root_key]]
    adjacency = graph.incoming if reverse else graph.outgoing

    def children_of(node: str) -> list[tuple[str, LogicalEdge]]:
        edges = adjacency.get(node, [])
        result = []

        for edge in edges:
            child = edge.from_key if reverse else edge.to_key
            result.append((child, edge))

        result.sort(key=lambda item: (graph.display_names.get(item[0], item[0]).lower(), item[1].length_m_min))
        return result

    def walk(node: str, prefix: str, active_path: set[str]) -> None:
        children = children_of(node)

        for index, (child, edge) in enumerate(children):
            last = index == len(children) - 1
            branch = "└─ " if last else "├─ "
            next_prefix = prefix + ("   " if last else "│  ")
            label = graph.display_names.get(child, child)
            caption = edge_caption(edge)

            if child in active_path:
                lines.append(f"{prefix}{branch}{label}  ({caption}; cycle)")
                continue

            lines.append(f"{prefix}{branch}{label}  ({caption})")
            walk(child, next_prefix, active_path | {child})

    walk(root_key, "", {root_key})
    return "\n".join(lines) + "\n"


def parse_group_list(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


def summarize_build(metadata: dict[str, Any], route_records: list[dict[str, Any]], output_root: Path) -> None:
    topology_counts: dict[str, int] = defaultdict(int)

    for record in route_records:
        topology_counts[record["topology_class"]] += 1

    print()
    print("Processed CNIG export")
    print("=====================")
    print(f"Output root:      {output_root}")
    print(f"Route groups:     {metadata['route_groups']}")
    print(f"Raw tracks:       {metadata['raw_tracks']}")
    print("Topology classes:")
    for key in ("LINEAR", "NETWORK", "DISCONNECTED", "OTHER", "EMPTY"):
        print(f"  {key:12} {topology_counts.get(key, 0):3}")
    print()
    print("Created:")
    print(f"  {output_root / 'metadata.json'}")
    print(f"  {output_root / 'catalog.json'}")
    print(f"  {output_root / 'places.json'}")
    print(f"  {output_root / 'network.json'}")
    print(f"  {output_root / 'place_alias_merges.json'}")
    print(f"  {output_root / 'place_alias_candidates.json'}")
    print(f"  {output_root / 'variant_endpoint_attachments.json'}")
    print(f"  {output_root / 'groups/'}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output-root",
        type=Path,
        default=Path("data/processed"),
        help="output directory for processed JSON (default: data/processed)",
    )
    parser.add_argument(
        "--tree-groups",
        metavar="GROUPS",
        help="comma-separated route groups for an ASCII logical tree, e.g. ES10a,ES10b,ES10c,ES10d,ES10e",
    )
    parser.add_argument(
        "--tree-root",
        metavar="PLACE",
        help="normalized place key for the tree root, e.g. baena or santiago",
    )
    parser.add_argument(
        "--tree-reverse",
        action="store_true",
        help="walk incoming edges instead of outgoing edges (useful for a top-down root such as Santiago or Baena)",
    )
    parser.add_argument(
        "--tree-output",
        type=Path,
        help="optional file path for the ASCII tree output",
    )
    parser.add_argument(
        "--network-tree",
        metavar="PLACE",
        help=(
            "render the global processed place network as an ASCII tree, "
            "walking toward origins by default; e.g. --network-tree santiago"
        ),
    )
    parser.add_argument(
        "--network-tree-forward",
        action="store_true",
        help="walk outgoing edges for --network-tree instead of incoming edges",
    )
    parser.add_argument(
        "--network-tree-output",
        type=Path,
        help="optional file path for the global network ASCII tree",
    )
    parser.add_argument(
        "--place-merge-distance",
        type=float,
        default=5000.0,
        metavar="METERS",
        help=(
            "maximum spatial separation for equal normalized place names to "
            "be treated as one place (default: 5000; minimum: 1000)"
        ),
    )

    args = parser.parse_args(argv)

    if args.place_merge_distance < 1000.0:
        fail(
            "--place-merge-distance must be at least 1000 m; Camino Guard "
            "intentionally uses a generous place identity radius."
        )

    if args.tree_groups and not args.tree_root:
        fail("--tree-root is required together with --tree-groups.")

    repo_root = require_repo_root()
    analyzer = load_analyzer(repo_root)
    groups, official_names = load_groups(repo_root, analyzer)

    output_root = (repo_root / args.output_root).resolve() if not args.output_root.is_absolute() else args.output_root
    metadata, route_records, network = build_processed_output(
        repo_root,
        analyzer,
        groups,
        official_names,
        output_root,
        place_merge_distance_m=args.place_merge_distance,
    )
    summarize_build(metadata, route_records, output_root)

    print("Global network:")
    print(f"  places:                 {network['stats']['nodes']}")
    print(f"  logical place pairs:    {network['stats']['logical_pairs']}")
    print(f"  edge families:          {network['stats']['edge_families']}")
    print(f"  alternative pairs:      {network['stats']['alternative_pairs']}")
    print(f"  merged source tracks:   {network['stats']['merged_duplicate_source_tracks']}")
    print(f"  detached variants:      {network['stats']['detached_variant_tracks']}")
    print(f"  weak components:        {network['stats']['weak_components']}")
    print(
        f"  same-name merge radius: {network['spatial_identity']['same_name_merge_distance_m'] / 1000.0:.2f} km"
    )
    print(
        f"  spatially split names:  {network['stats']['spatially_split_normalized_names']}"
    )
    print(f"  automatic aliases:      {network['stats']['automatic_alias_merges']}")
    print(f"  residual aliases:       {network['stats']['alias_candidates']}")
    print(f"  collapsed connectors:   {network['stats']['collapsed_alias_connector_tracks']}")
    print(
        f"  variant endpoints bound: {network['stats']['variant_endpoints_attached_to_primary_places']}"
    )
    print(
        f"  forced variant binds:    {network['stats']['variant_endpoints_forced_outside_radius']}"
    )

    if args.tree_groups:
        selected_codes = parse_group_list(args.tree_groups)
        missing = [code for code in selected_codes if code not in groups]

        if missing:
            fail(f"Unknown route group(s) for --tree-groups: {', '.join(missing)}")

        selected = {code: groups[code] for code in selected_codes}
        tree_graph = build_tree_graph(selected, analyzer)
        tree_text = render_ascii_tree(tree_graph, analyzer.normalize_place_name(args.tree_root), reverse=args.tree_reverse)

        print()
        print("ASCII logical tree")
        print("==================")
        print(tree_text, end="")

        tree_output = args.tree_output
        if tree_output is None:
            mode = "reverse" if args.tree_reverse else "forward"
            tree_output = output_root / "trees" / f"tree_{'_'.join(selected_codes)}_{analyzer.normalize_place_name(args.tree_root)}_{mode}.txt"
        elif not tree_output.is_absolute():
            tree_output = repo_root / tree_output

        tree_output.parent.mkdir(parents=True, exist_ok=True)
        tree_output.write_text(tree_text, encoding="utf-8")
        print(f"Saved tree to:    {tree_output}")

    if args.network_tree:
        root_key = resolve_network_root(
            network,
            args.network_tree,
            analyzer,
        )
        reverse = not args.network_tree_forward
        network_tree_text = render_network_tree(
            network,
            root_key,
            reverse=reverse,
        )

        print()
        print("Global Camino network tree")
        print("==========================")
        print(network_tree_text, end="")

        network_tree_output = args.network_tree_output
        if network_tree_output is None:
            mode = "incoming" if reverse else "outgoing"
            network_tree_output = (
                output_root
                / "trees"
                / f"network_{root_key}_{mode}.txt"
            )
        elif not network_tree_output.is_absolute():
            network_tree_output = repo_root / network_tree_output

        network_tree_output.parent.mkdir(
            parents=True,
            exist_ok=True,
        )
        network_tree_output.write_text(
            network_tree_text,
            encoding="utf-8",
        )
        print(f"Saved network tree to: {network_tree_output}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
