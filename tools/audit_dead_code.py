#!/usr/bin/env python3
# Conservative dead-code candidate scanner for Camino Guard production Java.
#
# This is deliberately NOT an automatic deletion tool and not a compiler.
#
# It reports:
#   1. private methods whose identifier occurs only in their declaration
#   2. private fields/constants whose identifier occurs only in their declaration
#
# Comments and string/char literals are ignored for the normal occurrence
# count. Android/framework callbacks are normally public/protected and are
# therefore outside the private-method pass.
#
# Caveat: reflection can access private members by string name. String literals
# are ignored, so every candidate must still be reviewed with a raw repository
# search before deletion.

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

# A Java local variable cannot be private, so matching a private declaration
# ending in '=' or ';' is a conservative way to identify class fields without
# needing a full Java parser. The part before the field name deliberately does
# not permit '(' so constructors/methods are excluded.
PRIVATE_FIELD_RE = re.compile(
    r"\bprivate\s+"
    r"(?P<modifiers>"
    r"(?:(?:static|final|volatile|transient)\s+)*"
    r")"
    r"(?:@\w+(?:\([^)]*\))?\s+)*"
    r"[A-Za-z_$][A-Za-z0-9_$.<>\[\],?@ \t]*\s+"
    r"(?P<name>[A-Za-z_$][A-Za-z0-9_$]*)"
    r"\s*(?P<terminator>=|;)"
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


def identifier_occurrences(name, combined):
    return len(
        re.findall(
            r"\b" + re.escape(name) + r"\b",
            combined,
        )
    )


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Report conservative unused private Java method/field candidates."
        )
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

    methods = []
    fields = []

    for path, stripped in stripped_by_path.items():
        for match in PRIVATE_METHOD_RE.finditer(stripped):
            methods.append(
                (
                    match.group("name"),
                    path,
                    line_number(stripped, match.start("name")),
                )
            )

        for match in PRIVATE_FIELD_RE.finditer(stripped):
            modifiers = match.group("modifiers") or ""
            modifier_words = modifiers.split()
            kind = (
                "constant"
                if "static" in modifier_words
                and "final" in modifier_words
                else "field"
            )

            fields.append(
                (
                    match.group("name"),
                    path,
                    line_number(stripped, match.start("name")),
                    kind,
                )
            )

    method_candidates = []
    for name, path, line in methods:
        if identifier_occurrences(name, combined) == 1:
            method_candidates.append((path, line, name))

    field_candidates = []
    for name, path, line, kind in fields:
        if identifier_occurrences(name, combined) == 1:
            field_candidates.append((path, line, name, kind))

    print("DEAD CODE AUDIT")
    print(f"  production Java files: {len(java_files)}")
    print(f"  private methods scanned: {len(methods)}")
    print(f"  private fields/constants scanned: {len(fields)}")
    print()

    total = len(method_candidates) + len(field_candidates)

    if total == 0:
        print("  no unused private-member candidates found")
        return 0

    if method_candidates:
        print("UNUSED PRIVATE-METHOD CANDIDATES")
        print(
            "  identifier occurs only in its own declaration;"
            " review before deletion"
        )
        print()

        for path, line, name in method_candidates:
            relative = path.relative_to(ROOT)
            print(f"  {relative}:{line}  {name}()")

        print()

    if field_candidates:
        print("UNUSED PRIVATE FIELD/CONSTANT CANDIDATES")
        print(
            "  identifier occurs only in its own declaration;"
            " review before deletion"
        )
        print()

        for path, line, name, kind in field_candidates:
            relative = path.relative_to(ROOT)
            label = "CONST" if kind == "constant" else "FIELD"
            print(f"  {relative}:{line}  [{label}] {name}")

        print()

    print(f"  candidates: {total}")
    print(
        "  note: reflection by string name is intentionally not inferred;"
        " verify each candidate with raw grep before removing it"
    )

    return 1 if args.fail_on_candidates else 0


if __name__ == "__main__":
    raise SystemExit(main())
