# Camino Guard Architecture

## One Camino world

Schaffhausen is data coverage, not an application mode.
Spain/Portugal and Schaffhausen/Tux use the same controller, renderer,
selection, measurement, graph routing, height profile, navigation and GPS path.

## One canonical Camino dataset

Runtime Camino geometry lives only in:

`android/app/src/main/assets/camino/camino-global.json`

`CaminoRepository` is the single runtime parser for this file.
`CaminoMapRenderer` renders the already-parsed route domain objects and must
not open or parse the canonical asset independently.
Do not add another `tracks-global*.geojson` runtime truth.

## One configuration file

User-tunable values live in:

`android/app/src/main/assets/config/camino-config.json`

This contains colours, casing, widths, fonts, navigation timing, camera
parameters, startup view and optional MapLibre layer overrides.
`CaminoConfig` is immutable after startup. Runtime state never belongs there.

## Regional map files are data only

Iberia, Schaffhausen and world PMTiles may remain separate physical files.
That is storage/coverage only; a region must never select a different Camino
implementation.

## Comments explain why

Keep comments that explain constraints and non-obvious algorithms.
Git history owns patch/version comments such as `CAMINO_*_V17`.
