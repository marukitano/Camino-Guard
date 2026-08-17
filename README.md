# Camino Guard

Camino Guard is a Camino de Santiago companion app project for the Pebble Time 2.

The watch is intended to act as a lightweight hiking instrument rather than a map:
current progress on the Camino, distance to the next place and daily destination,
elevation profile, walking statistics, and off-route warnings. GPS comes from the
connected phone.

The project is currently starting with the offline route-data pipeline.

## Repository layout

```text
Camino-Guard/
├── data/
│   ├── raw/
│   │   └── cnig/          Official CNIG/FEAACS KML source files
│   └── processed/         Generated Camino Guard data (later)
└── tools/
    └── download_cnig.py   Download/update the official source tracks
```

`data/raw/cnig/` is source material. Do not manually edit, simplify, merge, or
otherwise transform these KML files. Processing will always write separate output
under `data/processed/`.

The only intended changes to `data/raw/cnig/` are synchronization with newer
official source files published by CNIG.

## Camino track source

The raw route data comes from the Spanish Instituto Geográfico Nacional (IGN) /
Centro Nacional de Información Geográfica (CNIG):

https://centrodedescargas.cnig.es/CentroDescargas/loadCamSan

The routes are supplied by the Federación Española de Asociaciones de Amigos del
Camino de Santiago (FEAACS).

Attribution for the unchanged source data:

> Rutas de Caminos de Santiago 2020-2026 CC-BY 4.0 FEAACS

CNIG specifies the following attribution for products derived from these tracks:

> Obra derivada de Rutas de Caminos de Santiago 2020-2026 CC-BY 4.0 FEAACS

The initial Camino Guard import contains 1,073 KML route/variant files. The
downloader always reads the current CNIG index, so that number may change later.

See [`data/raw/cnig/README.md`](data/raw/cnig/README.md) for details about the
raw dataset.

## Downloading or repairing missing tracks

A CNIG account is used by the downloader. Credentials are requested interactively
and are not written to disk.

From the repository root:

```bash
python3 tools/download_cnig.py
```

Normal mode validates existing local KML files and downloads only missing or
invalid tracks. This is also the mode to use after an interrupted initial
download.

## Checking for CNIG updates

To compare all local tracks with the current files published by CNIG:

```bash
python3 tools/download_cnig.py --update
```

Update mode:

1. reads the current official CNIG Camino index;
2. downloads each current KML to a temporary `.part` file;
3. validates its coordinates/elevation data;
4. compares SHA-256 with the local source file;
5. replaces the local file only when the official content changed;
6. downloads newly published tracks;
7. updates `data/raw/cnig/manifest.json`.

Because CNIG does not publish content hashes in the Camino overview, detecting a
content change reliably requires downloading the current KML before comparing it.

If a track disappears from the current CNIG index, the downloader reports it as
stale but **does not delete it automatically**.

The downloader is resumable. Completed files remain valid if the process is
interrupted and `.part` files are never accepted as source data.


## Inspecting the raw route structure

Before merging official segments into long Camino routes, inspect the CNIG groups
and their variants:

```bash
python3 tools/analyze_cnig.py
```

For a detailed view of one route group, for example the Camino Mozárabe from
Granada:

```bash
python3 tools/analyze_cnig.py --group ES10c --details --connections
```

The analyzer only reads `data/raw/cnig/`. It reports route groups, segment IDs,
track lengths, endpoint coordinates and likely endpoint connections. It does not
create or modify processed route data.


### Topology classification

The analyzer also builds an undirected endpoint graph for every CNIG route group.
By default, track endpoints within 100 m are treated as the same graph node.
Because topology ignores track direction, reversed KML tracks do not break the
connectivity analysis.

```bash
python3 tools/analyze_cnig.py
```

The summary classifies groups as:

- `LINEAR` — one connected chain, two terminal nodes, no branches/cycles;
- `NETWORK` — connected, but contains branches and/or alternative-path cycles;
- `DISCONNECTED` — more than one endpoint-connected component;
- `OTHER` — connected but not covered by the simple classes above.

Filter a class with:

```bash
python3 tools/analyze_cnig.py --class LINEAR
python3 tools/analyze_cnig.py --class NETWORK
python3 tools/analyze_cnig.py --class DISCONNECTED
```

The analyzer reads the public CNIG index to display the official Camino group
names. Use `--offline` when only the local route codes are needed.


### Threshold sweep and gap diagnosis

A fixed endpoint threshold can be too strict for some CNIG segments and too
permissive for others. Use the threshold sweep before choosing any automatic
merging rule:

```bash
python3 tools/analyze_cnig.py --threshold-sweep
```

This compares the topology classifications at 50, 100, 250, 500 and 1000 m.

For groups that are disconnected at the selected threshold, inspect the minimum
set of endpoint gaps that would geometrically connect all components:

```bash
python3 tools/analyze_cnig.py --gaps
```

For a single Camino:

```bash
python3 tools/analyze_cnig.py --group ES08a --gaps
```

The gap report uses a minimum-spanning-tree calculation across disconnected
components. It is diagnostic only: it does **not** join tracks or modify raw data.
Large thresholds are not automatically considered safe, because unrelated route
branches can pass close to each other in towns or shared trail corridors.


### Primary route spine and sequential gaps

CNIG section IDs often use an `a` suffix for the numbered primary sequence
(`01a`, `02a`, `03a`, ...), while additional letters may represent alternate
sections. Camino Guard does not assume this is universally correct without
testing it.

Analyze the candidate `a` spine across all route groups:

```bash
python3 tools/analyze_cnig.py --primary-spine
```

The report shows how many expected sequential transitions stay within the
reference threshold, whether section numbers skip, and whether endpoint geometry
suggests a reversed KML orientation.

Inspect the real gaps for one route group:

```bash
python3 tools/analyze_cnig.py --group ES08a --sequential-gaps
```

For every expected transition, the analyzer reports the normal `end -> start`
distance and the closest of all four endpoint combinations. A gap remains a gap:
the analyzer never draws or inserts synthetic route geometry. This is important
because Camino Guard can legitimately start off-route (for example at a hotel)
and guide the walker toward the nearest official Camino geometry.


### Global track-orientation solver

Some CNIG KML sections are stored in the opposite point order from their
neighbouring numbered sections. A large `end -> start` distance therefore does
not necessarily mean the Camino has a real geographic gap.

Solve forward/reverse orientation for every numbered `a` spine globally:

```bash
python3 tools/analyze_cnig.py --solve-orientation
```

The solver uses dynamic programming with two states per track (`FWD` and `REV`).
It minimizes the total real endpoint-gap distance over the complete ordered
sequence. If solutions tie, fewer reversed tracks are preferred. Raw KML files
are never rewritten.

Inspect one Camino and see every selected orientation and optimized transition:

```bash
python3 tools/analyze_cnig.py --group ES35a --solve-orientation
```

After orientation has been solved, list only the remaining large real gaps. The
default cutoff is 2 km:

```bash
python3 tools/analyze_cnig.py --solve-orientation --large-gaps
```

A custom cutoff can be supplied in meters:

```bash
python3 tools/analyze_cnig.py --solve-orientation --large-gaps 500
```

These gaps remain gaps in the route data; the analyzer never inserts synthetic
geometry between official CNIG tracks.


### Variant bridge analysis

After primary-spine orientation is solved, a remaining large `a -> a` gap may
still be explained by an official alternate CNIG section (`b`, `c`, ...).

Search all optimized primary gaps over 2 km:

```bash
python3 tools/analyze_cnig.py --variant-bridges
```

Inspect one route group:

```bash
python3 tools/analyze_cnig.py --group PT08a --variant-bridges
```

The optional argument changes the primary-gap cutoff in meters:

```bash
python3 tools/analyze_cnig.py --variant-bridges 500
```

`--threshold` controls the maximum small unmapped endpoint join allowed between
official tracks (100 m by default).

The bridge search intentionally alternates between a small endpoint join and
traversal of a **complete official non-`a` CNIG track**. This prevents the
analyzer from hopping through a dense cluster of nearby endpoints without using
the actual route geometry. Every connector gap is reported separately. No raw
KML is modified and no synthetic route geometry is created.


### Cross-group shared-route analysis

Named CNIG Camino groups are not necessarily independent route islands. Different
Caminos can share the same physical route segment, so a missing section in one
group may already exist as an official track in another group.

Search all remaining optimized primary-spine gaps over 2 km against the complete
CNIG collection:

```bash
python3 tools/analyze_cnig.py --cross-group-bridges
```

Inspect only gaps belonging to one Camino while still searching all 1,073 tracks
as possible bridge geometry:

```bash
python3 tools/analyze_cnig.py --group PT08a --cross-group-bridges
```

The optional argument changes the primary-gap cutoff in meters:

```bash
python3 tools/analyze_cnig.py --cross-group-bridges 500
```

A cross-group bridge must traverse complete official CNIG tracks. Small unmapped
endpoint joins are limited by `--threshold` (100 m by default) and are reported
separately. To avoid accepting absurd routes through the wider Camino network,
the search traversal is capped at `max(100 km, straight_gap * 3)` by default.
The factor can be changed with `--bridge-max-factor`.

No raw KML file is changed and no synthetic route geometry is generated.


### Logical place graph and semantic bridges

CNIG filenames contain useful logical route metadata independently from the KML
point order. For example:

```text
PT08a_20a_muge-santarem.kml
PT01a_04a_santarem-golega.kml
PT08a_22a_golega-entroncamento.kml
```

These names describe the logical place chain `Muge -> Santarem -> Golega ->
Entroncamento`, even if the physical endpoints of two KML files are not close.

Summarize the global place graph:

```bash
python3 tools/analyze_cnig.py --place-graph
```

Resolve all optimized primary-spine gaps over 2 km using directed place
continuity across every CNIG route group:

```bash
python3 tools/analyze_cnig.py --semantic-bridges
```

Inspect one Camino while still using all 1,073 official tracks as possible
semantic bridge edges:

```bash
python3 tools/analyze_cnig.py --group PT08a --semantic-bridges
```

Place names are normalized case-insensitively, accent-insensitively, and without
separator differences such as underscores/spaces. The bridge search is directed:
a file named `santarem-golega` can connect Santarem to Golega but is not silently
treated as the reverse logical route.

After a semantic chain is found, KML point order (`FWD`/`REV`) is optimized
separately only to measure the physical connector gaps. Those gaps are never
filled and do not invalidate the logical route. This allows Camino Guard to
represent cases such as a hotel, town-center offset, or missing CNIG geometry as
"distance to the Camino" rather than inventing a route line.

Semantic searches are capped at eight official tracks and
`max(100 km, primary_gap * 3)` official bridge length by default.


### Semantic topology and duplicate geometry audit

Section numbers are useful source identifiers, but they are not reliable enough
to define Camino traversal order. The logical `FROM -> TO` place graph is
therefore analyzed directly.

Classify every route group from its directed place graph:

```bash
python3 tools/analyze_cnig.py --semantic-topology
```

Inspect one route in detail:

```bash
python3 tools/analyze_cnig.py --group ES22a --semantic-topology
```

The report counts weakly connected components, logical sources/sinks, real
branch and merge places, parallel `FROM -> TO` edges, and directed cycles.
Parallel tracks to the same destination do not create a false branch.

The global place graph currently contains repeated normalized directed
`FROM -> TO` pairs. Audit their physical geometry with:

```bash
python3 tools/analyze_cnig.py --duplicate-geometry
```

Or inspect only one route group:

```bash
python3 tools/analyze_cnig.py --group ES22a --duplicate-geometry
```

Every repeated pair is compared after distance-based polyline resampling, in
both KML point orientations. Pairwise geometry is classified as:

- `IDENTICAL`: coordinate sequences are exactly equal (forward or reversed).
- `SAME`: maximum sampled separation <= 30 m and length difference <= 2%.
- `NEAR`: maximum sampled separation <= 100 m and length difference <= 5%.
- `ALTERNATIVE`: larger geometric difference; keep as a potentially distinct
  route until deliberately reviewed.

This analysis is diagnostic only. No raw KML is modified, no track is removed,
and no synthetic connector geometry is created.


### Processed data export and logical tree view

The raw CNIG KML files remain untouched. A separate converter now creates a
human-friendly processed layer under `data/processed/`.

Run the converter from the repository root:

```bash
python3 tools/build_processed_cnig.py
```

This writes:

- `data/processed/metadata.json`
- `data/processed/catalog.json`
- `data/processed/places.json`
- `data/processed/groups/<GROUP>.json`

Each group export contains:

- the stable CNIG `source_id` (for updates and provenance),
- the official CNIG route name,
- a shorter human-facing `name`,
- a `display_name`,
- semantic topology summaries by logical component,
- and JSON track exports with coordinate/elevation arrays.

The goal is that user-facing layers can show names such as:

```text
Camino Mozárabe de Granada — Granada → Baena
```

instead of only CNIG codes like `ES10c`.

The converter can also print a first logical ASCII tree. This is not yet a
full stylized "subway map", but it already provides the directed branch
structure needed for such a view later.

Example for the Mozárabe family around Baena:

```bash
python3 tools/build_processed_cnig.py   --tree-groups ES10a,ES10b,ES10c,ES10d,ES10e   --tree-root baena   --tree-reverse
```

`--tree-reverse` walks the graph upstream from the chosen root, which is useful
for views such as "Santiago at the top" or "Baena at the top" with multiple
southern branches below.

The ASCII tree is also written to `data/processed/trees/` unless a custom
`--tree-output` path is provided.


### Global processed Camino network

The processed export now also writes:

```text
data/processed/network.json
```

This file is the global human-facing Camino place network. CNIG group codes are
kept only as provenance (`source_id` / `route_group_id`); route names and place
names are stored separately for UI use.

The global network applies conservative geometry handling:

- `IDENTICAL` and `SAME` source tracks for the same logical `FROM -> TO` pair
  are merged into one geometry family while all source references are kept.
- `NEAR` and `ALTERNATIVE` geometries remain separate parallel edge families.
- tracks whose parsed endpoint is a pseudo-place such as `variante` are not
  allowed to create fake place nodes. They are preserved under
  `detached_variant_tracks` for later geometric attachment.
- every network edge lists the human-readable Camino names that use it, plus
  the original CNIG source track IDs for provenance.

A global ASCII tree can be rendered directly from the processed network:

```bash
python3 tools/build_processed_cnig.py --network-tree santiago
```

The global tree walks incoming Camino edges by default, so the chosen
pilgrimage destination appears at the top and origins branch downward. This is
the textual precursor to the planned stylized U-Bahn / metro-network view.

For another direction use:

```bash
python3 tools/build_processed_cnig.py --network-tree baena --network-tree-forward
```

The generated tree is saved under `data/processed/trees/`. Shared nodes and
cycles are marked rather than recursively duplicated forever.


### Spatial place identity

The global network no longer assumes that equal normalized names automatically
mean the same physical place everywhere.

Processed place identity now uses semantic place names **plus track-endpoint
location**. Equal normalized names are merged when their endpoint observations
belong to the same spatial cluster.

The default same-name merge radius is deliberately generous:

```text
5 km
```

It can be changed when rebuilding:

```bash
python3 tools/build_processed_cnig.py --place-merge-distance 5000
```

Values below 1 km are rejected intentionally. CNIG endpoints may lie at
different entrances/exits of a town or otherwise be offset from one another.

If one normalized name occurs in spatial clusters farther apart than the merge
radius, the processed network creates separate place identities such as:

```text
santacruz@1
santacruz@2
```

The user-facing display name remains `Santa Cruz`; the suffix is internal
processed identity only.

`data/processed/network.json` now stores a representative endpoint-derived
position for each network place and reports all normalized names that had to be
split spatially.

Different normalized names are **not** merged automatically. Nearby
name-containment cases such as `Vitoria` / `Vitoria Gasteiz` are written as
audit candidates to:

```text
data/processed/place_alias_candidates.json
```

Those candidates remain separate until deliberately reviewed.


### Automatic nearby place aliases

Different place names that are conservative aliases are now merged
automatically in the global network. The rule remains intentionally narrow:

1. one normalized name must contain the other, for example `vitoria` and
   `vitoriagasteiz`; and
2. all source place positions in the merged alias cluster must remain within
   the configured place radius (5 km by default).

The second rule uses a complete-link distance check so a chain of intermediate
aliases cannot accidentally join two places more than 5 km apart.

For the user-facing name, the longest available place name wins. Shorter names
remain stored as aliases. Thus a pair such as `Vitoria` / `Vitoria Gasteiz` is
represented as `Vitoria Gasteiz` while both spellings remain searchable.

Applied merges are recorded in:

```text
data/processed/place_alias_merges.json
```

`place_alias_candidates.json` remains as a residual audit file and should
normally shrink substantially after automatic merging.

If a track's two endpoint names collapse to the same processed place, that
track is retained in the network audit as an alias connector but is not emitted
as a route edge. No raw geometry is changed or deleted.


### Primary-defined place identity for variant tracks

Strict primary `a` tracks now define canonical place identities before
non-primary variants are attached to the global network.

This prevents short, truncated, or offset variant geometry from creating a
second fake copy of an already known town. For example, a variant whose
filename says `cifuentes-mandayona` is attached to the canonical primary
`Cifuentes` and `Mandayona` places even when the variant KML endpoint is far
from the primary town endpoint.

Variant endpoint attachment follows this order:

1. exact normalized semantic name already defined by a primary track;
2. conservative normalized-name containment against a primary place, such as
   `Vitoria` -> `Vitoria Gasteiz`;
3. only if neither exists may a real variant-only place identity be created.

Pseudo-place labels beginning with `Variante`, `Variant`, `Ramal`,
`Alternativa`, or `Alternative` never become global place nodes. Their source
tracks remain preserved in the raw data and processed group exports.

Variant geometry is never moved or rewritten. Attachments that override a
misleading geometry endpoint are recorded for audit in:

```text
data/processed/variant_endpoint_attachments.json
```

The 5 km place merge rule remains unchanged for ordinary spatial identity.
Variant endpoint semantic attachment is deliberately allowed outside that
radius because the purpose is to keep a malformed/truncated variant endpoint
from redefining a known primary town.


### Portable source references

Processed JSON stores references to raw CNIG files as repository-relative paths,
for example:

```text
data/raw/cnig/ES10c/ES10c_01a_granada-pinos_puente.kml
```

Machine-specific absolute paths such as `/home/user/...` and shell placeholders
such as `$HOME` are deliberately not persisted. The same rule applies to
`occurrence_id` values used by the spatial/variant audit data.

## Raw versus processed data

```text
official CNIG KML
       │
       ▼
data/raw/cnig/          unchanged source
       │
       │ converter
       ▼
data/processed/         merged/normalized Camino Guard data
       │
       ▼
phone                   offline route engine + phone GPS
       │
       ▼
Pebble Time 2           compact navigation/statistics UI
```

Processed Caminos may join official segments and add settlements, route distance,
elevation summaries and variants. The raw KML files remain available so generated
data can always be recreated and verified.

## Android companion

Camino Guard uses a native Android companion as the phone-side application. The Android app will own the offline map, full Camino dataset, GPS/route matching, trip logging and later Pebble communication. The watch remains the small walking cockpit.

The Android project lives under `android/`.
