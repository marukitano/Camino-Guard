# CNIG raw Camino data

This directory contains the unmodified KML route files used as source data by
Camino Guard.

## Source

Instituto Geográfico Nacional (IGN) / Centro Nacional de Información Geográfica
(CNIG):

https://centrodedescargas.cnig.es/CentroDescargas/loadCamSan

The Camino routes are supplied by the Federación Española de Asociaciones de
Amigos del Camino de Santiago (FEAACS).

## License and attribution

CNIG publishes the Camino route product under CC BY 4.0 and specifies this
attribution for unchanged source data:

> Rutas de Caminos de Santiago 2020-2026 CC-BY 4.0 FEAACS

For a new product generated from the route data, CNIG specifies:

> Obra derivada de Rutas de Caminos de Santiago 2020-2026 CC-BY 4.0 FEAACS

The license applies to the route data in this directory independently of the
license chosen later for Camino Guard source code.

## Rules for this directory

- Do not manually edit the KML files.
- Do not merge or simplify tracks in place.
- Generated or converted data belongs under `data/processed/`.
- `manifest.json` records source URLs, CNIG IDs, local paths, coordinate counts,
  elevation counts, file sizes and SHA-256 hashes.
- Official upstream updates may replace a raw KML through
  `tools/download_cnig.py --update`.
- A file removed from the CNIG index is not automatically deleted locally.

## Synchronizing the dataset

From the repository root:

```bash
python3 tools/download_cnig.py
```

downloads missing/invalid files.

To check every route against the current CNIG copy:

```bash
python3 tools/download_cnig.py --update
```

Credentials are requested interactively. Password input is hidden and credentials
are not stored by Camino Guard.
