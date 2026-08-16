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
