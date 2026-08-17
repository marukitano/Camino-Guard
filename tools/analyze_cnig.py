#!/usr/bin/env python3
"""
Analyze the raw CNIG Camino KML collection without modifying it.

Examples, run from the Camino-Guard repository root:

    python3 tools/analyze_cnig.py
    python3 tools/analyze_cnig.py --threshold-sweep
    python3 tools/analyze_cnig.py --gaps
    python3 tools/analyze_cnig.py --primary-spine
    python3 tools/analyze_cnig.py --group ES01c --primary-spine
    python3 tools/analyze_cnig.py --group ES08a --sequential-gaps
    python3 tools/analyze_cnig.py --solve-orientation
    python3 tools/analyze_cnig.py --solve-orientation --large-gaps
    python3 tools/analyze_cnig.py --variant-bridges
    python3 tools/analyze_cnig.py --cross-group-bridges
    python3 tools/analyze_cnig.py --place-graph
    python3 tools/analyze_cnig.py --semantic-topology
    python3 tools/analyze_cnig.py --group ES22a --semantic-topology
    python3 tools/analyze_cnig.py --duplicate-geometry
    python3 tools/analyze_cnig.py --group ES22a --duplicate-geometry
    python3 tools/analyze_cnig.py --semantic-bridges
    python3 tools/analyze_cnig.py --group PT08a --semantic-bridges
    python3 tools/analyze_cnig.py --group ES35a --solve-orientation
    python3 tools/analyze_cnig.py --offline

Topology is built from track endpoints. Endpoints within --threshold meters
(default: 100 m) are treated as the same graph node. Track direction is ignored
for topology. Sequential-gap analysis keeps real CNIG gaps as gaps; it never
creates synthetic geometry.

The orientation solver treats the numbered 'a' sequence as ordered and chooses
forward/reverse orientation for every track globally. It minimizes the sum of
real endpoint gaps between consecutive tracks and never rewrites raw KML files.

Variant-bridge analysis examines large optimized primary-spine gaps and searches
only existing non-primary CNIG tracks for a complete official connection.

Cross-group bridge analysis goes one step further: it searches the complete
official CNIG track collection, because different named Caminos can share route
segments.

Semantic bridge analysis treats the filename suffix as logical place metadata,
for example ``muge-santarem`` and ``santarem-golega``. It builds a directed
place graph independently from KML point order. Geometry is then measured
separately, so a logical route can remain valid even when official track
endpoints are hundreds of meters apart.

Semantic-topology analysis uses that directed place graph instead of section
number order. Duplicate-geometry analysis then compares every repeated logical
FROM->TO pair and distinguishes exact/same route geometry from genuinely
different alternatives. No mode creates synthetic geometry.
"""

from __future__ import annotations

import argparse
import heapq
import html
import math
import re
import statistics
import sys
import unicodedata
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

from collections import defaultdict, deque
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path


RAW_REL = Path("data/raw/cnig")
CNIG_INDEX = "https://centrodedescargas.cnig.es/CentroDescargas/loadCamSan"
EARTH_RADIUS_M = 6_371_000.0
USER_AGENT = "Mozilla/5.0 Camino-Guard/0.10"
NAME_TIMEOUT_SECONDS = 30
DEFAULT_SWEEP_THRESHOLDS = (50.0, 100.0, 250.0, 500.0, 1000.0)


@dataclass
class TrackInfo:
    path: Path
    route_code: str
    section_id: str
    label: str
    points: int
    start_lat: float
    start_lon: float
    start_ele: float | None
    end_lat: float
    end_lon: float
    end_ele: float | None
    length_m: float
    min_ele: float | None
    max_ele: float | None


@dataclass(frozen=True)
class Endpoint:
    track_index: int
    side: str
    lat: float
    lon: float


@dataclass
class Topology:
    nodes: int
    edges: int
    components: int
    terminals: int
    branch_nodes: int
    cycle_rank: int
    classification: str
    track_nodes: list[tuple[int, int]]
    node_members: dict[int, list[Endpoint]]
    degrees: dict[int, int]
    node_component: dict[int, int]


@dataclass(frozen=True)
class Gap:
    component_a: int
    component_b: int
    endpoint_a: Endpoint
    endpoint_b: Endpoint
    distance_m: float


@dataclass(frozen=True)
class SequentialGap:
    previous: TrackInfo
    following: TrackInfo
    previous_number: int
    following_number: int
    number_step: int
    directed_distance_m: float
    best_distance_m: float
    best_previous_side: str
    best_following_side: str

    @property
    def expected_orientation(self) -> bool:
        return (
            self.best_previous_side == "end"
            and self.best_following_side == "start"
        )


@dataclass
class OrientationSolution:
    tracks: list[TrackInfo]
    reversed_flags: list[bool]
    transition_distances_m: list[float]
    total_gap_m: float
    original_total_gap_m: float
    number_jumps: int

    @property
    def reversed_count(self) -> int:
        return sum(1 for value in self.reversed_flags if value)


@dataclass(frozen=True)
class VariantBridgeStep:
    kind: str
    distance_m: float
    track: TrackInfo | None = None
    reversed_track: bool | None = None
    from_label: str | None = None
    to_label: str | None = None


@dataclass
class VariantBridgeResult:
    found: bool
    steps: list[VariantBridgeStep]
    official_length_m: float
    connector_gap_m: float
    total_cost_m: float
    variant_track_count: int
    nearest_source_m: float | None
    nearest_source_label: str | None
    nearest_target_m: float | None
    nearest_target_label: str | None


@dataclass(frozen=True)
class GlobalEndpoint:
    endpoint_index: int
    track_index: int
    side: str
    lat: float
    lon: float


@dataclass
class GlobalTrackGraph:
    tracks: list[TrackInfo]
    endpoints: list[GlobalEndpoint]
    connector_neighbors: dict[int, list[tuple[int, float]]]


@dataclass
class CrossGroupBridgeResult:
    found: bool
    steps: list[VariantBridgeStep]
    official_length_m: float
    connector_gap_m: float
    total_cost_m: float
    track_count: int
    route_codes: list[str]
    search_limit_m: float
    nearest_source_m: float | None
    nearest_source_label: str | None
    nearest_target_m: float | None
    nearest_target_label: str | None


@dataclass(frozen=True)
class TrackPlaces:
    track: TrackInfo
    from_raw: str
    to_raw: str
    from_key: str
    to_key: str


@dataclass
class PlaceGraph:
    edges_by_from: dict[str, list[TrackPlaces]]
    incoming_by_to: dict[str, list[TrackPlaces]]
    display_names: dict[str, str]
    parseable_tracks: list[TrackPlaces]
    unparseable_tracks: list[TrackInfo]


@dataclass
class SemanticBridgeResult:
    found: bool
    same_place: bool
    source_place: str
    target_place: str
    chain: list[TrackPlaces]
    official_length_m: float
    geometry_reversed_flags: list[bool]
    connector_distances_m: list[float]
    total_connector_gap_m: float
    max_connector_gap_m: float
    search_limit_m: float
    max_hops: int
    outgoing_from_source: int
    incoming_to_target: int


@dataclass
class SemanticTopology:
    places: int
    tracks: int
    weak_components: int
    sources: list[str]
    sinks: list[str]
    branch_places: list[str]
    merge_places: list[str]
    parallel_pairs: int
    cyclic_sccs: int
    cycle_places: int
    classification: str
    outgoing_neighbors: dict[str, set[str]]
    incoming_neighbors: dict[str, set[str]]
    components: list[set[str]]


@dataclass(frozen=True)
class GeometryComparison:
    classification: str
    orientation: str
    exact_coordinates: bool
    length_difference_pct: float
    mean_separation_m: float
    max_separation_m: float


class VisibleTextParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.tokens: list[str] = []
        self._skip_depth = 0

    def handle_starttag(self, tag: str, attrs) -> None:
        if tag.lower() in {"script", "style"}:
            self._skip_depth += 1

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() in {"script", "style"} and self._skip_depth:
            self._skip_depth -= 1

    def handle_data(self, data: str) -> None:
        if self._skip_depth:
            return

        text = html.unescape(data)

        for line in text.splitlines():
            line = re.sub(r"\s+", " ", line).strip()

            if line:
                self.tokens.append(line)


class UnionFind:
    def __init__(self, size: int) -> None:
        self.parent = list(range(size))
        self.rank = [0] * size

    def find(self, item: int) -> int:
        while self.parent[item] != item:
            self.parent[item] = self.parent[self.parent[item]]
            item = self.parent[item]

        return item

    def union(self, a: int, b: int) -> None:
        ra = self.find(a)
        rb = self.find(b)

        if ra == rb:
            return

        if self.rank[ra] < self.rank[rb]:
            ra, rb = rb, ra

        self.parent[rb] = ra

        if self.rank[ra] == self.rank[rb]:
            self.rank[ra] += 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Inspect CNIG Camino route structure and endpoint topology."
    )
    parser.add_argument(
        "--group",
        help="show only one CNIG route group, e.g. ES10c",
    )
    parser.add_argument(
        "--details",
        action="store_true",
        help="show every track in the selected group(s)",
    )
    parser.add_argument(
        "--connections",
        action="store_true",
        help="show endpoint connections used by the topology graph",
    )
    parser.add_argument(
        "--gaps",
        action="store_true",
        help=(
            "for disconnected groups, show the minimum component-bridging "
            "endpoint gaps"
        ),
    )
    parser.add_argument(
        "--threshold-sweep",
        action="store_true",
        help="compare topology at 50/100/250/500/1000 m endpoint thresholds",
    )
    parser.add_argument(
        "--primary-spine",
        action="store_true",
        help=(
            "analyze only numbered 'a' sections (01a, 02a, 03a, ...) as a "
            "candidate primary route spine"
        ),
    )
    parser.add_argument(
        "--sequential-gaps",
        action="store_true",
        help=(
            "show expected primary-spine transitions and their real endpoint "
            "gaps without joining them"
        ),
    )
    parser.add_argument(
        "--solve-orientation",
        action="store_true",
        help=(
            "globally choose forward/reverse orientation for the numbered "
            "'a' spine to minimize consecutive endpoint gaps"
        ),
    )
    parser.add_argument(
        "--large-gaps",
        nargs="?",
        const=2000.0,
        type=float,
        metavar="METERS",
        help=(
            "with --solve-orientation, list optimized sequential gaps above "
            "METERS (default when omitted: 2000)"
        ),
    )
    parser.add_argument(
        "--variant-bridges",
        nargs="?",
        const=2000.0,
        type=float,
        metavar="METERS",
        help=(
            "search existing non-'a' CNIG tracks for bridges across optimized "
            "primary-spine gaps above METERS (default: 2000); endpoint joins "
            "use --threshold"
        ),
    )
    parser.add_argument(
        "--cross-group-bridges",
        nargs="?",
        const=2000.0,
        type=float,
        metavar="METERS",
        help=(
            "search all official CNIG tracks across all route groups for "
            "bridges across optimized primary gaps above METERS "
            "(default: 2000)"
        ),
    )
    parser.add_argument(
        "--bridge-max-factor",
        type=float,
        default=3.0,
        metavar="FACTOR",
        help=(
            "maximum cross-group bridge traversal relative to straight primary "
            "gap; actual limit is max(100 km, gap*FACTOR), default: 3.0"
        ),
    )
    parser.add_argument(
        "--place-graph",
        action="store_true",
        help=(
            "summarize the logical place graph derived from CNIG filename "
            "from/to labels"
        ),
    )
    parser.add_argument(
        "--semantic-bridges",
        nargs="?",
        const=2000.0,
        type=float,
        metavar="METERS",
        help=(
            "resolve optimized primary gaps above METERS through the global "
            "directed place graph (default: 2000)"
        ),
    )
    parser.add_argument(
        "--semantic-max-hops",
        type=int,
        default=8,
        metavar="N",
        help="maximum official tracks in a semantic bridge (default: 8)",
    )
    parser.add_argument(
        "--semantic-max-factor",
        type=float,
        default=3.0,
        metavar="FACTOR",
        help=(
            "maximum official semantic bridge length relative to geometric "
            "primary gap; actual limit is max(100 km, gap*FACTOR), default: 3.0"
        ),
    )
    parser.add_argument(
        "--semantic-topology",
        action="store_true",
        help=(
            "classify each route group from the directed logical place graph "
            "instead of section-number order"
        ),
    )
    parser.add_argument(
        "--duplicate-geometry",
        action="store_true",
        help=(
            "audit repeated normalized FROM-TO pairs and classify their route "
            "geometry as IDENTICAL, SAME, NEAR, or ALTERNATIVE"
        ),
    )
    parser.add_argument(
        "--threshold",
        type=float,
        default=100.0,
        metavar="METERS",
        help="merge endpoints within this distance (default: 100)",
    )
    parser.add_argument(
        "--offline",
        action="store_true",
        help="do not fetch official Camino names from CNIG",
    )
    parser.add_argument(
        "--class",
        dest="classification",
        choices=["LINEAR", "NETWORK", "DISCONNECTED", "OTHER"],
        help="show only groups with this topology classification",
    )

    return parser.parse_args()


def require_repo_root() -> Path:
    root = Path.cwd().resolve()

    if not (root / ".git").is_dir():
        raise SystemExit(
            "ERROR: Run this command from the Camino-Guard repository root."
        )

    raw = root / RAW_REL

    if not raw.is_dir():
        raise SystemExit(f"ERROR: Missing {RAW_REL}/")

    return root


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1 = math.radians(lat1)
    p2 = math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)

    h = (
        math.sin(dp / 2.0) ** 2
        + math.cos(p1) * math.cos(p2) * math.sin(dl / 2.0) ** 2
    )

    return 2.0 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(h)))


def parse_filename(path: Path) -> tuple[str, str, str]:
    match = re.match(
        r"^([A-Za-z]{2}\d{2}[A-Za-z0-9]*)_([0-9]{2}[A-Za-z0-9]*)_(.+)\.kml$",
        path.name,
        flags=re.IGNORECASE,
    )

    if not match:
        raise ValueError(f"Unexpected CNIG filename: {path.name}")

    return match.group(1), match.group(2), match.group(3)


def parse_coordinates(path: Path) -> list[tuple[float, float, float | None]]:
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        raise ValueError(f"{path}: invalid KML: {exc}") from exc

    points: list[tuple[float, float, float | None]] = []

    for elem in root.iter():
        if elem.tag.split("}")[-1] != "coordinates" or not elem.text:
            continue

        for token in elem.text.split():
            parts = token.split(",")

            if len(parts) < 2:
                continue

            try:
                lon = float(parts[0])
                lat = float(parts[1])
            except ValueError:
                continue

            ele: float | None = None

            if len(parts) >= 3 and parts[2].strip():
                try:
                    ele = float(parts[2])
                except ValueError:
                    pass

            points.append((lat, lon, ele))

    if not points:
        raise ValueError(f"{path}: no coordinates")

    return points


def track_length_m(points: list[tuple[float, float, float | None]]) -> float:
    total = 0.0

    for a, b in zip(points, points[1:]):
        total += haversine_m(a[0], a[1], b[0], b[1])

    return total


def read_track(path: Path) -> TrackInfo:
    route_code, section_id, label = parse_filename(path)
    points = parse_coordinates(path)
    elevations = [point[2] for point in points if point[2] is not None]

    return TrackInfo(
        path=path,
        route_code=route_code,
        section_id=section_id,
        label=label,
        points=len(points),
        start_lat=points[0][0],
        start_lon=points[0][1],
        start_ele=points[0][2],
        end_lat=points[-1][0],
        end_lon=points[-1][1],
        end_ele=points[-1][2],
        length_m=track_length_m(points),
        min_ele=min(elevations) if elevations else None,
        max_ele=max(elevations) if elevations else None,
    )


def primary_section_number(section_id: str) -> int | None:
    match = re.fullmatch(r"(\d+)a", section_id, flags=re.IGNORECASE)

    if not match:
        return None

    return int(match.group(1))


def primary_tracks(tracks: list[TrackInfo]) -> list[TrackInfo]:
    result = [
        track
        for track in tracks
        if primary_section_number(track.section_id) is not None
    ]

    result.sort(
        key=lambda track: (
            primary_section_number(track.section_id) or 0,
            track.section_id,
        )
    )

    return result


def variant_slot_count(tracks: list[TrackInfo]) -> int:
    slots: dict[str, list[str]] = defaultdict(list)

    for track in tracks:
        match = re.match(r"^(\d{2})(.*)$", track.section_id)

        if match:
            slots[match.group(1)].append(track.section_id)

    return sum(1 for ids in slots.values() if len(ids) > 1)


def track_endpoints(tracks: list[TrackInfo]) -> list[Endpoint]:
    endpoints: list[Endpoint] = []

    for index, track in enumerate(tracks):
        endpoints.append(
            Endpoint(index, "start", track.start_lat, track.start_lon)
        )
        endpoints.append(
            Endpoint(index, "end", track.end_lat, track.end_lon)
        )

    return endpoints


def build_topology(tracks: list[TrackInfo], threshold_m: float) -> Topology:
    endpoints = track_endpoints(tracks)
    uf = UnionFind(len(endpoints))

    for i in range(len(endpoints)):
        a = endpoints[i]

        for j in range(i + 1, len(endpoints)):
            b = endpoints[j]

            if a.track_index == b.track_index:
                continue

            if haversine_m(a.lat, a.lon, b.lat, b.lon) <= threshold_m:
                uf.union(i, j)

    root_to_node: dict[int, int] = {}
    endpoint_node: list[int] = []

    for i in range(len(endpoints)):
        root = uf.find(i)

        if root not in root_to_node:
            root_to_node[root] = len(root_to_node)

        endpoint_node.append(root_to_node[root])

    node_members: dict[int, list[Endpoint]] = defaultdict(list)

    for i, endpoint in enumerate(endpoints):
        node_members[endpoint_node[i]].append(endpoint)

    track_nodes: list[tuple[int, int]] = []
    adjacency: dict[int, set[int]] = {
        node: set() for node in range(len(root_to_node))
    }
    degrees: dict[int, int] = {
        node: 0 for node in range(len(root_to_node))
    }

    for track_index in range(len(tracks)):
        a = endpoint_node[2 * track_index]
        b = endpoint_node[2 * track_index + 1]
        track_nodes.append((a, b))

        if a == b:
            adjacency[a].add(b)
            degrees[a] += 2
        else:
            adjacency[a].add(b)
            adjacency[b].add(a)
            degrees[a] += 1
            degrees[b] += 1

    components = 0
    node_component: dict[int, int] = {}
    visited: set[int] = set()

    for node in adjacency:
        if node in visited:
            continue

        component_id = components
        components += 1
        queue = deque([node])
        visited.add(node)

        while queue:
            current = queue.popleft()
            node_component[current] = component_id

            for neighbour in adjacency[current]:
                if neighbour not in visited:
                    visited.add(neighbour)
                    queue.append(neighbour)

    nodes = len(root_to_node)
    edges = len(tracks)
    terminals = sum(1 for degree in degrees.values() if degree == 1)
    branch_nodes = sum(1 for degree in degrees.values() if degree >= 3)
    cycle_rank = max(0, edges - nodes + components)

    if components > 1:
        classification = "DISCONNECTED"
    elif branch_nodes > 0 or cycle_rank > 0:
        classification = "NETWORK"
    elif terminals == 2 and cycle_rank == 0:
        classification = "LINEAR"
    else:
        classification = "OTHER"

    return Topology(
        nodes=nodes,
        edges=edges,
        components=components,
        terminals=terminals,
        branch_nodes=branch_nodes,
        cycle_rank=cycle_rank,
        classification=classification,
        track_nodes=track_nodes,
        node_members=dict(node_members),
        degrees=degrees,
        node_component=node_component,
    )


def endpoint_component(
    endpoint: Endpoint,
    topology: Topology,
) -> int:
    node_a, node_b = topology.track_nodes[endpoint.track_index]
    node = node_a if endpoint.side == "start" else node_b

    return topology.node_component[node]


def component_bridge_candidates(
    tracks: list[TrackInfo],
    topology: Topology,
) -> list[Gap]:
    if topology.components <= 1:
        return []

    endpoints = track_endpoints(tracks)
    best: dict[tuple[int, int], Gap] = {}

    for i in range(len(endpoints)):
        a = endpoints[i]
        component_a = endpoint_component(a, topology)

        for j in range(i + 1, len(endpoints)):
            b = endpoints[j]
            component_b = endpoint_component(b, topology)

            if component_a == component_b:
                continue

            ca, cb = sorted((component_a, component_b))
            distance = haversine_m(a.lat, a.lon, b.lat, b.lon)
            key = (ca, cb)

            gap = Gap(
                component_a=ca,
                component_b=cb,
                endpoint_a=a,
                endpoint_b=b,
                distance_m=distance,
            )

            if key not in best or distance < best[key].distance_m:
                best[key] = gap

    return list(best.values())


def minimum_component_bridges(
    tracks: list[TrackInfo],
    topology: Topology,
) -> list[Gap]:
    if topology.components <= 1:
        return []

    candidates = sorted(
        component_bridge_candidates(tracks, topology),
        key=lambda gap: gap.distance_m,
    )

    uf = UnionFind(topology.components)
    chosen: list[Gap] = []

    for gap in candidates:
        if uf.find(gap.component_a) == uf.find(gap.component_b):
            continue

        uf.union(gap.component_a, gap.component_b)
        chosen.append(gap)

        if len(chosen) == topology.components - 1:
            break

    return chosen


def pair_distance(
    a: TrackInfo,
    a_side: str,
    b: TrackInfo,
    b_side: str,
) -> float:
    if a_side == "start":
        a_lat, a_lon = a.start_lat, a.start_lon
    else:
        a_lat, a_lon = a.end_lat, a.end_lon

    if b_side == "start":
        b_lat, b_lon = b.start_lat, b.start_lon
    else:
        b_lat, b_lon = b.end_lat, b.end_lon

    return haversine_m(a_lat, a_lon, b_lat, b_lon)


def analyze_sequential_gaps(
    tracks: list[TrackInfo],
) -> list[SequentialGap]:
    primary = primary_tracks(tracks)
    result: list[SequentialGap] = []

    for previous, following in zip(primary, primary[1:]):
        previous_number = primary_section_number(previous.section_id)
        following_number = primary_section_number(following.section_id)

        assert previous_number is not None
        assert following_number is not None

        combinations = [
            ("end", "start"),
            ("end", "end"),
            ("start", "start"),
            ("start", "end"),
        ]

        distances = [
            (
                pair_distance(
                    previous,
                    previous_side,
                    following,
                    following_side,
                ),
                previous_side,
                following_side,
            )
            for previous_side, following_side in combinations
        ]

        best_distance, best_previous_side, best_following_side = min(
            distances,
            key=lambda item: item[0],
        )

        result.append(
            SequentialGap(
                previous=previous,
                following=following,
                previous_number=previous_number,
                following_number=following_number,
                number_step=following_number - previous_number,
                directed_distance_m=pair_distance(
                    previous,
                    "end",
                    following,
                    "start",
                ),
                best_distance_m=best_distance,
                best_previous_side=best_previous_side,
                best_following_side=best_following_side,
            )
        )

    return result


def oriented_point(
    track: TrackInfo,
    reversed_track: bool,
    role: str,
) -> tuple[float, float]:
    if role not in {"start", "end"}:
        raise ValueError(f"Invalid oriented endpoint role: {role}")

    if not reversed_track:
        if role == "start":
            return track.start_lat, track.start_lon

        return track.end_lat, track.end_lon

    if role == "start":
        return track.end_lat, track.end_lon

    return track.start_lat, track.start_lon


def source_side_for_oriented_role(
    reversed_track: bool,
    role: str,
) -> str:
    if role == "start":
        return "end" if reversed_track else "start"

    if role == "end":
        return "start" if reversed_track else "end"

    raise ValueError(f"Invalid oriented endpoint role: {role}")


def oriented_transition_distance(
    previous: TrackInfo,
    previous_reversed: bool,
    following: TrackInfo,
    following_reversed: bool,
) -> float:
    a_lat, a_lon = oriented_point(
        previous,
        previous_reversed,
        "end",
    )
    b_lat, b_lon = oriented_point(
        following,
        following_reversed,
        "start",
    )

    return haversine_m(a_lat, a_lon, b_lat, b_lon)


def solve_primary_orientation(
    tracks: list[TrackInfo],
) -> OrientationSolution:
    """
    Globally solve forward/reverse orientation of the ordered 'a' spine.

    Dynamic programming has two states per track: forward and reversed.
    The objective is minimum total endpoint-gap distance over the complete
    sequence. If two solutions have equal distance, fewer reversed tracks win.
    Raw KML geometry is never modified.
    """
    primary = primary_tracks(tracks)

    if not primary:
        return OrientationSolution(
            tracks=[],
            reversed_flags=[],
            transition_distances_m=[],
            total_gap_m=0.0,
            original_total_gap_m=0.0,
            number_jumps=0,
        )

    original_total = sum(
        pair_distance(previous, "end", following, "start")
        for previous, following in zip(primary, primary[1:])
    )

    # score[state] = (total_distance, reversed_track_count)
    scores: list[dict[bool, tuple[float, int]]] = [
        {
            False: (0.0, 0),
            True: (0.0, 1),
        }
    ]
    parents: list[dict[bool, bool | None]] = [
        {
            False: None,
            True: None,
        }
    ]

    for index in range(1, len(primary)):
        previous = primary[index - 1]
        current = primary[index]

        current_scores: dict[bool, tuple[float, int]] = {}
        current_parents: dict[bool, bool | None] = {}

        for current_reversed in (False, True):
            candidates: list[
                tuple[float, int, bool]
            ] = []

            for previous_reversed in (False, True):
                prior_distance, prior_reversed_count = scores[index - 1][
                    previous_reversed
                ]
                transition = oriented_transition_distance(
                    previous,
                    previous_reversed,
                    current,
                    current_reversed,
                )

                candidates.append(
                    (
                        prior_distance + transition,
                        prior_reversed_count
                        + (1 if current_reversed else 0),
                        previous_reversed,
                    )
                )

            best = min(
                candidates,
                key=lambda item: (
                    item[0],
                    item[1],
                    1 if item[2] else 0,
                ),
            )
            current_scores[current_reversed] = (
                best[0],
                best[1],
            )
            current_parents[current_reversed] = best[2]

        scores.append(current_scores)
        parents.append(current_parents)

    final_reversed = min(
        (False, True),
        key=lambda state: (
            scores[-1][state][0],
            scores[-1][state][1],
            1 if state else 0,
        ),
    )

    reversed_flags = [False] * len(primary)
    state = final_reversed

    for index in range(len(primary) - 1, -1, -1):
        reversed_flags[index] = state
        parent = parents[index][state]

        if parent is None:
            break

        state = parent

    transition_distances = [
        oriented_transition_distance(
            previous,
            previous_reversed,
            following,
            following_reversed,
        )
        for previous, previous_reversed, following, following_reversed
        in zip(
            primary,
            reversed_flags,
            primary[1:],
            reversed_flags[1:],
        )
    ]

    number_jumps = 0

    for previous, following in zip(primary, primary[1:]):
        previous_number = primary_section_number(previous.section_id)
        following_number = primary_section_number(following.section_id)

        assert previous_number is not None
        assert following_number is not None

        if following_number - previous_number != 1:
            number_jumps += 1

    return OrientationSolution(
        tracks=primary,
        reversed_flags=reversed_flags,
        transition_distances_m=transition_distances,
        total_gap_m=sum(transition_distances),
        original_total_gap_m=original_total,
        number_jumps=number_jumps,
    )


def orientation_label(reversed_track: bool) -> str:
    return "REV" if reversed_track else "FWD"


def orientation_gap_bucket(distance_m: float) -> str:
    return gap_bucket(distance_m)


def is_primary_track(track: TrackInfo) -> bool:
    return primary_section_number(track.section_id) is not None


def raw_endpoint_point(
    track: TrackInfo,
    side: str,
) -> tuple[float, float]:
    if side == "start":
        return track.start_lat, track.start_lon

    if side == "end":
        return track.end_lat, track.end_lon

    raise ValueError(f"Invalid raw endpoint side: {side}")


def endpoint_label(track: TrackInfo, side: str) -> str:
    return f"{track.section_id}.{side}"


def nearest_variant_endpoint(
    variants: list[TrackInfo],
    anchor: tuple[float, float],
) -> tuple[float | None, str | None]:
    best_distance: float | None = None
    best_label: str | None = None

    for track in variants:
        for side in ("start", "end"):
            lat, lon = raw_endpoint_point(track, side)
            distance = haversine_m(
                anchor[0],
                anchor[1],
                lat,
                lon,
            )

            if best_distance is None or distance < best_distance:
                best_distance = distance
                best_label = endpoint_label(track, side)

    return best_distance, best_label


def search_variant_bridge(
    all_tracks: list[TrackInfo],
    source_track: TrackInfo,
    source_reversed: bool,
    target_track: TrackInfo,
    target_reversed: bool,
    join_threshold_m: float,
) -> VariantBridgeResult:
    """
    Search non-primary tracks for a complete connection between two oriented
    primary-spine anchors.

    The graph deliberately alternates:
        connector -> whole official track -> connector -> whole track ...

    Therefore a result cannot hop through a dense cluster of nearby endpoints
    without actually traversing the intervening official CNIG tracks.
    """
    variants = [
        track for track in all_tracks
        if not is_primary_track(track)
    ]

    source = oriented_point(
        source_track,
        source_reversed,
        "end",
    )
    target = oriented_point(
        target_track,
        target_reversed,
        "start",
    )

    (
        nearest_source_m,
        nearest_source_label,
    ) = nearest_variant_endpoint(variants, source)
    (
        nearest_target_m,
        nearest_target_label,
    ) = nearest_variant_endpoint(variants, target)

    if not variants:
        return VariantBridgeResult(
            found=False,
            steps=[],
            official_length_m=0.0,
            connector_gap_m=0.0,
            total_cost_m=0.0,
            variant_track_count=0,
            nearest_source_m=nearest_source_m,
            nearest_source_label=nearest_source_label,
            nearest_target_m=nearest_target_m,
            nearest_target_label=nearest_target_label,
        )

    # Endpoint indices: start=2*i, end=2*i+1.
    endpoint_data: list[tuple[int, str, float, float]] = []

    for index, track in enumerate(variants):
        endpoint_data.append(
            (
                index,
                "start",
                track.start_lat,
                track.start_lon,
            )
        )
        endpoint_data.append(
            (
                index,
                "end",
                track.end_lat,
                track.end_lon,
            )
        )

    source_state = ("source", -1)
    target_state = ("target", -1)

    # State types:
    #   ("arrive", endpoint_index): reached endpoint via connector; must traverse
    #   ("depart", endpoint_index): traversed a whole official track; may connect
    adjacency: dict[
        tuple[str, int],
        list[
            tuple[
                tuple[str, int],
                float,
                VariantBridgeStep,
            ]
        ],
    ] = defaultdict(list)

    # Whole-track traversal edges, in either direction.
    for index, track in enumerate(variants):
        start_ep = 2 * index
        end_ep = start_ep + 1

        adjacency[("arrive", start_ep)].append(
            (
                ("depart", end_ep),
                track.length_m,
                VariantBridgeStep(
                    kind="track",
                    distance_m=track.length_m,
                    track=track,
                    reversed_track=False,
                    from_label=f"{track.section_id}.start",
                    to_label=f"{track.section_id}.end",
                ),
            )
        )
        adjacency[("arrive", end_ep)].append(
            (
                ("depart", start_ep),
                track.length_m,
                VariantBridgeStep(
                    kind="track",
                    distance_m=track.length_m,
                    track=track,
                    reversed_track=True,
                    from_label=f"{track.section_id}.end",
                    to_label=f"{track.section_id}.start",
                ),
            )
        )

    # Source anchor -> a variant endpoint within the join threshold.
    for endpoint_index, (
        variant_index,
        side,
        lat,
        lon,
    ) in enumerate(endpoint_data):
        distance = haversine_m(
            source[0],
            source[1],
            lat,
            lon,
        )

        if distance <= join_threshold_m:
            track = variants[variant_index]
            adjacency[source_state].append(
                (
                    ("arrive", endpoint_index),
                    distance,
                    VariantBridgeStep(
                        kind="connector",
                        distance_m=distance,
                        from_label=(
                            f"{source_track.section_id}."
                            f"{source_side_for_oriented_role(source_reversed, 'end')}"
                        ),
                        to_label=endpoint_label(track, side),
                    ),
                )
            )

    # Variant endpoint -> target anchor within threshold. This is allowed only
    # from a "depart" state, i.e. after traversing the complete variant track.
    for endpoint_index, (
        variant_index,
        side,
        lat,
        lon,
    ) in enumerate(endpoint_data):
        distance = haversine_m(
            lat,
            lon,
            target[0],
            target[1],
        )

        if distance <= join_threshold_m:
            track = variants[variant_index]
            adjacency[("depart", endpoint_index)].append(
                (
                    target_state,
                    distance,
                    VariantBridgeStep(
                        kind="connector",
                        distance_m=distance,
                        from_label=endpoint_label(track, side),
                        to_label=(
                            f"{target_track.section_id}."
                            f"{source_side_for_oriented_role(target_reversed, 'start')}"
                        ),
                    ),
                )
            )

    # Connector edges between endpoints of different variant tracks. They only
    # go depart -> arrive, forcing the next track to be traversed in full.
    for i in range(len(endpoint_data)):
        track_i, side_i, lat_i, lon_i = endpoint_data[i]

        for j in range(i + 1, len(endpoint_data)):
            track_j, side_j, lat_j, lon_j = endpoint_data[j]

            if track_i == track_j:
                continue

            distance = haversine_m(
                lat_i,
                lon_i,
                lat_j,
                lon_j,
            )

            if distance > join_threshold_m:
                continue

            a = variants[track_i]
            b = variants[track_j]

            adjacency[("depart", i)].append(
                (
                    ("arrive", j),
                    distance,
                    VariantBridgeStep(
                        kind="connector",
                        distance_m=distance,
                        from_label=endpoint_label(a, side_i),
                        to_label=endpoint_label(b, side_j),
                    ),
                )
            )
            adjacency[("depart", j)].append(
                (
                    ("arrive", i),
                    distance,
                    VariantBridgeStep(
                        kind="connector",
                        distance_m=distance,
                        from_label=endpoint_label(b, side_j),
                        to_label=endpoint_label(a, side_i),
                    ),
                )
            )

    # Dijkstra by physical distance: official route length + explicitly reported
    # unmapped endpoint connector gaps.
    distances: dict[tuple[str, int], float] = {
        source_state: 0.0
    }
    parents: dict[
        tuple[str, int],
        tuple[
            tuple[str, int],
            VariantBridgeStep,
        ],
    ] = {}
    queue: list[
        tuple[
            float,
            int,
            tuple[str, int],
        ]
    ] = []
    serial = 0
    heapq.heappush(queue, (0.0, serial, source_state))

    while queue:
        current_distance, _, state = heapq.heappop(queue)

        if current_distance != distances.get(state):
            continue

        if state == target_state:
            break

        for next_state, edge_cost, step in adjacency.get(state, []):
            candidate = current_distance + edge_cost

            if candidate >= distances.get(next_state, math.inf):
                continue

            distances[next_state] = candidate
            parents[next_state] = (state, step)
            serial += 1
            heapq.heappush(
                queue,
                (candidate, serial, next_state),
            )

    if target_state not in distances:
        return VariantBridgeResult(
            found=False,
            steps=[],
            official_length_m=0.0,
            connector_gap_m=0.0,
            total_cost_m=0.0,
            variant_track_count=0,
            nearest_source_m=nearest_source_m,
            nearest_source_label=nearest_source_label,
            nearest_target_m=nearest_target_m,
            nearest_target_label=nearest_target_label,
        )

    steps_reversed: list[VariantBridgeStep] = []
    state = target_state

    while state != source_state:
        previous_state, step = parents[state]
        steps_reversed.append(step)
        state = previous_state

    steps = list(reversed(steps_reversed))
    official_length = sum(
        step.distance_m
        for step in steps
        if step.kind == "track"
    )
    connector_gap = sum(
        step.distance_m
        for step in steps
        if step.kind == "connector"
    )
    track_count = sum(
        1 for step in steps
        if step.kind == "track"
    )

    return VariantBridgeResult(
        found=True,
        steps=steps,
        official_length_m=official_length,
        connector_gap_m=connector_gap,
        total_cost_m=distances[target_state],
        variant_track_count=track_count,
        nearest_source_m=nearest_source_m,
        nearest_source_label=nearest_source_label,
        nearest_target_m=nearest_target_m,
        nearest_target_label=nearest_target_label,
    )


def build_global_track_graph(
    tracks: list[TrackInfo],
    join_threshold_m: float,
) -> GlobalTrackGraph:
    """
    Build endpoint connector adjacency for the complete CNIG track collection.

    Each official track itself is traversed separately during Dijkstra. This
    graph stores only small endpoint-to-endpoint joins between different tracks.
    """
    endpoints: list[GlobalEndpoint] = []

    for track_index, track in enumerate(tracks):
        endpoints.append(
            GlobalEndpoint(
                endpoint_index=len(endpoints),
                track_index=track_index,
                side="start",
                lat=track.start_lat,
                lon=track.start_lon,
            )
        )
        endpoints.append(
            GlobalEndpoint(
                endpoint_index=len(endpoints),
                track_index=track_index,
                side="end",
                lat=track.end_lat,
                lon=track.end_lon,
            )
        )

    connector_neighbors: dict[
        int,
        list[tuple[int, float]],
    ] = defaultdict(list)

    # A simple latitude/longitude bucket index avoids an O(N²) all-pairs scan.
    # One degree latitude is ~111 km. We make cells slightly larger than the
    # connector threshold and inspect the surrounding 3x3 cells.
    cell_deg = max(join_threshold_m / 100_000.0, 0.0001)
    buckets: dict[tuple[int, int], list[int]] = defaultdict(list)

    for endpoint in endpoints:
        key = (
            math.floor(endpoint.lat / cell_deg),
            math.floor(endpoint.lon / cell_deg),
        )
        buckets[key].append(endpoint.endpoint_index)

    checked: set[tuple[int, int]] = set()

    for endpoint in endpoints:
        base_x = math.floor(endpoint.lat / cell_deg)
        base_y = math.floor(endpoint.lon / cell_deg)

        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for other_index in buckets.get(
                    (base_x + dx, base_y + dy),
                    [],
                ):
                    if other_index == endpoint.endpoint_index:
                        continue

                    a_index, b_index = sorted(
                        (endpoint.endpoint_index, other_index)
                    )
                    key = (a_index, b_index)

                    if key in checked:
                        continue

                    checked.add(key)
                    other = endpoints[other_index]

                    if endpoint.track_index == other.track_index:
                        continue

                    distance = haversine_m(
                        endpoint.lat,
                        endpoint.lon,
                        other.lat,
                        other.lon,
                    )

                    if distance > join_threshold_m:
                        continue

                    connector_neighbors[endpoint.endpoint_index].append(
                        (other.endpoint_index, distance)
                    )
                    connector_neighbors[other.endpoint_index].append(
                        (endpoint.endpoint_index, distance)
                    )

    return GlobalTrackGraph(
        tracks=tracks,
        endpoints=endpoints,
        connector_neighbors=dict(connector_neighbors),
    )


def nearest_global_endpoint(
    graph: GlobalTrackGraph,
    anchor: tuple[float, float],
    excluded_track_indices: set[int],
) -> tuple[float | None, str | None]:
    best_distance: float | None = None
    best_label: str | None = None

    for endpoint in graph.endpoints:
        if endpoint.track_index in excluded_track_indices:
            continue

        track = graph.tracks[endpoint.track_index]
        distance = haversine_m(
            anchor[0],
            anchor[1],
            endpoint.lat,
            endpoint.lon,
        )

        if best_distance is None or distance < best_distance:
            best_distance = distance
            best_label = (
                f"{track.route_code}:{track.section_id}.{endpoint.side}"
            )

    return best_distance, best_label


def search_cross_group_bridge(
    graph: GlobalTrackGraph,
    source_track: TrackInfo,
    source_reversed: bool,
    target_track: TrackInfo,
    target_reversed: bool,
    join_threshold_m: float,
    search_limit_m: float,
) -> CrossGroupBridgeResult:
    """
    Search the complete CNIG network for a bridge.

    State alternation is the same safety rule as variant bridging:
      anchor -> ARRIVE endpoint -> traverse WHOLE official track -> DEPART
             -> small connector -> ARRIVE next track -> ...

    Source and target tracks are excluded from traversal, preventing the search
    from "solving" the gap by looping through the tracks that define the gap.
    """
    source = oriented_point(
        source_track,
        source_reversed,
        "end",
    )
    target = oriented_point(
        target_track,
        target_reversed,
        "start",
    )

    excluded_indices = {
        index
        for index, track in enumerate(graph.tracks)
        if track.path == source_track.path or track.path == target_track.path
    }

    nearest_source_m, nearest_source_label = nearest_global_endpoint(
        graph,
        source,
        excluded_indices,
    )
    nearest_target_m, nearest_target_label = nearest_global_endpoint(
        graph,
        target,
        excluded_indices,
    )

    # Endpoint state encoded as integer:
    #   2*endpoint_index     = ARRIVE (must traverse that official track)
    #   2*endpoint_index + 1 = DEPART (may take connector / reach target)
    #
    # We use negative sentinels for source/target.
    SOURCE_STATE = -1
    TARGET_STATE = -2

    def arrive_state(endpoint_index: int) -> int:
        return 2 * endpoint_index

    def depart_state(endpoint_index: int) -> int:
        return 2 * endpoint_index + 1

    # Source/target connector candidates.
    source_candidates: list[tuple[int, float]] = []
    target_candidates: dict[int, float] = {}

    for endpoint in graph.endpoints:
        if endpoint.track_index in excluded_indices:
            continue

        source_distance = haversine_m(
            source[0],
            source[1],
            endpoint.lat,
            endpoint.lon,
        )

        if source_distance <= join_threshold_m:
            source_candidates.append(
                (endpoint.endpoint_index, source_distance)
            )

        target_distance = haversine_m(
            endpoint.lat,
            endpoint.lon,
            target[0],
            target[1],
        )

        if target_distance <= join_threshold_m:
            target_candidates[endpoint.endpoint_index] = target_distance

    distances: dict[int, float] = {SOURCE_STATE: 0.0}
    parents: dict[int, tuple[int, VariantBridgeStep]] = {}
    queue: list[tuple[float, int, int]] = []
    serial = 0
    heapq.heappush(queue, (0.0, serial, SOURCE_STATE))

    while queue:
        current_distance, _, state = heapq.heappop(queue)

        if current_distance != distances.get(state):
            continue

        if current_distance > search_limit_m:
            continue

        if state == TARGET_STATE:
            break

        neighbors: list[tuple[int, float, VariantBridgeStep]] = []

        if state == SOURCE_STATE:
            for endpoint_index, connector_distance in source_candidates:
                endpoint = graph.endpoints[endpoint_index]
                track = graph.tracks[endpoint.track_index]

                neighbors.append(
                    (
                        arrive_state(endpoint_index),
                        connector_distance,
                        VariantBridgeStep(
                            kind="connector",
                            distance_m=connector_distance,
                            from_label=(
                                f"{source_track.route_code}:"
                                f"{source_track.section_id}."
                                f"{source_side_for_oriented_role(source_reversed, 'end')}"
                            ),
                            to_label=(
                                f"{track.route_code}:"
                                f"{track.section_id}.{endpoint.side}"
                            ),
                        ),
                    )
                )

        elif state >= 0:
            endpoint_index = state // 2
            endpoint = graph.endpoints[endpoint_index]
            track_index = endpoint.track_index
            track = graph.tracks[track_index]

            if state % 2 == 0:
                # ARRIVE: must traverse the whole official track.
                if track_index not in excluded_indices:
                    other_endpoint_index = (
                        endpoint_index + 1
                        if endpoint.side == "start"
                        else endpoint_index - 1
                    )
                    reversed_track = endpoint.side == "end"

                    neighbors.append(
                        (
                            depart_state(other_endpoint_index),
                            track.length_m,
                            VariantBridgeStep(
                                kind="track",
                                distance_m=track.length_m,
                                track=track,
                                reversed_track=reversed_track,
                                from_label=(
                                    f"{track.route_code}:"
                                    f"{track.section_id}.{endpoint.side}"
                                ),
                                to_label=(
                                    f"{track.route_code}:"
                                    f"{track.section_id}."
                                    f"{'start' if endpoint.side == 'end' else 'end'}"
                                ),
                            ),
                        )
                    )

            else:
                # DEPART: may reach target or connect to another track endpoint.
                target_distance = target_candidates.get(endpoint_index)

                if target_distance is not None:
                    neighbors.append(
                        (
                            TARGET_STATE,
                            target_distance,
                            VariantBridgeStep(
                                kind="connector",
                                distance_m=target_distance,
                                from_label=(
                                    f"{track.route_code}:"
                                    f"{track.section_id}.{endpoint.side}"
                                ),
                                to_label=(
                                    f"{target_track.route_code}:"
                                    f"{target_track.section_id}."
                                    f"{source_side_for_oriented_role(target_reversed, 'start')}"
                                ),
                            ),
                        )
                    )

                for other_index, connector_distance in graph.connector_neighbors.get(
                    endpoint_index,
                    [],
                ):
                    other = graph.endpoints[other_index]

                    if other.track_index in excluded_indices:
                        continue

                    other_track = graph.tracks[other.track_index]

                    neighbors.append(
                        (
                            arrive_state(other_index),
                            connector_distance,
                            VariantBridgeStep(
                                kind="connector",
                                distance_m=connector_distance,
                                from_label=(
                                    f"{track.route_code}:"
                                    f"{track.section_id}.{endpoint.side}"
                                ),
                                to_label=(
                                    f"{other_track.route_code}:"
                                    f"{other_track.section_id}.{other.side}"
                                ),
                            ),
                        )
                    )

        for next_state, edge_cost, step in neighbors:
            candidate = current_distance + edge_cost

            if candidate > search_limit_m:
                continue

            if candidate >= distances.get(next_state, math.inf):
                continue

            distances[next_state] = candidate
            parents[next_state] = (state, step)
            serial += 1
            heapq.heappush(
                queue,
                (candidate, serial, next_state),
            )

    if TARGET_STATE not in distances:
        return CrossGroupBridgeResult(
            found=False,
            steps=[],
            official_length_m=0.0,
            connector_gap_m=0.0,
            total_cost_m=0.0,
            track_count=0,
            route_codes=[],
            search_limit_m=search_limit_m,
            nearest_source_m=nearest_source_m,
            nearest_source_label=nearest_source_label,
            nearest_target_m=nearest_target_m,
            nearest_target_label=nearest_target_label,
        )

    steps_reversed: list[VariantBridgeStep] = []
    state = TARGET_STATE

    while state != SOURCE_STATE:
        previous_state, step = parents[state]
        steps_reversed.append(step)
        state = previous_state

    steps = list(reversed(steps_reversed))

    official_length = sum(
        step.distance_m
        for step in steps
        if step.kind == "track"
    )
    connector_gap = sum(
        step.distance_m
        for step in steps
        if step.kind == "connector"
    )
    used_tracks = [
        step.track
        for step in steps
        if step.kind == "track" and step.track is not None
    ]
    route_codes: list[str] = []

    for track in used_tracks:
        if track.route_code not in route_codes:
            route_codes.append(track.route_code)

    return CrossGroupBridgeResult(
        found=True,
        steps=steps,
        official_length_m=official_length,
        connector_gap_m=connector_gap,
        total_cost_m=distances[TARGET_STATE],
        track_count=len(used_tracks),
        route_codes=route_codes,
        search_limit_m=search_limit_m,
        nearest_source_m=nearest_source_m,
        nearest_source_label=nearest_source_label,
        nearest_target_m=nearest_target_m,
        nearest_target_label=nearest_target_label,
    )


def normalize_place_name(value: str) -> str:
    normalized = unicodedata.normalize("NFKD", value)
    ascii_value = "".join(
        char
        for char in normalized
        if not unicodedata.combining(char)
    ).lower()

    return re.sub(r"[^a-z0-9]+", "", ascii_value)


def display_place_name(value: str) -> str:
    return value.replace("_", " ").strip()


def parse_track_places(track: TrackInfo) -> TrackPlaces | None:
    """
    Parse the CNIG filename suffix as semantic FROM-TO metadata.

    CNIG place names use underscores internally, while the route endpoint
    separator is a hyphen in the current dataset, e.g.:
        muge-santarem
        santarem-golega

    We split once so the analyzer remains tolerant if a later source token
    contains an additional hyphen.
    """
    if "-" not in track.label:
        return None

    from_raw, to_raw = track.label.split("-", 1)
    from_raw = from_raw.strip()
    to_raw = to_raw.strip()

    if not from_raw or not to_raw:
        return None

    from_key = normalize_place_name(from_raw)
    to_key = normalize_place_name(to_raw)

    if not from_key or not to_key:
        return None

    return TrackPlaces(
        track=track,
        from_raw=from_raw,
        to_raw=to_raw,
        from_key=from_key,
        to_key=to_key,
    )


def build_place_graph(
    tracks: list[TrackInfo],
) -> PlaceGraph:
    edges_by_from: dict[str, list[TrackPlaces]] = defaultdict(list)
    incoming_by_to: dict[str, list[TrackPlaces]] = defaultdict(list)
    display_names: dict[str, str] = {}
    parseable: list[TrackPlaces] = []
    unparseable: list[TrackInfo] = []

    for track in tracks:
        places = parse_track_places(track)

        if places is None:
            unparseable.append(track)
            continue

        parseable.append(places)
        edges_by_from[places.from_key].append(places)
        incoming_by_to[places.to_key].append(places)

        display_names.setdefault(
            places.from_key,
            display_place_name(places.from_raw),
        )
        display_names.setdefault(
            places.to_key,
            display_place_name(places.to_raw),
        )

    for edges in edges_by_from.values():
        edges.sort(
            key=lambda item: (
                item.track.length_m,
                item.track.route_code,
                item.track.section_id,
            )
        )

    return PlaceGraph(
        edges_by_from=dict(edges_by_from),
        incoming_by_to=dict(incoming_by_to),
        display_names=display_names,
        parseable_tracks=parseable,
        unparseable_tracks=unparseable,
    )


def semantic_track_chain(
    graph: PlaceGraph,
    source_key: str,
    target_key: str,
    excluded_paths: set[Path],
    max_hops: int,
    max_length_m: float,
) -> list[TrackPlaces] | None:
    """
    Directed Dijkstra over logical places.

    Edge cost is the official track length. State includes hop count so the
    caller can cap semantic complexity without inventing geometric joins.
    """
    if source_key == target_key:
        return []

    start_state = (source_key, 0)
    distances: dict[tuple[str, int], float] = {
        start_state: 0.0
    }
    parents: dict[
        tuple[str, int],
        tuple[tuple[str, int], TrackPlaces],
    ] = {}
    queue: list[tuple[float, int, str, int]] = []
    serial = 0
    heapq.heappush(
        queue,
        (0.0, serial, source_key, 0),
    )
    final_state: tuple[str, int] | None = None

    while queue:
        distance, _, place_key, hops = heapq.heappop(queue)
        state = (place_key, hops)

        if distance != distances.get(state):
            continue

        if place_key == target_key:
            final_state = state
            break

        if hops >= max_hops:
            continue

        for edge in graph.edges_by_from.get(place_key, []):
            if edge.track.path in excluded_paths:
                continue

            candidate = distance + edge.track.length_m

            if candidate > max_length_m:
                continue

            next_state = (edge.to_key, hops + 1)

            if candidate >= distances.get(next_state, math.inf):
                continue

            distances[next_state] = candidate
            parents[next_state] = (state, edge)
            serial += 1
            heapq.heappush(
                queue,
                (
                    candidate,
                    serial,
                    edge.to_key,
                    hops + 1,
                ),
            )

    if final_state is None:
        return None

    chain_reversed: list[TrackPlaces] = []
    state = final_state

    while state != start_state:
        previous_state, edge = parents[state]
        chain_reversed.append(edge)
        state = previous_state

    return list(reversed(chain_reversed))


def optimize_semantic_chain_geometry(
    previous: TrackInfo,
    previous_reversed: bool,
    chain: list[TrackPlaces],
    following: TrackInfo,
    following_reversed: bool,
) -> tuple[list[bool], list[float]]:
    """
    Keep semantic route direction fixed, but choose KML point order (FWD/REV)
    for every bridge track to minimize the sum of physical connector gaps.

    Connector distances are never used to reject the semantic route. They are
    measurements only.
    """
    source = oriented_point(
        previous,
        previous_reversed,
        "end",
    )
    target = oriented_point(
        following,
        following_reversed,
        "start",
    )

    if not chain:
        return (
            [],
            [
                haversine_m(
                    source[0],
                    source[1],
                    target[0],
                    target[1],
                )
            ],
        )

    # scores[i][state] = (connector_sum, reversed_count)
    scores: list[dict[bool, tuple[float, int]]] = []
    parents: list[dict[bool, bool | None]] = []

    first = chain[0].track
    first_scores: dict[bool, tuple[float, int]] = {}
    first_parents: dict[bool, bool | None] = {}

    for reversed_track in (False, True):
        start_lat, start_lon = oriented_point(
            first,
            reversed_track,
            "start",
        )
        connector = haversine_m(
            source[0],
            source[1],
            start_lat,
            start_lon,
        )
        first_scores[reversed_track] = (
            connector,
            1 if reversed_track else 0,
        )
        first_parents[reversed_track] = None

    scores.append(first_scores)
    parents.append(first_parents)

    for index in range(1, len(chain)):
        previous_track = chain[index - 1].track
        current_track = chain[index].track
        current_scores: dict[bool, tuple[float, int]] = {}
        current_parents: dict[bool, bool | None] = {}

        for current_reversed in (False, True):
            candidates: list[tuple[float, int, bool]] = []

            current_start = oriented_point(
                current_track,
                current_reversed,
                "start",
            )

            for prior_reversed in (False, True):
                prior_end = oriented_point(
                    previous_track,
                    prior_reversed,
                    "end",
                )
                connector = haversine_m(
                    prior_end[0],
                    prior_end[1],
                    current_start[0],
                    current_start[1],
                )
                prior_score, prior_reverse_count = scores[
                    index - 1
                ][prior_reversed]

                candidates.append(
                    (
                        prior_score + connector,
                        prior_reverse_count
                        + (1 if current_reversed else 0),
                        prior_reversed,
                    )
                )

            best = min(
                candidates,
                key=lambda item: (
                    item[0],
                    item[1],
                    1 if item[2] else 0,
                ),
            )
            current_scores[current_reversed] = (
                best[0],
                best[1],
            )
            current_parents[current_reversed] = best[2]

        scores.append(current_scores)
        parents.append(current_parents)

    last_track = chain[-1].track
    final_candidates: list[tuple[float, int, bool]] = []

    for last_reversed in (False, True):
        last_end = oriented_point(
            last_track,
            last_reversed,
            "end",
        )
        target_connector = haversine_m(
            last_end[0],
            last_end[1],
            target[0],
            target[1],
        )
        score, reverse_count = scores[-1][last_reversed]
        final_candidates.append(
            (
                score + target_connector,
                reverse_count,
                last_reversed,
            )
        )

    final = min(
        final_candidates,
        key=lambda item: (
            item[0],
            item[1],
            1 if item[2] else 0,
        ),
    )

    flags = [False] * len(chain)
    state = final[2]

    for index in range(len(chain) - 1, -1, -1):
        flags[index] = state
        parent = parents[index][state]

        if parent is None:
            break

        state = parent

    connectors: list[float] = []

    first_start = oriented_point(
        chain[0].track,
        flags[0],
        "start",
    )
    connectors.append(
        haversine_m(
            source[0],
            source[1],
            first_start[0],
            first_start[1],
        )
    )

    for index in range(len(chain) - 1):
        current_end = oriented_point(
            chain[index].track,
            flags[index],
            "end",
        )
        next_start = oriented_point(
            chain[index + 1].track,
            flags[index + 1],
            "start",
        )
        connectors.append(
            haversine_m(
                current_end[0],
                current_end[1],
                next_start[0],
                next_start[1],
            )
        )

    last_end = oriented_point(
        chain[-1].track,
        flags[-1],
        "end",
    )
    connectors.append(
        haversine_m(
            last_end[0],
            last_end[1],
            target[0],
            target[1],
        )
    )

    return flags, connectors


def search_semantic_bridge(
    graph: PlaceGraph,
    previous: TrackInfo,
    previous_reversed: bool,
    following: TrackInfo,
    following_reversed: bool,
    max_hops: int,
    max_length_m: float,
) -> SemanticBridgeResult:
    previous_places = parse_track_places(previous)
    following_places = parse_track_places(following)

    if previous_places is None or following_places is None:
        return SemanticBridgeResult(
            found=False,
            same_place=False,
            source_place="?",
            target_place="?",
            chain=[],
            official_length_m=0.0,
            geometry_reversed_flags=[],
            connector_distances_m=[],
            total_connector_gap_m=0.0,
            max_connector_gap_m=0.0,
            search_limit_m=max_length_m,
            max_hops=max_hops,
            outgoing_from_source=0,
            incoming_to_target=0,
        )

    source_key = previous_places.to_key
    target_key = following_places.from_key
    source_display = graph.display_names.get(
        source_key,
        display_place_name(previous_places.to_raw),
    )
    target_display = graph.display_names.get(
        target_key,
        display_place_name(following_places.from_raw),
    )

    outgoing = len(graph.edges_by_from.get(source_key, []))
    incoming = len(graph.incoming_by_to.get(target_key, []))

    chain = semantic_track_chain(
        graph,
        source_key,
        target_key,
        {
            previous.path,
            following.path,
        },
        max_hops,
        max_length_m,
    )

    if chain is None:
        return SemanticBridgeResult(
            found=False,
            same_place=False,
            source_place=source_display,
            target_place=target_display,
            chain=[],
            official_length_m=0.0,
            geometry_reversed_flags=[],
            connector_distances_m=[],
            total_connector_gap_m=0.0,
            max_connector_gap_m=0.0,
            search_limit_m=max_length_m,
            max_hops=max_hops,
            outgoing_from_source=outgoing,
            incoming_to_target=incoming,
        )

    flags, connectors = optimize_semantic_chain_geometry(
        previous,
        previous_reversed,
        chain,
        following,
        following_reversed,
    )

    return SemanticBridgeResult(
        found=True,
        same_place=(source_key == target_key),
        source_place=source_display,
        target_place=target_display,
        chain=chain,
        official_length_m=sum(
            edge.track.length_m for edge in chain
        ),
        geometry_reversed_flags=flags,
        connector_distances_m=connectors,
        total_connector_gap_m=sum(connectors),
        max_connector_gap_m=max(connectors, default=0.0),
        search_limit_m=max_length_m,
        max_hops=max_hops,
        outgoing_from_source=outgoing,
        incoming_to_target=incoming,
    )


def semantic_pair_groups(
    tracks: list[TrackInfo],
) -> dict[tuple[str, str], list[TrackPlaces]]:
    pairs: dict[tuple[str, str], list[TrackPlaces]] = defaultdict(list)

    for track in tracks:
        places = parse_track_places(track)

        if places is None:
            continue

        pairs[(places.from_key, places.to_key)].append(places)

    return dict(pairs)


def weak_place_components(
    places: set[str],
    outgoing: dict[str, set[str]],
    incoming: dict[str, set[str]],
) -> list[set[str]]:
    components: list[set[str]] = []
    visited: set[str] = set()

    for start in sorted(places):
        if start in visited:
            continue

        component: set[str] = set()
        queue = deque([start])
        visited.add(start)

        while queue:
            current = queue.popleft()
            component.add(current)

            neighbours = (
                outgoing.get(current, set())
                | incoming.get(current, set())
            )

            for neighbour in neighbours:
                if neighbour in visited:
                    continue

                visited.add(neighbour)
                queue.append(neighbour)

        components.append(component)

    components.sort(
        key=lambda component: (
            -len(component),
            sorted(component)[0] if component else "",
        )
    )

    return components


def strongly_connected_place_components(
    places: set[str],
    outgoing: dict[str, set[str]],
) -> list[set[str]]:
    """Tarjan SCC decomposition of the directed logical place graph."""
    index = 0
    stack: list[str] = []
    on_stack: set[str] = set()
    indices: dict[str, int] = {}
    lowlinks: dict[str, int] = {}
    result: list[set[str]] = []

    sys.setrecursionlimit(
        max(
            sys.getrecursionlimit(),
            len(places) * 4 + 1000,
        )
    )

    def visit(node: str) -> None:
        nonlocal index

        indices[node] = index
        lowlinks[node] = index
        index += 1
        stack.append(node)
        on_stack.add(node)

        for neighbour in outgoing.get(node, set()):
            if neighbour not in indices:
                visit(neighbour)
                lowlinks[node] = min(
                    lowlinks[node],
                    lowlinks[neighbour],
                )
            elif neighbour in on_stack:
                lowlinks[node] = min(
                    lowlinks[node],
                    indices[neighbour],
                )

        if lowlinks[node] != indices[node]:
            return

        component: set[str] = set()

        while True:
            member = stack.pop()
            on_stack.remove(member)
            component.add(member)

            if member == node:
                break

        result.append(component)

    for place in sorted(places):
        if place not in indices:
            visit(place)

    return result


def build_semantic_topology(
    tracks: list[TrackInfo],
) -> SemanticTopology:
    pair_groups = semantic_pair_groups(tracks)
    places: set[str] = set()
    outgoing: dict[str, set[str]] = defaultdict(set)
    incoming: dict[str, set[str]] = defaultdict(set)

    for (from_key, to_key), edges in pair_groups.items():
        places.add(from_key)
        places.add(to_key)
        outgoing[from_key].add(to_key)
        incoming[to_key].add(from_key)

        # Ensure keys exist for degree lookups.
        outgoing.setdefault(to_key, set())
        incoming.setdefault(from_key, set())

    sources = sorted(
        place
        for place in places
        if len(incoming.get(place, set())) == 0
        and len(outgoing.get(place, set())) > 0
    )
    sinks = sorted(
        place
        for place in places
        if len(outgoing.get(place, set())) == 0
        and len(incoming.get(place, set())) > 0
    )
    branches = sorted(
        place
        for place in places
        if len(outgoing.get(place, set())) >= 2
    )
    merges = sorted(
        place
        for place in places
        if len(incoming.get(place, set())) >= 2
    )

    components = weak_place_components(
        places,
        outgoing,
        incoming,
    )
    sccs = strongly_connected_place_components(
        places,
        outgoing,
    )

    cyclic_sccs = [
        component
        for component in sccs
        if len(component) > 1
    ]
    cycle_places = sum(
        len(component)
        for component in cyclic_sccs
    )
    parallel_pairs = sum(
        1
        for edges in pair_groups.values()
        if len(edges) > 1
    )

    if not tracks:
        classification = "EMPTY"
    elif len(components) > 1:
        classification = "DISCONNECTED"
    elif branches or merges or cyclic_sccs:
        classification = "NETWORK"
    elif len(sources) == 1 and len(sinks) == 1:
        classification = "LINEAR"
    else:
        classification = "OTHER"

    return SemanticTopology(
        places=len(places),
        tracks=len(tracks),
        weak_components=len(components),
        sources=sources,
        sinks=sinks,
        branch_places=branches,
        merge_places=merges,
        parallel_pairs=parallel_pairs,
        cyclic_sccs=len(cyclic_sccs),
        cycle_places=cycle_places,
        classification=classification,
        outgoing_neighbors={
            key: set(value)
            for key, value in outgoing.items()
        },
        incoming_neighbors={
            key: set(value)
            for key, value in incoming.items()
        },
        components=components,
    )


def polyline_cumulative_distances(
    points: list[tuple[float, float, float | None]],
) -> list[float]:
    cumulative = [0.0]

    for previous, current in zip(points, points[1:]):
        cumulative.append(
            cumulative[-1]
            + haversine_m(
                previous[0],
                previous[1],
                current[0],
                current[1],
            )
        )

    return cumulative


def resample_polyline(
    points: list[tuple[float, float, float | None]],
    sample_count: int = 101,
) -> list[tuple[float, float]]:
    if not points:
        return []

    if len(points) == 1:
        return [(points[0][0], points[0][1])] * sample_count

    cumulative = polyline_cumulative_distances(points)
    total = cumulative[-1]

    if total <= 0.0:
        return [(points[0][0], points[0][1])] * sample_count

    result: list[tuple[float, float]] = []
    segment = 0

    for sample_index in range(sample_count):
        target = total * sample_index / (sample_count - 1)

        while (
            segment + 1 < len(cumulative) - 1
            and cumulative[segment + 1] < target
        ):
            segment += 1

        start_distance = cumulative[segment]
        end_distance = cumulative[segment + 1]
        span = end_distance - start_distance

        if span <= 0.0:
            fraction = 0.0
        else:
            fraction = (target - start_distance) / span

        a = points[segment]
        b = points[segment + 1]
        lat = a[0] + (b[0] - a[0]) * fraction
        lon = a[1] + (b[1] - a[1]) * fraction
        result.append((lat, lon))

    return result


def sampled_separation(
    a: list[tuple[float, float]],
    b: list[tuple[float, float]],
) -> tuple[float, float]:
    if not a or not b or len(a) != len(b):
        return math.inf, math.inf

    distances = [
        haversine_m(
            point_a[0],
            point_a[1],
            point_b[0],
            point_b[1],
        )
        for point_a, point_b in zip(a, b)
    ]

    return (
        statistics.mean(distances),
        max(distances),
    )


def compare_track_geometry(
    a: TrackInfo,
    b: TrackInfo,
    sample_count: int = 101,
) -> GeometryComparison:
    points_a = parse_coordinates(a.path)
    points_b = parse_coordinates(b.path)

    coordinates_a = [
        (point[0], point[1], point[2])
        for point in points_a
    ]
    coordinates_b = [
        (point[0], point[1], point[2])
        for point in points_b
    ]

    exact_direct = coordinates_a == coordinates_b
    exact_reverse = coordinates_a == list(reversed(coordinates_b))

    length_base = max(
        min(a.length_m, b.length_m),
        1.0,
    )
    length_difference_pct = (
        abs(a.length_m - b.length_m)
        / length_base
        * 100.0
    )

    sampled_a = resample_polyline(
        points_a,
        sample_count,
    )
    sampled_b = resample_polyline(
        points_b,
        sample_count,
    )

    mean_direct, max_direct = sampled_separation(
        sampled_a,
        sampled_b,
    )
    mean_reverse, max_reverse = sampled_separation(
        sampled_a,
        list(reversed(sampled_b)),
    )

    if (
        (max_reverse, mean_reverse)
        < (max_direct, mean_direct)
    ):
        orientation = "REVERSED"
        mean_separation = mean_reverse
        max_separation = max_reverse
    else:
        orientation = "SAME"
        mean_separation = mean_direct
        max_separation = max_direct

    exact_coordinates = exact_direct or exact_reverse

    if exact_coordinates:
        classification = "IDENTICAL"
    elif (
        max_separation <= 30.0
        and length_difference_pct <= 2.0
    ):
        classification = "SAME"
    elif (
        max_separation <= 100.0
        and length_difference_pct <= 5.0
    ):
        classification = "NEAR"
    else:
        classification = "ALTERNATIVE"

    return GeometryComparison(
        classification=classification,
        orientation=orientation,
        exact_coordinates=exact_coordinates,
        length_difference_pct=length_difference_pct,
        mean_separation_m=mean_separation,
        max_separation_m=max_separation,
    )


def duplicate_pair_classification(
    comparisons: list[GeometryComparison],
) -> str:
    if not comparisons:
        return "SINGLE"

    order = {
        "IDENTICAL": 0,
        "SAME": 1,
        "NEAR": 2,
        "ALTERNATIVE": 3,
    }

    return max(
        comparisons,
        key=lambda item: order[item.classification],
    ).classification


def fetch_cnig_index() -> str:
    request = urllib.request.Request(
        CNIG_INDEX,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "text/html,application/xhtml+xml",
        },
    )

    with urllib.request.urlopen(
        request,
        timeout=NAME_TIMEOUT_SECONDS,
    ) as response:
        raw = response.read()
        charset = response.headers.get_content_charset() or "utf-8"

        return raw.decode(charset, errors="replace")


def looks_like_group_name(token: str) -> bool:
    lower = token.lower()

    if ".kml" in lower:
        return False

    rejected = {
        "descargar",
        "licencia",
        "rutas del camino de santiago",
        "etapas de los diferentes caminos de santiago.",
        "iniciar sesión",
        "buscar",
        "inicio",
    }

    if lower in rejected:
        return False

    keywords = (
        "camino",
        "caminos",
        "cami ",
        "camí",
        "caminho",
        "chemin",
        "voie ",
        "vía ",
        "via ",
        "ruta ",
    )

    return any(keyword in lower for keyword in keywords)


def parse_group_names(index_html: str) -> dict[str, str]:
    parser = VisibleTextParser()
    parser.feed(index_html)
    tokens = parser.tokens

    kml_re = re.compile(
        r"\b([A-Za-z]{2}\d{2}[A-Za-z0-9]*)-[0-9]{2}[A-Za-z0-9]-"
        r"[^<>\s]+?\.kml\b",
        flags=re.IGNORECASE,
    )

    names: dict[str, str] = {}
    last_kml_token = -1

    for index, token in enumerate(tokens):
        match = kml_re.search(token)

        if not match:
            continue

        route_code = match.group(1)

        if route_code not in names:
            candidates = tokens[last_kml_token + 1:index]
            chosen: str | None = None

            for candidate in reversed(candidates):
                if looks_like_group_name(candidate):
                    chosen = candidate
                    break

            if chosen is None:
                for candidate in reversed(tokens[max(0, index - 20):index]):
                    if looks_like_group_name(candidate):
                        chosen = candidate
                        break

            if chosen:
                names[route_code] = chosen

        last_kml_token = index

    return names


def endpoint_name(endpoint: Endpoint, tracks: list[TrackInfo]) -> str:
    track = tracks[endpoint.track_index]

    return f"{track.section_id}.{endpoint.side}"


def print_group_summary(
    route_code: str,
    official_name: str | None,
    tracks: list[TrackInfo],
    topology: Topology,
) -> None:
    total_points = sum(track.points for track in tracks)
    total_km = sum(track.length_m for track in tracks) / 1000.0
    variants = variant_slot_count(tracks)

    print(
        f"{route_code:8} "
        f"{topology.classification:12} "
        f"{len(tracks):3} trk  "
        f"{total_km:7.1f} kmΣ  "
        f"comp {topology.components:2}  "
        f"term {topology.terminals:2}  "
        f"branch {topology.branch_nodes:2}  "
        f"cycles {topology.cycle_rank:2}  "
        f"varslots {variants:2}"
    )

    if official_name:
        print(f"          {official_name}")

    print(f"          {total_points} track points")


def print_track_details(track: TrackInfo, repo: Path) -> None:
    elevation = ""

    if track.min_ele is not None and track.max_ele is not None:
        elevation = f"  ele {track.min_ele:.0f}-{track.max_ele:.0f} m"

    print(
        f"  {track.section_id:5} "
        f"{track.length_m / 1000.0:7.2f} km  "
        f"{track.points:5} pts"
        f"{elevation}"
    )
    print(f"        {track.label}")
    print(
        f"        start {track.start_lat:.6f}, {track.start_lon:.6f}  "
        f"end {track.end_lat:.6f}, {track.end_lon:.6f}"
    )
    print(f"        {track.path.relative_to(repo)}")


def print_connections(
    tracks: list[TrackInfo],
    threshold_m: float,
) -> None:
    endpoints = track_endpoints(tracks)
    connections = []

    for i in range(len(endpoints)):
        a = endpoints[i]

        for j in range(i + 1, len(endpoints)):
            b = endpoints[j]

            if a.track_index == b.track_index:
                continue

            distance = haversine_m(a.lat, a.lon, b.lat, b.lon)

            if distance <= threshold_m:
                connections.append((distance, a, b))

    connections.sort(
        key=lambda item: (
            tracks[item[1].track_index].section_id,
            tracks[item[2].track_index].section_id,
            item[0],
        )
    )

    if not connections:
        print("  no endpoint connections within threshold")

        return

    for distance, a, b in connections:
        print(
            f"  {endpoint_name(a, tracks):11} <-> "
            f"{endpoint_name(b, tracks):11}  "
            f"{distance:8.1f} m"
        )


def format_gap_distance(distance_m: float) -> str:
    if distance_m < 1000.0:
        return f"{distance_m:.1f} m"

    return f"{distance_m / 1000.0:.2f} km"


def print_gap_report(
    route_code: str,
    official_name: str | None,
    tracks: list[TrackInfo],
    topology: Topology,
) -> None:
    if topology.components <= 1:
        print(f"{route_code}: connected — no component gaps")

        return

    gaps = minimum_component_bridges(tracks, topology)
    title = official_name or route_code

    print(f"{route_code} — {title}")
    print(
        f"  components: {topology.components}; "
        f"minimum bridges required: {len(gaps)}"
    )

    for index, gap in enumerate(gaps, 1):
        print(
            f"  {index:2}. "
            f"C{gap.component_a + 1} <-> C{gap.component_b + 1}: "
            f"{endpoint_name(gap.endpoint_a, tracks)} <-> "
            f"{endpoint_name(gap.endpoint_b, tracks)} = "
            f"{format_gap_distance(gap.distance_m)}"
        )


def sweep_summary(
    groups: dict[str, list[TrackInfo]],
    thresholds: tuple[float, ...],
) -> None:
    print("Threshold sweep")
    print("===============")
    print(
        f"{'Threshold':>10}  "
        f"{'LINEAR':>6}  "
        f"{'NETWORK':>7}  "
        f"{'DISCONNECTED':>12}  "
        f"{'OTHER':>5}"
    )
    print("-" * 52)

    for threshold in thresholds:
        counts: dict[str, int] = defaultdict(int)

        for tracks in groups.values():
            topology = build_topology(tracks, threshold)
            counts[topology.classification] += 1

        print(
            f"{threshold:8.0f} m  "
            f"{counts['LINEAR']:6}  "
            f"{counts['NETWORK']:7}  "
            f"{counts['DISCONNECTED']:12}  "
            f"{counts['OTHER']:5}"
        )


def gap_bucket(distance_m: float) -> str:
    if distance_m <= 50:
        return "≤50m"
    if distance_m <= 100:
        return "51-100m"
    if distance_m <= 250:
        return "101-250m"
    if distance_m <= 500:
        return "251-500m"
    if distance_m <= 1000:
        return "501m-1km"
    if distance_m <= 2000:
        return "1-2km"

    return ">2km"


def print_primary_spine_summary(
    groups: dict[str, list[TrackInfo]],
    official_names: dict[str, str],
    threshold_m: float,
) -> None:
    print("Primary 'a' spine analysis")
    print("==========================")
    print(
        f"{'Group':8} {'a-trk':>5} {'seq':>4} {'skip':>4} "
        f"{'≤thr':>5} {'>thr':>5} {'orient':>6} "
        f"{'max gap':>10} {'topology':>12}"
    )
    print("-" * 78)

    all_gaps: list[SequentialGap] = []
    groups_with_primary = 0
    all_expected_orientation = 0
    all_orientation_anomalies = 0
    all_skipped_numbers = 0

    for route_code in sorted(groups):
        primary = primary_tracks(groups[route_code])

        if not primary:
            continue

        groups_with_primary += 1
        gaps = analyze_sequential_gaps(groups[route_code])
        all_gaps.extend(gaps)

        skipped = sum(1 for gap in gaps if gap.number_step != 1)
        within = sum(
            1 for gap in gaps
            if gap.directed_distance_m <= threshold_m
        )
        beyond = len(gaps) - within
        orientation_anomalies = sum(
            1 for gap in gaps
            if not gap.expected_orientation
        )
        all_skipped_numbers += skipped
        all_orientation_anomalies += orientation_anomalies
        all_expected_orientation += len(gaps) - orientation_anomalies

        max_gap = max(
            (gap.directed_distance_m for gap in gaps),
            default=0.0,
        )

        topology = build_topology(primary, threshold_m)

        print(
            f"{route_code:8} "
            f"{len(primary):5} "
            f"{len(gaps):4} "
            f"{skipped:4} "
            f"{within:5} "
            f"{beyond:5} "
            f"{orientation_anomalies:6} "
            f"{format_gap_distance(max_gap):>10} "
            f"{topology.classification:>12}"
        )

        if official_names.get(route_code):
            print(f"          {official_names[route_code]}")

    print()
    print("Primary-spine totals")
    print("--------------------")
    print(f"Groups with 'a' tracks:          {groups_with_primary}")
    print(f"Sequential transitions:          {len(all_gaps)}")
    print(f"Non-consecutive number jumps:    {all_skipped_numbers}")
    print(f"Expected end→start orientation:  {all_expected_orientation}")
    print(f"Orientation anomalies:           {all_orientation_anomalies}")

    buckets: dict[str, int] = defaultdict(int)

    for gap in all_gaps:
        buckets[gap_bucket(gap.directed_distance_m)] += 1

    print()
    print("Directed end→start gap distribution")
    print("-----------------------------------")

    for bucket in (
        "≤50m",
        "51-100m",
        "101-250m",
        "251-500m",
        "501m-1km",
        "1-2km",
        ">2km",
    ):
        print(f"{bucket:12} {buckets.get(bucket, 0):5}")

    if all_gaps:
        distances = [gap.directed_distance_m for gap in all_gaps]
        print()
        print(
            f"Median directed gap: "
            f"{format_gap_distance(statistics.median(distances))}"
        )
        print(
            f"Maximum directed gap: "
            f"{format_gap_distance(max(distances))}"
        )


def print_sequential_gap_report(
    route_code: str,
    official_name: str | None,
    tracks: list[TrackInfo],
    threshold_m: float,
) -> None:
    primary = primary_tracks(tracks)
    gaps = analyze_sequential_gaps(tracks)
    title = official_name or route_code

    print(f"{route_code} — {title}")
    print(f"  primary 'a' tracks: {len(primary)}")
    print(f"  expected transitions: {len(gaps)}")
    print(f"  reference threshold: {threshold_m:.1f} m")
    print()

    if not primary:
        print("  No numbered 'a' tracks found.")

        return

    if len(primary) == 1:
        print(f"  {primary[0].section_id}: only one primary track")

        return

    print(
        f"  {'transition':13} {'directed':>11} {'best':>11} "
        f"{'best endpoints':>17} {'status'}"
    )
    print("  " + "-" * 76)

    for gap in gaps:
        status: list[str] = []

        if gap.number_step != 1:
            status.append(f"NUMBER+{gap.number_step}")

        if gap.directed_distance_m > threshold_m:
            status.append("GAP")

        if not gap.expected_orientation:
            status.append("ORIENTATION?")

        if not status:
            status.append("OK")

        print(
            f"  {gap.previous.section_id:5} → "
            f"{gap.following.section_id:5} "
            f"{format_gap_distance(gap.directed_distance_m):>11} "
            f"{format_gap_distance(gap.best_distance_m):>11} "
            f"{gap.best_previous_side + '→' + gap.best_following_side:>17} "
            f"{', '.join(status)}"
        )


def print_orientation_summary(
    groups: dict[str, list[TrackInfo]],
    official_names: dict[str, str],
    threshold_m: float,
) -> None:
    print("Globally optimized primary-spine orientation")
    print("============================================")
    print(
        f"{'Group':8} {'a-trk':>5} {'rev':>4} {'seq':>4} "
        f"{'≤thr':>5} {'>thr':>5} {'>2km':>5} "
        f"{'max gap':>10} {'saved':>10}"
    )
    print("-" * 78)

    solutions: dict[str, OrientationSolution] = {}
    all_distances: list[float] = []
    all_original_total = 0.0
    all_optimized_total = 0.0
    total_tracks = 0
    total_reversed = 0
    total_jumps = 0

    for route_code in sorted(groups):
        solution = solve_primary_orientation(groups[route_code])
        solutions[route_code] = solution

        if not solution.tracks:
            continue

        distances = solution.transition_distances_m
        all_distances.extend(distances)
        all_original_total += solution.original_total_gap_m
        all_optimized_total += solution.total_gap_m
        total_tracks += len(solution.tracks)
        total_reversed += solution.reversed_count
        total_jumps += solution.number_jumps

        within = sum(
            1 for distance in distances
            if distance <= threshold_m
        )
        beyond = len(distances) - within
        large = sum(
            1 for distance in distances
            if distance > 2000.0
        )
        max_gap = max(distances, default=0.0)
        saved = max(
            0.0,
            solution.original_total_gap_m - solution.total_gap_m,
        )

        print(
            f"{route_code:8} "
            f"{len(solution.tracks):5} "
            f"{solution.reversed_count:4} "
            f"{len(distances):4} "
            f"{within:5} "
            f"{beyond:5} "
            f"{large:5} "
            f"{format_gap_distance(max_gap):>10} "
            f"{format_gap_distance(saved):>10}"
        )

        if official_names.get(route_code):
            print(f"          {official_names[route_code]}")

    print()
    print("Optimized orientation totals")
    print("----------------------------")
    print(f"Primary tracks:                  {total_tracks}")
    print(f"Tracks read reversed:            {total_reversed}")
    print(f"Sequential transitions:          {len(all_distances)}")
    print(f"Non-consecutive number jumps:    {total_jumps}")
    print(
        f"Original end→start gap sum:      "
        f"{format_gap_distance(all_original_total)}"
    )
    print(
        f"Optimized oriented gap sum:      "
        f"{format_gap_distance(all_optimized_total)}"
    )
    print(
        f"Gap distance removed by orient.: "
        f"{format_gap_distance(max(0.0, all_original_total - all_optimized_total))}"
    )

    buckets: dict[str, int] = defaultdict(int)

    for distance in all_distances:
        buckets[orientation_gap_bucket(distance)] += 1

    print()
    print("Optimized sequential gap distribution")
    print("-------------------------------------")

    for bucket in (
        "≤50m",
        "51-100m",
        "101-250m",
        "251-500m",
        "501m-1km",
        "1-2km",
        ">2km",
    ):
        print(f"{bucket:12} {buckets.get(bucket, 0):5}")

    if all_distances:
        print()
        print(
            "Median optimized gap: "
            f"{format_gap_distance(statistics.median(all_distances))}"
        )
        print(
            "Maximum optimized gap: "
            f"{format_gap_distance(max(all_distances))}"
        )


def print_orientation_details(
    route_code: str,
    official_name: str | None,
    tracks: list[TrackInfo],
    threshold_m: float,
) -> None:
    solution = solve_primary_orientation(tracks)
    title = official_name or route_code

    print()
    print(f"{route_code} — optimized orientation — {title}")
    print("-" * min(110, len(route_code) + len(title) + 28))

    if not solution.tracks:
        print("  No numbered 'a' tracks found.")
        return

    print(
        f"  primary tracks: {len(solution.tracks)}; "
        f"reversed: {solution.reversed_count}; "
        f"number jumps: {solution.number_jumps}"
    )
    print(
        f"  original end→start gap sum: "
        f"{format_gap_distance(solution.original_total_gap_m)}"
    )
    print(
        f"  optimized gap sum: "
        f"{format_gap_distance(solution.total_gap_m)}"
    )

    print()
    print("  Track orientation")
    print("  -----------------")

    for track, reversed_track in zip(
        solution.tracks,
        solution.reversed_flags,
    ):
        source_start = source_side_for_oriented_role(
            reversed_track,
            "start",
        )
        source_end = source_side_for_oriented_role(
            reversed_track,
            "end",
        )

        print(
            f"  {track.section_id:5} "
            f"{orientation_label(reversed_track):3}  "
            f"source {source_start}→{source_end}  "
            f"{track.label}"
        )

    if not solution.transition_distances_m:
        return

    print()
    print("  Optimized transitions")
    print("  ---------------------")

    for index, distance in enumerate(
        solution.transition_distances_m
    ):
        previous = solution.tracks[index]
        following = solution.tracks[index + 1]
        previous_reversed = solution.reversed_flags[index]
        following_reversed = solution.reversed_flags[index + 1]

        previous_source_side = source_side_for_oriented_role(
            previous_reversed,
            "end",
        )
        following_source_side = source_side_for_oriented_role(
            following_reversed,
            "start",
        )

        previous_number = primary_section_number(
            previous.section_id
        )
        following_number = primary_section_number(
            following.section_id
        )

        status: list[str] = []

        if (
            previous_number is not None
            and following_number is not None
            and following_number - previous_number != 1
        ):
            status.append(
                f"NUMBER+{following_number - previous_number}"
            )

        if distance > threshold_m:
            status.append("GAP")

        if distance > 2000.0:
            status.append("LARGE")

        if not status:
            status.append("OK")

        print(
            f"  {previous.section_id:5} "
            f"({orientation_label(previous_reversed)}) → "
            f"{following.section_id:5} "
            f"({orientation_label(following_reversed)})  "
            f"{format_gap_distance(distance):>10}  "
            f"source {previous_source_side}→{following_source_side}  "
            f"{', '.join(status)}"
        )


def print_large_optimized_gaps(
    groups: dict[str, list[TrackInfo]],
    official_names: dict[str, str],
    minimum_m: float,
) -> None:
    rows = []

    for route_code, tracks in groups.items():
        solution = solve_primary_orientation(tracks)

        for index, distance in enumerate(
            solution.transition_distances_m
        ):
            if distance <= minimum_m:
                continue

            previous = solution.tracks[index]
            following = solution.tracks[index + 1]
            previous_reversed = solution.reversed_flags[index]
            following_reversed = solution.reversed_flags[index + 1]

            rows.append(
                (
                    distance,
                    route_code,
                    official_names.get(route_code),
                    previous,
                    following,
                    previous_reversed,
                    following_reversed,
                )
            )

    rows.sort(key=lambda row: row[0], reverse=True)

    print()
    print(
        f"Optimized sequential gaps > "
        f"{format_gap_distance(minimum_m)}"
    )
    print("=" * 48)

    if not rows:
        print("None.")
        return

    for (
        distance,
        route_code,
        official_name,
        previous,
        following,
        previous_reversed,
        following_reversed,
    ) in rows:
        previous_source_side = source_side_for_oriented_role(
            previous_reversed,
            "end",
        )
        following_source_side = source_side_for_oriented_role(
            following_reversed,
            "start",
        )

        print(
            f"{route_code:8} "
            f"{previous.section_id:5}"
            f"({orientation_label(previous_reversed)}) → "
            f"{following.section_id:5}"
            f"({orientation_label(following_reversed)})  "
            f"{format_gap_distance(distance):>10}  "
            f"source {previous_source_side}→{following_source_side}"
        )

        if official_name:
            print(f"          {official_name}")


def format_optional_distance(
    distance_m: float | None,
) -> str:
    if distance_m is None:
        return "n/a"

    return format_gap_distance(distance_m)


def print_variant_bridge_result(
    route_code: str,
    official_name: str | None,
    previous: TrackInfo,
    following: TrackInfo,
    previous_reversed: bool,
    following_reversed: bool,
    direct_gap_m: float,
    result: VariantBridgeResult,
    join_threshold_m: float,
) -> None:
    print(
        f"{route_code:8} "
        f"{previous.section_id:5}"
        f"({orientation_label(previous_reversed)}) → "
        f"{following.section_id:5}"
        f"({orientation_label(following_reversed)})  "
        f"primary gap {format_gap_distance(direct_gap_m)}"
    )

    if official_name:
        print(f"          {official_name}")

    if not result.found:
        print(
            f"          NO VARIANT BRIDGE within "
            f"{join_threshold_m:.1f} m endpoint joins"
        )
        print(
            f"          nearest variant to source: "
            f"{result.nearest_source_label or 'n/a'} "
            f"({format_optional_distance(result.nearest_source_m)})"
        )
        print(
            f"          nearest variant to target: "
            f"{result.nearest_target_label or 'n/a'} "
            f"({format_optional_distance(result.nearest_target_m)})"
        )

        return

    chain = [
        (
            f"{step.track.section_id}"
            f"({'REV' if step.reversed_track else 'FWD'})"
        )
        for step in result.steps
        if step.kind == "track" and step.track is not None
    ]

    print(
        f"          FOUND {result.variant_track_count} official "
        f"variant track(s)"
    )
    print(
        f"          chain: "
        f"{' → '.join(chain) if chain else '(none)'}"
    )
    print(
        f"          official variant length: "
        f"{format_gap_distance(result.official_length_m)}"
    )
    print(
        f"          unmapped endpoint joins: "
        f"{format_gap_distance(result.connector_gap_m)}"
    )
    print(
        f"          total bridge traversal: "
        f"{format_gap_distance(result.total_cost_m)}"
    )

    print("          steps:")

    for step in result.steps:
        if step.kind == "connector":
            print(
                f"            GAP   "
                f"{step.from_label} → {step.to_label}: "
                f"{format_gap_distance(step.distance_m)}"
            )
        elif step.track is not None:
            print(
                f"            TRACK "
                f"{step.track.section_id} "
                f"{'REV' if step.reversed_track else 'FWD'}: "
                f"{format_gap_distance(step.distance_m)}  "
                f"{step.track.label}"
            )


def print_variant_bridge_report(
    groups: dict[str, list[TrackInfo]],
    official_names: dict[str, str],
    minimum_primary_gap_m: float,
    join_threshold_m: float,
) -> None:
    print("Official variant-bridge analysis")
    print("===============================")
    print(
        f"Primary-gap cutoff: {format_gap_distance(minimum_primary_gap_m)}"
    )
    print(
        f"Maximum unmapped endpoint join: "
        f"{join_threshold_m:.1f} m"
    )
    print(
        "Only existing non-'a' CNIG tracks may form a bridge; "
        "no geometry is invented."
    )

    cases = 0
    found = 0

    for route_code in sorted(groups):
        solution = solve_primary_orientation(groups[route_code])

        for index, direct_gap in enumerate(
            solution.transition_distances_m
        ):
            if direct_gap <= minimum_primary_gap_m:
                continue

            cases += 1
            previous = solution.tracks[index]
            following = solution.tracks[index + 1]
            previous_reversed = solution.reversed_flags[index]
            following_reversed = solution.reversed_flags[index + 1]

            result = search_variant_bridge(
                groups[route_code],
                previous,
                previous_reversed,
                following,
                following_reversed,
                join_threshold_m,
            )

            if result.found:
                found += 1

            print()
            print_variant_bridge_result(
                route_code,
                official_names.get(route_code),
                previous,
                following,
                previous_reversed,
                following_reversed,
                direct_gap,
                result,
                join_threshold_m,
            )

    print()
    print("Variant-bridge totals")
    print("---------------------")
    print(f"Large primary gaps examined:    {cases}")
    print(f"Official variant bridges found: {found}")
    print(f"Still unresolved:               {cases - found}")


def print_cross_group_bridge_result(
    route_code: str,
    official_name: str | None,
    previous: TrackInfo,
    following: TrackInfo,
    previous_reversed: bool,
    following_reversed: bool,
    direct_gap_m: float,
    result: CrossGroupBridgeResult,
    join_threshold_m: float,
) -> None:
    print(
        f"{route_code:8} "
        f"{previous.section_id:5}"
        f"({orientation_label(previous_reversed)}) → "
        f"{following.section_id:5}"
        f"({orientation_label(following_reversed)})  "
        f"primary gap {format_gap_distance(direct_gap_m)}"
    )

    if official_name:
        print(f"          {official_name}")

    if not result.found:
        print(
            f"          NO CROSS-GROUP BRIDGE within "
            f"{format_gap_distance(result.search_limit_m)} traversal"
        )
        print(
            f"          nearest official endpoint to source: "
            f"{result.nearest_source_label or 'n/a'} "
            f"({format_optional_distance(result.nearest_source_m)})"
        )
        print(
            f"          nearest official endpoint to target: "
            f"{result.nearest_target_label or 'n/a'} "
            f"({format_optional_distance(result.nearest_target_m)})"
        )
        return

    chain = [
        (
            f"{step.track.route_code}:{step.track.section_id}"
            f"({'REV' if step.reversed_track else 'FWD'})"
        )
        for step in result.steps
        if step.kind == "track" and step.track is not None
    ]

    print(
        f"          FOUND {result.track_count} official track(s) "
        f"across group(s): "
        f"{', '.join(result.route_codes) if result.route_codes else 'n/a'}"
    )
    print(
        f"          chain: "
        f"{' → '.join(chain) if chain else '(none)'}"
    )
    print(
        f"          official track length: "
        f"{format_gap_distance(result.official_length_m)}"
    )
    print(
        f"          unmapped endpoint joins: "
        f"{format_gap_distance(result.connector_gap_m)}"
    )
    print(
        f"          total bridge traversal: "
        f"{format_gap_distance(result.total_cost_m)}"
    )
    print(
        f"          search ceiling: "
        f"{format_gap_distance(result.search_limit_m)}"
    )

    print("          steps:")

    for step in result.steps:
        if step.kind == "connector":
            print(
                f"            GAP   "
                f"{step.from_label} → {step.to_label}: "
                f"{format_gap_distance(step.distance_m)}"
            )
        elif step.track is not None:
            print(
                f"            TRACK "
                f"{step.track.route_code}:{step.track.section_id} "
                f"{'REV' if step.reversed_track else 'FWD'}: "
                f"{format_gap_distance(step.distance_m)}  "
                f"{step.track.label}"
            )


def print_cross_group_bridge_report(
    selected_groups: dict[str, list[TrackInfo]],
    all_groups: dict[str, list[TrackInfo]],
    official_names: dict[str, str],
    minimum_primary_gap_m: float,
    join_threshold_m: float,
    max_factor: float,
) -> None:
    all_tracks = [
        track
        for route_code in sorted(all_groups)
        for track in all_groups[route_code]
    ]

    print("Building global CNIG endpoint graph...")
    graph = build_global_track_graph(
        all_tracks,
        join_threshold_m,
    )

    connector_edges = sum(
        len(neighbors)
        for neighbors in graph.connector_neighbors.values()
    ) // 2

    print()
    print("Cross-group official bridge analysis")
    print("====================================")
    print(
        f"Global official tracks: {len(graph.tracks)}"
    )
    print(
        f"Endpoint connector pairs ≤ {join_threshold_m:.1f} m: "
        f"{connector_edges}"
    )
    print(
        f"Primary-gap cutoff: "
        f"{format_gap_distance(minimum_primary_gap_m)}"
    )
    print(
        f"Bridge ceiling: max(100 km, primary gap × {max_factor:g})"
    )
    print(
        "Complete official tracks may be shared across named Caminos; "
        "no route geometry is invented."
    )

    cases = 0
    found = 0

    for route_code in sorted(selected_groups):
        solution = solve_primary_orientation(
            selected_groups[route_code]
        )

        for index, direct_gap in enumerate(
            solution.transition_distances_m
        ):
            if direct_gap <= minimum_primary_gap_m:
                continue

            cases += 1
            previous = solution.tracks[index]
            following = solution.tracks[index + 1]
            previous_reversed = solution.reversed_flags[index]
            following_reversed = solution.reversed_flags[index + 1]

            search_limit = max(
                100_000.0,
                direct_gap * max_factor,
            )

            result = search_cross_group_bridge(
                graph,
                previous,
                previous_reversed,
                following,
                following_reversed,
                join_threshold_m,
                search_limit,
            )

            if result.found:
                found += 1

            print()
            print_cross_group_bridge_result(
                route_code,
                official_names.get(route_code),
                previous,
                following,
                previous_reversed,
                following_reversed,
                direct_gap,
                result,
                join_threshold_m,
            )

    print()
    print("Cross-group bridge totals")
    print("-------------------------")
    print(f"Large primary gaps examined:     {cases}")
    print(f"Official cross-group bridges:    {found}")
    print(f"Still unresolved:                {cases - found}")


def print_place_graph_summary(
    graph: PlaceGraph,
) -> None:
    pair_groups: dict[tuple[str, str], set[str]] = defaultdict(set)
    pair_tracks: dict[tuple[str, str], int] = defaultdict(int)

    for edge in graph.parseable_tracks:
        pair = (edge.from_key, edge.to_key)
        pair_groups[pair].add(edge.track.route_code)
        pair_tracks[pair] += 1

    duplicate_pairs = sum(
        1 for count in pair_tracks.values()
        if count > 1
    )
    cross_group_pairs = sum(
        1 for groups in pair_groups.values()
        if len(groups) > 1
    )
    self_loops = sum(
        1 for edge in graph.parseable_tracks
        if edge.from_key == edge.to_key
    )

    print("Logical CNIG place graph")
    print("========================")
    print(f"Tracks parseable as FROM-TO: {len(graph.parseable_tracks)}")
    print(f"Tracks without FROM-TO:      {len(graph.unparseable_tracks)}")
    print(f"Unique normalized places:    {len(graph.display_names)}")
    print(f"Unique directed place pairs: {len(pair_tracks)}")
    print(f"Duplicate directed pairs:    {duplicate_pairs}")
    print(f"Pairs shared across groups:  {cross_group_pairs}")
    print(f"Semantic self-loops:         {self_loops}")

    if graph.unparseable_tracks:
        print()
        print("Unparseable filename labels")
        print("---------------------------")

        for track in graph.unparseable_tracks[:20]:
            print(
                f"{track.route_code}:{track.section_id}  "
                f"{track.label}"
            )

        if len(graph.unparseable_tracks) > 20:
            print(
                f"... and {len(graph.unparseable_tracks) - 20} more"
            )


def print_semantic_bridge_case(
    route_code: str,
    official_name: str | None,
    previous: TrackInfo,
    following: TrackInfo,
    previous_reversed: bool,
    following_reversed: bool,
    direct_gap_m: float,
    result: SemanticBridgeResult,
) -> None:
    previous_places = parse_track_places(previous)
    following_places = parse_track_places(following)

    previous_desc = (
        f"{display_place_name(previous_places.from_raw)} → "
        f"{display_place_name(previous_places.to_raw)}"
        if previous_places is not None
        else previous.label
    )
    following_desc = (
        f"{display_place_name(following_places.from_raw)} → "
        f"{display_place_name(following_places.to_raw)}"
        if following_places is not None
        else following.label
    )

    print(
        f"{route_code:8} "
        f"{previous.section_id:5}"
        f"({orientation_label(previous_reversed)}) → "
        f"{following.section_id:5}"
        f"({orientation_label(following_reversed)})  "
        f"geometry gap {format_gap_distance(direct_gap_m)}"
    )

    if official_name:
        print(f"          {official_name}")

    print(
        f"          previous: {previous_desc}"
    )
    print(
        f"          following: {following_desc}"
    )
    print(
        f"          logical missing link: "
        f"{result.source_place} → {result.target_place}"
    )

    if not result.found:
        print(
            f"          NO SEMANTIC BRIDGE within "
            f"{result.max_hops} tracks / "
            f"{format_gap_distance(result.search_limit_m)} official length"
        )
        print(
            f"          outgoing official tracks from "
            f"{result.source_place}: {result.outgoing_from_source}"
        )
        print(
            f"          incoming official tracks to "
            f"{result.target_place}: {result.incoming_to_target}"
        )
        return

    if result.same_place and not result.chain:
        print(
            "          SAME LOGICAL PLACE — no intervening official "
            "track is required"
        )
        print(
            f"          physical endpoint separation remains: "
            f"{format_gap_distance(result.max_connector_gap_m)}"
        )
        return

    print(
        f"          SEMANTIC BRIDGE FOUND: "
        f"{len(result.chain)} official track(s)"
    )

    chain_labels = [
        (
            f"{edge.track.route_code}:{edge.track.section_id} "
            f"[{display_place_name(edge.from_raw)} → "
            f"{display_place_name(edge.to_raw)}]"
        )
        for edge in result.chain
    ]
    print(
        f"          chain: {' → '.join(chain_labels)}"
    )
    print(
        f"          official bridge length: "
        f"{format_gap_distance(result.official_length_m)}"
    )
    print(
        f"          physical connector gaps total: "
        f"{format_gap_distance(result.total_connector_gap_m)}"
    )
    print(
        f"          largest physical connector gap: "
        f"{format_gap_distance(result.max_connector_gap_m)}"
    )

    print("          geometry:")

    for index, edge in enumerate(result.chain):
        connector_before = result.connector_distances_m[index]
        reversed_track = result.geometry_reversed_flags[index]

        if index == 0:
            from_label = (
                f"{previous.route_code}:{previous.section_id}"
            )
        else:
            prior = result.chain[index - 1].track
            from_label = (
                f"{prior.route_code}:{prior.section_id}"
            )

        print(
            f"            GAP   {from_label} → "
            f"{edge.track.route_code}:{edge.track.section_id}: "
            f"{format_gap_distance(connector_before)}"
        )
        print(
            f"            TRACK {edge.track.route_code}:"
            f"{edge.track.section_id} "
            f"{'REV' if reversed_track else 'FWD'}  "
            f"{display_place_name(edge.from_raw)} → "
            f"{display_place_name(edge.to_raw)}  "
            f"{format_gap_distance(edge.track.length_m)}"
        )

    final_gap = result.connector_distances_m[-1]
    last = result.chain[-1].track

    print(
        f"            GAP   {last.route_code}:{last.section_id} → "
        f"{following.route_code}:{following.section_id}: "
        f"{format_gap_distance(final_gap)}"
    )


def print_semantic_bridge_report(
    selected_groups: dict[str, list[TrackInfo]],
    all_groups: dict[str, list[TrackInfo]],
    official_names: dict[str, str],
    minimum_primary_gap_m: float,
    max_hops: int,
    max_factor: float,
) -> None:
    all_tracks = [
        track
        for route_code in sorted(all_groups)
        for track in all_groups[route_code]
    ]
    graph = build_place_graph(all_tracks)

    print("Semantic place-graph bridge analysis")
    print("====================================")
    print(f"Global official tracks: {len(all_tracks)}")
    print(f"Normalized places:      {len(graph.display_names)}")
    print(
        f"Primary-gap cutoff:     "
        f"{format_gap_distance(minimum_primary_gap_m)}"
    )
    print(f"Maximum semantic hops:  {max_hops}")
    print(
        f"Bridge length ceiling:  max(100 km, primary gap × "
        f"{max_factor:g})"
    )
    print(
        "Place continuity comes from CNIG filename FROM-TO labels. "
        "Physical endpoint gaps are measured, never filled."
    )

    cases = 0
    found = 0
    same_place = 0

    for route_code in sorted(selected_groups):
        solution = solve_primary_orientation(
            selected_groups[route_code]
        )

        for index, direct_gap in enumerate(
            solution.transition_distances_m
        ):
            if direct_gap <= minimum_primary_gap_m:
                continue

            cases += 1
            previous = solution.tracks[index]
            following = solution.tracks[index + 1]
            previous_reversed = solution.reversed_flags[index]
            following_reversed = solution.reversed_flags[index + 1]
            max_length = max(
                100_000.0,
                direct_gap * max_factor,
            )

            result = search_semantic_bridge(
                graph,
                previous,
                previous_reversed,
                following,
                following_reversed,
                max_hops,
                max_length,
            )

            if result.found:
                found += 1

            if result.same_place:
                same_place += 1

            print()
            print_semantic_bridge_case(
                route_code,
                official_names.get(route_code),
                previous,
                following,
                previous_reversed,
                following_reversed,
                direct_gap,
                result,
            )

    print()
    print("Semantic bridge totals")
    print("----------------------")
    print(f"Large primary gaps examined: {cases}")
    print(f"Semantic bridges found:      {found}")
    print(f"Same-place continuities:     {same_place}")
    print(f"Still unresolved:            {cases - found}")


def display_place_key(
    key: str,
    graph: PlaceGraph,
) -> str:
    return graph.display_names.get(key, key)


def print_semantic_topology_summary(
    groups: dict[str, list[TrackInfo]],
    official_names: dict[str, str],
) -> None:
    print("Semantic route topology")
    print("=======================")
    print(
        f"{'Group':8} {'class':>12} {'trk':>4} {'places':>6} "
        f"{'comp':>4} {'src':>3} {'sink':>4} "
        f"{'branch':>6} {'merge':>5} {'parallel':>8} {'cycles':>6}"
    )
    print("-" * 88)

    counts: dict[str, int] = defaultdict(int)

    for route_code in sorted(groups):
        topology = build_semantic_topology(groups[route_code])
        counts[topology.classification] += 1

        print(
            f"{route_code:8} "
            f"{topology.classification:>12} "
            f"{topology.tracks:4} "
            f"{topology.places:6} "
            f"{topology.weak_components:4} "
            f"{len(topology.sources):3} "
            f"{len(topology.sinks):4} "
            f"{len(topology.branch_places):6} "
            f"{len(topology.merge_places):5} "
            f"{topology.parallel_pairs:8} "
            f"{topology.cyclic_sccs:6}"
        )

        if official_names.get(route_code):
            print(f"          {official_names[route_code]}")

    print()
    print("Semantic topology classes")
    print("-------------------------")

    for classification in (
        "LINEAR",
        "NETWORK",
        "DISCONNECTED",
        "OTHER",
        "EMPTY",
    ):
        print(
            f"{classification:12} "
            f"{counts.get(classification, 0):3}"
        )


def print_semantic_topology_details(
    route_code: str,
    tracks: list[TrackInfo],
    official_name: str | None,
) -> None:
    graph = build_place_graph(tracks)
    topology = build_semantic_topology(tracks)
    pair_groups = semantic_pair_groups(tracks)

    print()
    print(
        f"{route_code} — semantic topology — "
        f"{official_name or route_code}"
    )
    print("-" * 78)
    print(
        f"  class: {topology.classification}; "
        f"tracks: {topology.tracks}; "
        f"places: {topology.places}; "
        f"components: {topology.weak_components}"
    )

    def print_places(
        title: str,
        values: list[str],
    ) -> None:
        print(f"  {title}: {len(values)}")

        for key in values:
            print(
                f"    {display_place_key(key, graph)} "
                f"[{key}]"
            )

    print_places("sources", topology.sources)
    print_places("sinks", topology.sinks)
    print_places(
        "branch places",
        topology.branch_places,
    )
    print_places(
        "merge places",
        topology.merge_places,
    )

    if topology.weak_components > 1:
        print("  weak components:")

        for index, component in enumerate(
            topology.components,
            1,
        ):
            component_sources = sorted(
                key
                for key in component
                if key in topology.sources
            )
            component_sinks = sorted(
                key
                for key in component
                if key in topology.sinks
            )

            source_text = ", ".join(
                display_place_key(key, graph)
                for key in component_sources
            ) or "-"
            sink_text = ", ".join(
                display_place_key(key, graph)
                for key in component_sinks
            ) or "-"

            print(
                f"    C{index}: {len(component)} places; "
                f"sources={source_text}; sinks={sink_text}"
            )

    parallel = [
        (pair, edges)
        for pair, edges in pair_groups.items()
        if len(edges) > 1
    ]

    print(f"  parallel FROM→TO pairs: {len(parallel)}")

    for (from_key, to_key), edges in sorted(
        parallel,
        key=lambda item: item[0],
    ):
        print(
            f"    {display_place_key(from_key, graph)} → "
            f"{display_place_key(to_key, graph)}: "
            f"{len(edges)} tracks"
        )

        for edge in edges:
            print(
                f"      {edge.track.route_code}:"
                f"{edge.track.section_id}  "
                f"{edge.track.length_m / 1000.0:.2f} km"
            )

    print("  logical edges:")

    for edge in sorted(
        graph.parseable_tracks,
        key=lambda item: (
            item.from_key,
            item.to_key,
            item.track.route_code,
            item.track.section_id,
        ),
    ):
        print(
            f"    {edge.track.route_code}:{edge.track.section_id}  "
            f"{display_place_name(edge.from_raw)} → "
            f"{display_place_name(edge.to_raw)}  "
            f"{edge.track.length_m / 1000.0:.2f} km"
        )


def print_duplicate_geometry_audit(
    tracks: list[TrackInfo],
) -> None:
    graph = build_place_graph(tracks)
    pair_groups = semantic_pair_groups(tracks)
    duplicate_pairs = [
        (pair, edges)
        for pair, edges in pair_groups.items()
        if len(edges) > 1
    ]

    print("Duplicate FROM→TO geometry audit")
    print("================================")
    print(
        f"Repeated normalized directed pairs: "
        f"{len(duplicate_pairs)}"
    )
    print(
        "Classification thresholds: IDENTICAL coordinates; "
        "SAME ≤30 m / ≤2%; NEAR ≤100 m / ≤5%; "
        "otherwise ALTERNATIVE."
    )
    print(
        "KML point order is compared both forward and reversed. "
        "Raw files are never changed."
    )

    summary: dict[str, int] = defaultdict(int)

    for (from_key, to_key), edges in sorted(
        duplicate_pairs,
        key=lambda item: item[0],
    ):
        pairwise: list[
            tuple[
                TrackPlaces,
                TrackPlaces,
                GeometryComparison,
            ]
        ] = []

        for i in range(len(edges)):
            for j in range(i + 1, len(edges)):
                comparison = compare_track_geometry(
                    edges[i].track,
                    edges[j].track,
                )
                pairwise.append(
                    (
                        edges[i],
                        edges[j],
                        comparison,
                    )
                )

        pair_class = duplicate_pair_classification(
            [
                comparison
                for _, _, comparison in pairwise
            ]
        )
        summary[pair_class] += 1

        print()
        print(
            f"{display_place_key(from_key, graph)} → "
            f"{display_place_key(to_key, graph)}  "
            f"[{pair_class}]  {len(edges)} tracks"
        )

        for edge in edges:
            print(
                f"  TRACK {edge.track.route_code}:"
                f"{edge.track.section_id}  "
                f"{edge.track.length_m / 1000.0:.2f} km  "
                f"{edge.track.points} pts  "
                f"{edge.track.label}"
            )

        for a, b, comparison in pairwise:
            print(
                f"  CMP   {a.track.route_code}:{a.track.section_id} "
                f"vs {b.track.route_code}:{b.track.section_id}: "
                f"{comparison.classification}; "
                f"orientation={comparison.orientation}; "
                f"Δlen={comparison.length_difference_pct:.2f}%; "
                f"mean={comparison.mean_separation_m:.1f} m; "
                f"max={comparison.max_separation_m:.1f} m"
            )

    print()
    print("Duplicate pair classes")
    print("----------------------")

    for classification in (
        "IDENTICAL",
        "SAME",
        "NEAR",
        "ALTERNATIVE",
    ):
        print(
            f"{classification:12} "
            f"{summary.get(classification, 0):3}"
        )


def load_tracks(
    paths: list[Path],
) -> tuple[list[TrackInfo], list[str]]:
    tracks: list[TrackInfo] = []
    errors: list[str] = []

    for path in paths:
        try:
            tracks.append(read_track(path))
        except ValueError as exc:
            errors.append(str(exc))

    return tracks, errors


def main() -> int:
    args = parse_args()

    if args.threshold <= 0:
        print("ERROR: --threshold must be > 0.", file=sys.stderr)

        return 2

    if args.large_gaps is not None and args.large_gaps <= 0:
        print("ERROR: --large-gaps must be > 0.", file=sys.stderr)

        return 2

    if args.large_gaps is not None and not args.solve_orientation:
        print(
            "ERROR: --large-gaps requires --solve-orientation.",
            file=sys.stderr,
        )

        return 2

    if args.variant_bridges is not None and args.variant_bridges <= 0:
        print("ERROR: --variant-bridges must be > 0.", file=sys.stderr)

        return 2

    if (
        args.cross_group_bridges is not None
        and args.cross_group_bridges <= 0
    ):
        print(
            "ERROR: --cross-group-bridges must be > 0.",
            file=sys.stderr,
        )
        return 2

    if args.bridge_max_factor <= 0:
        print(
            "ERROR: --bridge-max-factor must be > 0.",
            file=sys.stderr,
        )
        return 2

    if (
        args.semantic_bridges is not None
        and args.semantic_bridges <= 0
    ):
        print(
            "ERROR: --semantic-bridges must be > 0.",
            file=sys.stderr,
        )
        return 2

    if args.semantic_max_hops <= 0:
        print(
            "ERROR: --semantic-max-hops must be > 0.",
            file=sys.stderr,
        )
        return 2

    if args.semantic_max_factor <= 0:
        print(
            "ERROR: --semantic-max-factor must be > 0.",
            file=sys.stderr,
        )
        return 2

    repo = require_repo_root()
    raw_root = repo / RAW_REL
    official_names: dict[str, str] = {}

    if not args.offline:
        print("Reading official Camino names from CNIG...")

        try:
            official_names = parse_group_names(fetch_cnig_index())
        except (
            urllib.error.URLError,
            TimeoutError,
            OSError,
        ) as exc:
            print(
                f"WARNING: Could not read CNIG names: {exc}\n"
                "Continuing with local route codes only.",
                file=sys.stderr,
            )

    all_paths = sorted(raw_root.glob("*/*.kml"))
    selected_paths = all_paths

    if args.group:
        wanted = args.group.lower()
        selected_paths = [
            path
            for path in all_paths
            if path.parent.name.lower() == wanted
        ]

        if not selected_paths:
            print(
                f"ERROR: No route group {args.group!r} found.",
                file=sys.stderr,
            )

            return 1

    load_all_for_cross_group = args.cross_group_bridges is not None
    load_all_for_semantic = args.semantic_bridges is not None
    load_all_for_global_analysis = (
        load_all_for_cross_group
        or load_all_for_semantic
        or args.place_graph
    )
    paths = all_paths if load_all_for_global_analysis else selected_paths

    print("Reading local CNIG tracks...")
    tracks, errors = load_tracks(paths)

    if errors:
        print("\nERRORS\n------")

        for error in errors:
            print(error)

        return 1

    groups: dict[str, list[TrackInfo]] = defaultdict(list)

    for track in tracks:
        groups[track.route_code].append(track)

    for group_tracks in groups.values():
        group_tracks.sort(
            key=lambda track: (
                track.section_id,
                track.label,
            )
        )

    selected_groups = groups

    if args.group and (
        load_all_for_cross_group
        or load_all_for_semantic
    ):
        selected_groups = {
            route_code: group_tracks
            for route_code, group_tracks in groups.items()
            if route_code.lower() == args.group.lower()
        }

    if args.place_graph:
        print()
        all_tracks = [
            track
            for route_code in sorted(groups)
            for track in groups[route_code]
        ]
        print_place_graph_summary(
            build_place_graph(all_tracks)
        )
        return 0

    if args.semantic_topology:
        print()
        print_semantic_topology_summary(
            selected_groups,
            official_names,
        )

        if args.group or args.details:
            for route_code in sorted(selected_groups):
                print_semantic_topology_details(
                    route_code,
                    selected_groups[route_code],
                    official_names.get(route_code),
                )

        return 0

    if args.duplicate_geometry:
        print()
        selected_tracks = [
            track
            for route_code in sorted(selected_groups)
            for track in selected_groups[route_code]
        ]
        print_duplicate_geometry_audit(
            selected_tracks,
        )
        return 0

    if args.threshold_sweep:
        print()
        sweep_summary(groups, DEFAULT_SWEEP_THRESHOLDS)

        return 0

    if args.semantic_bridges is not None:
        print()
        print_semantic_bridge_report(
            selected_groups,
            groups,
            official_names,
            args.semantic_bridges,
            args.semantic_max_hops,
            args.semantic_max_factor,
        )
        return 0

    if args.cross_group_bridges is not None:
        print()
        print_cross_group_bridge_report(
            selected_groups,
            groups,
            official_names,
            args.cross_group_bridges,
            args.threshold,
            args.bridge_max_factor,
        )

        return 0

    if args.variant_bridges is not None:
        print()
        print_variant_bridge_report(
            groups,
            official_names,
            args.variant_bridges,
            args.threshold,
        )

        return 0

    if args.solve_orientation:
        print()
        print_orientation_summary(
            groups,
            official_names,
            args.threshold,
        )

        if args.group or args.details:
            for route_code in sorted(groups):
                print_orientation_details(
                    route_code,
                    official_names.get(route_code),
                    groups[route_code],
                    args.threshold,
                )

        if args.large_gaps is not None:
            print_large_optimized_gaps(
                groups,
                official_names,
                args.large_gaps,
            )

        return 0

    if args.primary_spine:
        print()
        print_primary_spine_summary(
            groups,
            official_names,
            args.threshold,
        )

        if not args.sequential_gaps:
            return 0

    if args.sequential_gaps:
        print()
        print("Sequential primary-spine gaps")
        print("=============================")

        for index, route_code in enumerate(sorted(groups)):
            if index:
                print()

            print_sequential_gap_report(
                route_code,
                official_names.get(route_code),
                groups[route_code],
                args.threshold,
            )

        return 0

    topologies = {
        route_code: build_topology(group_tracks, args.threshold)
        for route_code, group_tracks in groups.items()
    }

    visible_groups = groups

    if args.classification:
        visible_groups = {
            route_code: group_tracks
            for route_code, group_tracks in groups.items()
            if topologies[route_code].classification
            == args.classification
        }

    local_codes = set(topologies)
    named_local = sum(
        1
        for code in local_codes
        if code in official_names
    )

    print()
    print("CNIG topology inventory")
    print("=======================")
    print(f"Tracks read:       {len(tracks)}")
    print(f"Route groups:      {len(topologies)}")
    print(f"Endpoint threshold:{args.threshold:.1f} m")

    if args.offline:
        print("Official names:    offline")
    else:
        print(
            f"Official names:    "
            f"{named_local}/{len(local_codes)} local groups matched"
        )

    print()

    class_counts: dict[str, int] = defaultdict(int)

    for topology in topologies.values():
        class_counts[topology.classification] += 1

    print("Classification")
    print("--------------")

    for name in ("LINEAR", "NETWORK", "DISCONNECTED", "OTHER"):
        print(f"{name:12} {class_counts.get(name, 0):3}")

    print()
    print("Groups")
    print("------")

    for route_code in sorted(visible_groups):
        print_group_summary(
            route_code,
            official_names.get(route_code),
            visible_groups[route_code],
            topologies[route_code],
        )

    if args.details:
        for route_code in sorted(visible_groups):
            print()
            title = official_names.get(route_code, route_code)
            print(f"{route_code} — {title}")
            print(
                "-" * min(
                    100,
                    len(route_code) + len(title) + 3,
                )
            )

            for track in visible_groups[route_code]:
                print_track_details(track, repo)

    if args.connections:
        for route_code in sorted(visible_groups):
            print()
            print(
                f"{route_code} endpoint connections "
                f"≤ {args.threshold:.1f} m"
            )
            print("-" * 55)
            print_connections(
                visible_groups[route_code],
                args.threshold,
            )

    if args.gaps:
        disconnected = [
            code
            for code in sorted(visible_groups)
            if topologies[code].components > 1
        ]

        print()
        print("Component gap diagnosis")
        print("=======================")

        if not disconnected:
            print("No disconnected groups in the current selection.")
        else:
            for index, route_code in enumerate(disconnected):
                if index:
                    print()

                print_gap_report(
                    route_code,
                    official_names.get(route_code),
                    visible_groups[route_code],
                    topologies[route_code],
                )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
