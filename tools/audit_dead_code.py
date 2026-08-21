#!/usr/bin/env python3
# Conservative dead-code candidate scanner for Camino Guard production Java.
#
# This is deliberately NOT an automatic deletion tool and not a compiler.
# It reports private methods whose identifier occurs only in their declaration
# across src/main/java after comments and string/char literals are ignored.
#
# Android/framework callbacks are normally public/protected and are outside
# this conservative first pass.
#
# Caveat: reflection can call private methods by string name. String literals
# are ignored, so every candidate must still be reviewed before deletion.

from pathlib import Path
import argparse
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "android/app/src/main/java"

PRIVATE_METHOD_RE = re.compile(
    r"\bprivate\s+"
    r"(?:(?:static|final|synchronized|native|strictfp)\s+)*"
    r"(?:<[^>{};]+>\s+)?"
    r"[A-Za-z_$][A-Za-z0-9_$.<>\[\],?@ \t]*\s+"
    r"(?P<name>[A-Za-z_$][A-Za-z0-9_$]*)\s*\("
)


def strip_comments_and_literals(text):
    # Replace comments/string/char contents with spaces, preserving newlines.
    out = []
    i = 0
    n = len(text)
    state = "code"

    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""

        if state == "code":
            if ch == "/" and nxt == "/":
                out.extend((" ", " "))
                i += 2
                state = "line_comment"
                continue
            if ch == "/" and nxt == "*":
                out.extend((" ", " "))
                i += 2
                state = "block_comment"
                continue
            if ch == '"':
                out.append(" ")
                i += 1
                state = "string"
                continue
            if ch == "'":
                out.append(" ")
                i += 1
                state = "char"
                continue

            out.append(ch)
            i += 1
            continue

        if state == "line_comment":
            if ch == "\n":
                out.append("\n")
                state = "code"
            else:
                out.append(" ")
            i += 1
            continue

        if state == "block_comment":
            if ch == "*" and nxt == "/":
                out.extend((" ", " "))
                i += 2
                state = "code"
                continue

            out.append("\n" if ch == "\n" else " ")
            i += 1
            continue

        if state in ("string", "char"):
            quote = '"' if state == "string" else "'"

            if ch == "\\":
                out.append(" ")
                i += 1
                if i < n:
                    out.append("\n" if text[i] == "\n" else " ")
                    i += 1
                continue

            if ch == quote:
                out.append(" ")
                i += 1
                state = "code"
                continue

            out.append("\n" if ch == "\n" else " ")
            i += 1
            continue

    return "".join(out)


def line_number(text, offset):
    return text.count("\n", 0, offset) + 1


def main():
    parser = argparse.ArgumentParser(
        description="Report conservative unused private Java method candidates."
    )
    parser.add_argument(
        "--fail-on-candidates",
        action="store_true",
        help="exit 1 when one or more candidates are found",
    )
    args = parser.parse_args()

    java_files = sorted(JAVA_ROOT.rglob("*.java"))
    if not java_files:
        print("ERROR: no production Java files found", file=sys.stderr)
        return 2

    stripped_by_path = {}
    combined_parts = []

    for path in java_files:
        text = path.read_text(encoding="utf-8")
        stripped = strip_comments_and_literals(text)
        stripped_by_path[path] = stripped
        combined_parts.append(stripped)

    combined = "\n".join(combined_parts)

    declarations = []
    for path, stripped in stripped_by_path.items():
        for match in PRIVATE_METHOD_RE.finditer(stripped):
            name = match.group("name")
            declarations.append(
                (
                    name,
                    path,
                    line_number(stripped, match.start("name")),
                )
            )

    candidates = []
    for name, path, line in declarations:
        occurrences = len(
            re.findall(
                r"\b" + re.escape(name) + r"\b",
                combined,
            )
        )

        if occurrences == 1:
            candidates.append((path, line, name))

    print("DEAD CODE AUDIT")
    print(f"  production Java files: {len(java_files)}")
    print(f"  private methods scanned: {len(declarations)}")
    print()

    if not candidates:
        print("  no unused private-method candidates found")
        return 0

    print("UNUSED PRIVATE-METHOD CANDIDATES")
    print(
        "  identifier occurs only in its own declaration;"
        " review before deletion"
    )
    print()

    for path, line, name in candidates:
        relative = path.relative_to(ROOT)
        print(f"  {relative}:{line}  {name}()")

    print()
    print(f"  candidates: {len(candidates)}")
    print(
        "  note: reflection by string name is intentionally not inferred;"
        " verify each candidate before removing it"
    )

    return 1 if args.fail_on_candidates else 0


if __name__ == "__main__":
    raise SystemExit(main())
