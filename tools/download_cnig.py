#!/usr/bin/env python3
# Download and synchronize official CNIG/IGN Camino de Santiago KML tracks.

from __future__ import annotations

import argparse
import getpass
import hashlib
import html
import http.cookiejar
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from email.message import Message
from pathlib import Path

BASE = "https://centrodedescargas.cnig.es"
LOGIN_PAGE = BASE + "/CentroDescargas/registrate"
LOGIN_URL = BASE + "/CentroDescargas/login"
INDEX_URL = BASE + "/CentroDescargas/loadCamSan"
OUTPUT_REL = Path("data/raw/cnig")
MANIFEST_NAME = "manifest.json"

ATTRIBUTION = "Rutas de Caminos de Santiago 2020-2026 CC-BY 4.0 FEAACS"
DERIVED_ATTRIBUTION = (
    "Obra derivada de Rutas de Caminos de Santiago "
    "2020-2026 CC-BY 4.0 FEAACS"
)

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) "
    "AppleWebKit/537.36 Camino-Guard/0.2"
)
REQUEST_DELAY_SECONDS = 0.35
TIMEOUT_SECONDS = 45
MAX_RETRIES = 4


@dataclass(frozen=True)
class Track:
    page_filename: str
    download_url: str
    download_id: str
    route_code: str
    route_id: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Download/synchronize official CNIG Camino KML tracks."
    )
    parser.add_argument(
        "--update",
        action="store_true",
        help="re-download all current tracks and replace only changed files",
    )
    parser.add_argument("-y", "--yes", action="store_true")
    return parser.parse_args()


def require_repo_root() -> Path:
    root = Path.cwd().resolve()
    if not (root / ".git").exists():
        raise SystemExit(
            "ERROR: Run this command from the Camino-Guard repository root."
        )
    return root


def route_id_from_name(filename: str) -> str:
    match = re.match(
        r"^([A-Za-z]{2}\d{2}[A-Za-z0-9]*)[-_]([0-9]{2}[A-Za-z0-9]*)[-_]",
        Path(filename).name,
    )
    if not match:
        raise ValueError(f"Cannot derive route ID from filename: {filename}")
    return f"{match.group(1)}-{match.group(2)}"


def build_opener() -> urllib.request.OpenerDirector:
    jar = http.cookiejar.CookieJar()
    return urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(jar)
    )


def make_request(url: str, *, referer=None, accept="*/*") -> urllib.request.Request:
    headers = {"User-Agent": USER_AGENT, "Accept": accept}
    if referer:
        headers["Referer"] = referer
    return urllib.request.Request(url, headers=headers)


def read_response_text(response) -> str:
    raw = response.read()
    charset = response.headers.get_content_charset() or "utf-8"
    return raw.decode(charset, errors="replace")


def login(opener: urllib.request.OpenerDirector) -> None:
    print("CNIG login")
    print("----------")
    email = input("CNIG email: ").strip()
    password = getpass.getpass("CNIG password: ")
    if not email or not password:
        raise RuntimeError("Email and password may not be empty.")

    with opener.open(
        make_request(LOGIN_PAGE, accept="text/html,application/xhtml+xml"),
        timeout=TIMEOUT_SECONDS,
    ) as response:
        response.read()

    query = urllib.parse.urlencode({"usuario": email, "pwd": password})
    login_request_url = LOGIN_URL + "?" + query
    try:
        req = make_request(
            login_request_url,
            referer=LOGIN_PAGE,
            accept="application/json,text/javascript,*/*;q=0.01",
        )
        req.add_header("X-Requested-With", "XMLHttpRequest")
        with opener.open(req, timeout=TIMEOUT_SECONDS) as response:
            text = read_response_text(response)
    finally:
        password = ""
        query = ""
        login_request_url = ""

    try:
        obj = json.loads(text)
    except json.JSONDecodeError as exc:
        raise RuntimeError("CNIG login returned non-JSON.") from exc

    message = obj.get("msj")
    if message not in ("", None):
        raise RuntimeError(f"CNIG rejected login: {message}")

    print("Login: OK")
    print()


def fetch_index(opener: urllib.request.OpenerDirector) -> str:
    req = make_request(
        INDEX_URL,
        referer=LOGIN_PAGE,
        accept="text/html,application/xhtml+xml",
    )
    with opener.open(req, timeout=TIMEOUT_SECONDS) as response:
        return read_response_text(response)


def parse_tracks(index_html: str) -> list[Track]:
    row_re = re.compile(r"<tr\b[^>]*>(.*?)</tr>", re.I | re.S)
    href_re = re.compile(
        r"href\s*=\s*[\"']([^\"']*/descargarArchivo/usuarioMovil/(\d+))[\"']",
        re.I,
    )
    kml_re = re.compile(
        r"\b([A-Za-z]{2}\d{2}[A-Za-z0-9]*-[^<>\s]+?\.kml)\b", re.I
    )

    tracks = []
    seen = set()

    for row_match in row_re.finditer(index_html):
        row = html.unescape(row_match.group(1))
        km = kml_re.search(row)
        hm = href_re.search(row)
        if not km or not hm:
            continue

        page_filename = km.group(1)
        route_id = route_id_from_name(page_filename)
        if route_id in seen:
            raise RuntimeError(f"Duplicate route ID in CNIG index: {route_id}")
        seen.add(route_id)

        tracks.append(
            Track(
                page_filename=page_filename,
                download_url=urllib.parse.urljoin(INDEX_URL, hm.group(1)),
                download_id=hm.group(2),
                route_code=page_filename.split("-", 1)[0],
                route_id=route_id,
            )
        )

    if not tracks:
        raise RuntimeError("No KML tracks found in CNIG index.")
    return tracks


def filename_from_headers(headers: Message, fallback: str) -> str:
    cd = headers.get("Content-Disposition")
    if not cd:
        return fallback
    temp = Message()
    temp["content-disposition"] = cd
    filename = temp.get_param("filename", header="content-disposition", unquote=True)
    return Path(filename).name if filename else fallback


def fallback_filename(page_filename: str) -> str:
    parts = page_filename.split("-", 2)
    if len(parts) == 3:
        return f"{parts[0]}_{parts[1]}_{parts[2]}"
    return page_filename


def kml_info(path: Path) -> tuple[int, int]:
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        raise ValueError(f"invalid XML/KML: {exc}") from exc

    coordinates = elevations = 0
    for elem in root.iter():
        if elem.tag.split("}")[-1] != "coordinates" or not elem.text:
            continue
        for token in elem.text.split():
            parts = token.split(",")
            if len(parts) < 2:
                continue
            try:
                float(parts[0])
                float(parts[1])
            except ValueError:
                continue
            coordinates += 1
            if len(parts) >= 3 and parts[2].strip():
                try:
                    float(parts[2])
                    elevations += 1
                except ValueError:
                    pass

    if coordinates == 0:
        raise ValueError("KML contains no coordinates")
    return coordinates, elevations


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_manifest(path: Path) -> dict:
    if path.is_file():
        manifest = json.loads(path.read_text(encoding="utf-8"))
    else:
        manifest = {"tracks": {}}

    tracks = manifest.get("tracks", {})
    if manifest.get("schema_version") != 2:
        migrated = {}
        for entry in tracks.values():
            name = entry.get("page_filename") or entry.get("local_path", "")
            route_id = route_id_from_name(name)
            if route_id in migrated:
                raise RuntimeError(f"Duplicate route ID in manifest: {route_id}")
            entry = dict(entry)
            entry["route_id"] = route_id
            migrated[route_id] = entry
        tracks = migrated

    manifest.update(
        {
            "schema_version": 2,
            "source": INDEX_URL,
            "attribution": ATTRIBUTION,
            "derived_attribution": DERIVED_ATTRIBUTION,
            "tracks": tracks,
        }
    )
    return manifest


def save_manifest(path: Path, manifest: dict) -> None:
    manifest["updated_at_utc"] = datetime.now(timezone.utc).isoformat()
    temp = path.with_name(path.name + ".tmp")
    temp.write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    temp.replace(path)


def candidate_paths(repo: Path, route_dir: Path, track: Track, manifest: dict):
    candidates = []
    old = manifest.get("tracks", {}).get(track.route_id)
    if old and old.get("local_path"):
        candidates.append(repo / old["local_path"])
    candidates += [
        route_dir / track.page_filename,
        route_dir / fallback_filename(track.page_filename),
    ]
    prefix = track.route_id.replace("-", "_", 1)
    candidates += sorted(route_dir.glob(prefix + "*.kml"))

    result, seen = [], set()
    for path in candidates:
        path = path.resolve()
        if path not in seen:
            seen.add(path)
            result.append(path)
    return result


def valid_existing(repo: Path, route_dir: Path, track: Track, manifest: dict):
    for path in candidate_paths(repo, route_dir, track, manifest):
        if not path.is_file():
            continue
        try:
            kml_info(path)
            return path
        except ValueError:
            pass
    return None


def fetch_remote_to_part(opener, track: Track, route_dir: Path):
    last_error = None
    part_path = route_dir / f".{track.route_id}.{track.download_id}.part"

    for attempt in range(1, MAX_RETRIES + 1):
        try:
            part_path.unlink(missing_ok=True)
            req = make_request(
                track.download_url,
                referer=INDEX_URL,
                accept="application/xml,text/xml,*/*;q=0.8",
            )
            with opener.open(req, timeout=TIMEOUT_SECONDS) as response:
                server_filename = filename_from_headers(
                    response.headers, fallback_filename(track.page_filename)
                )
                server_filename = Path(server_filename).name
                if not server_filename.lower().endswith(".kml"):
                    server_filename += ".kml"

                with part_path.open("wb") as out:
                    while True:
                        chunk = response.read(256 * 1024)
                        if not chunk:
                            break
                        out.write(chunk)

            coordinates, elevations = kml_info(part_path)
            return part_path, server_filename, coordinates, elevations

        except urllib.error.HTTPError as exc:
            last_error = exc
            part_path.unlink(missing_ok=True)
            if exc.code in (401, 403, 429):
                raise RuntimeError(
                    f"CNIG returned HTTP {exc.code}; rerun later."
                ) from exc
        except (urllib.error.URLError, TimeoutError, ValueError, OSError) as exc:
            last_error = exc
            part_path.unlink(missing_ok=True)

        if attempt < MAX_RETRIES:
            wait = 2 ** (attempt - 1)
            print(f"      retry in {wait}s: {last_error}")
            time.sleep(wait)

    raise RuntimeError(
        f"Failed {track.page_filename} after {MAX_RETRIES} attempts: {last_error}"
    )


def make_entry(repo, track, path, coordinates, elevations, checksum):
    return {
        **asdict(track),
        "local_path": str(path.relative_to(repo)),
        "bytes": path.stat().st_size,
        "coordinates": coordinates,
        "elevations": elevations,
        "sha256": checksum,
    }


def main() -> int:
    args = parse_args()
    repo = require_repo_root()
    output_root = repo / OUTPUT_REL
    output_root.mkdir(parents=True, exist_ok=True)
    manifest_path = output_root / MANIFEST_NAME

    try:
        manifest = load_manifest(manifest_path)
        opener = build_opener()
        login(opener)
        print("Reading current CNIG Camino index...")
        tracks = parse_tracks(fetch_index(opener))
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    current_ids = {t.route_id for t in tracks}
    route_codes = sorted({t.route_code for t in tracks})

    print(f"Mode:             {'UPDATE' if args.update else 'NORMAL'}")
    print(f"Tracks in CNIG:   {len(tracks)}")
    print(f"Route groups:     {len(route_codes)}")
    print(f"Destination:      {OUTPUT_REL}/")
    print()

    if not args.yes:
        verb = "Check all tracks for updates" if args.update else "Validate/download tracks"
        if input(f"{verb}? [y/N] ").strip().lower() not in {"y", "yes", "j", "ja"}:
            print("Cancelled.")
            return 0
        print()

    counters = dict(new=0, updated=0, unchanged=0, skipped=0, repaired=0)
    failed = False
    started = time.monotonic()

    try:
        for idx, track in enumerate(tracks, 1):
            route_dir = output_root / track.route_code
            route_dir.mkdir(parents=True, exist_ok=True)
            existing = valid_existing(repo, route_dir, track, manifest)

            if not args.update and existing:
                c, e = kml_info(existing)
                manifest["tracks"][track.route_id] = make_entry(
                    repo, track, existing, c, e, sha256_file(existing)
                )
                counters["skipped"] += 1
                print(f"[{idx:4}/{len(tracks)}] SKIP {track.route_id}")
                continue

            print(
                f"[{idx:4}/{len(tracks)}] "
                f"{'CHECK' if args.update and existing else 'GET  '} "
                f"{track.page_filename}"
            )
            part, server_name, c, e = fetch_remote_to_part(opener, track, route_dir)
            remote_sha = sha256_file(part)

            if args.update and existing:
                local_sha = sha256_file(existing)
                if local_sha == remote_sha:
                    expected = (route_dir / server_name).resolve()
                    if existing.resolve() != expected:
                        if expected.exists():
                            expected.unlink()
                        existing.replace(expected)
                        existing = expected
                    part.unlink(missing_ok=True)
                    manifest["tracks"][track.route_id] = make_entry(
                        repo, track, existing, c, e, remote_sha
                    )
                    counters["unchanged"] += 1
                    print("             unchanged")
                else:
                    final = (route_dir / server_name).resolve()
                    part.replace(final)
                    if existing.resolve() != final and existing.exists():
                        existing.unlink()
                    manifest["tracks"][track.route_id] = make_entry(
                        repo, track, final, c, e, remote_sha
                    )
                    counters["updated"] += 1
                    print(f"             UPDATED -> {final.name}")
            else:
                old_candidates = [
                    p for p in candidate_paths(repo, route_dir, track, manifest)
                    if p.is_file()
                ]
                had_invalid = bool(old_candidates)
                final = (route_dir / server_name).resolve()
                part.replace(final)
                for old in old_candidates:
                    if old.resolve() != final and old.exists():
                        old.unlink()
                manifest["tracks"][track.route_id] = make_entry(
                    repo, track, final, c, e, remote_sha
                )
                if had_invalid:
                    counters["repaired"] += 1
                    print(f"             REPAIRED -> {final.name}")
                else:
                    counters["new"] += 1
                    print(f"             NEW -> {final.name}")

            save_manifest(manifest_path, manifest)
            time.sleep(REQUEST_DELAY_SECONDS)

    except KeyboardInterrupt:
        print("\nInterrupted. Completed files are safe.")
        failed = True
    except Exception as exc:
        print(f"\nERROR: {exc}", file=sys.stderr)
        print("Completed files are safe; rerun to resume.")
        failed = True
    finally:
        manifest["index_track_count"] = len(tracks)
        manifest["route_groups"] = route_codes
        save_manifest(manifest_path, manifest)

    stale = sorted(set(manifest["tracks"]) - current_ids)

    print("\nSummary")
    print("-------")
    if args.update:
        print(f"Updated:          {counters['updated']}")
        print(f"Unchanged:        {counters['unchanged']}")
        print(f"New:              {counters['new']}")
        print(f"Repaired:         {counters['repaired']}")
    else:
        print(f"Downloaded:       {counters['new']}")
        print(f"Repaired:         {counters['repaired']}")
        print(f"Already valid:    {counters['skipped']}")
    print(f"Current CNIG:     {len(tracks)}")
    print(f"Stale local IDs:  {len(stale)}")
    print(f"Elapsed:          {(time.monotonic() - started) / 60:.1f} min")

    if stale:
        print("\nNo longer in current CNIG index (NOT deleted):")
        for rid in stale:
            print(f"  {rid}: {manifest['tracks'][rid].get('local_path', '?')}")

    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
