#!/usr/bin/env bash

# Camino Guard developer gate.
#
# Keeps the complete output of every check for debugging, then prints one
# compact colored human-readable summary at the very end.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID="$ROOT/android"

if [[ -t 1 ]]; then
    GREEN=$'\033[32m'
    RED=$'\033[31m'
    BOLD=$'\033[1m'
    RESET=$'\033[0m'
else
    GREEN=''
    RED=''
    BOLD=''
    RESET=''
fi

dead_code_status=1
architecture_status=1
tests_status=1
compile_status=1

run_step() {
    local label="$1"
    shift

    printf '\n%s\n' "==================== $label ===================="

    "$@"
    local status=$?

    printf '%s\n' "=================================================="
    return "$status"
}

cd "$ROOT" || exit 1

run_step "DEAD CODE AUDIT" \
    python3 tools/audit_dead_code.py
dead_code_status=$?

run_step "ARCHITECTURE AUDIT" \
    python3 tools/audit_architecture.py
architecture_status=$?

cd "$ANDROID" || exit 1

run_step "P0 / UNIT TESTS" \
    ./gradlew testDebugUnitTest
tests_status=$?

run_step "JAVA COMPILE" \
    ./gradlew compileDebugJavaWithJavac
compile_status=$?

print_result() {
    local label="$1"
    local status="$2"

    if [[ "$status" -eq 0 ]]; then
        printf '%b🟢 %-24s BESTANDEN%b\n' "$GREEN" "$label" "$RESET"
    else
        printf '%b🔴 %-24s NICHT BESTANDEN%b\n' "$RED" "$label" "$RESET"
    fi
}

printf '\n'
printf '%b%s%b\n' "$BOLD" "================ CAMINO GUARD CHECK ================" "$RESET"
printf '\n'

print_result "DEAD CODE AUDIT" "$dead_code_status"
print_result "ARCHITECTURE AUDIT" "$architecture_status"
print_result "P0 / UNIT TESTS" "$tests_status"
print_result "JAVA COMPILE" "$compile_status"

printf '\n'

if [[ "$dead_code_status" -eq 0 \
        && "$architecture_status" -eq 0 \
        && "$tests_status" -eq 0 \
        && "$compile_status" -eq 0 ]]; then
    printf '%b%s%b\n' "$GREEN$BOLD" "================== ALLES GRÜN ======================" "$RESET"
    exit 0
fi

printf '%b%s%b\n' "$RED$BOLD" "=============== FEHLER VORHANDEN ==================" "$RESET"
exit 1
