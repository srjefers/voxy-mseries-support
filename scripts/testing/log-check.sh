#!/bin/bash
# log-check.sh: check a Minecraft log for known Voxy failure signatures.
# Compatible with bash 3.2 (macOS /bin/bash).
if [ -z "${BASH_VERSION:-}" ]; then echo "Run this script with bash, for example: bash $0" >&2; exit 2; fi
set -u

usage() {
    cat <<'EOF'
Usage: bash log-check.sh [GAME_DIR]

Checks GAME_DIR/logs/latest.log after a test session. Run it after you quit
the game, or while the game is on the title screen.

It prints:
  - the Voxy version, the 'Metal device:' line and whether Voxy was enabled
  - a count for each known failure message (each must be 0)
  - model atlas CREATED and FREED counts (they must match after you quit)
  - info-only counts that are NOT failures
  - crash reports and hs_err_pid*.log files from the last 60 minutes

GAME_DIR is chosen in this order:
  1. the GAME_DIR argument
  2. the VOXY_GAME_DIR environment variable
  3. the run/ folder, when the script sits inside the Voxy repository
  4. ~/Library/Application Support/minecraft (official launcher)

Exit status: 0 = pass, 1 = at least one failure, 2 = setup error.
Save the log before you start the game again. The next launch overwrites it.
EOF
}

case ${1:-} in
    -h|--help) usage; exit 0 ;;
    -*) printf 'Unknown option: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
esac
[ $# -le 1 ] || { usage >&2; exit 2; }

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/lib.sh" || exit 2

echo "== Voxy log check"
voxy_resolve_game_dir "${1:-}"
G=$VOXY_GAME
L="$G/logs/latest.log"
WINDOW_MIN=60

printf 'Log file:    %s\n' "$L"
printf '  (last modified %s, %s KB)\n' "$(voxy_mtime "$L")" "$(( $(stat -f %z "$L") / 1024 ))"

# Count lines that contain a fixed string. LC_ALL=C and -a keep grep from
# stopping on odd bytes in the log.
count() {
    local n
    n=$(LC_ALL=C grep -a -c -F -- "$1" "$L" 2>/dev/null)
    printf '%s\n' "${n:-0}"
}
first_match() {
    LC_ALL=C grep -a -m1 -F -- "$1" "$L" 2>/dev/null | cut -c1-220
}

FAILS=0
WARNS=0

# ---------------------------------------------------------------- environment
echo
echo "== Environment"
fabric=$(LC_ALL=C grep -a -m1 -E 'Loading Minecraft .* with Fabric Loader' "$L" | sed -E 's/.*(Loading Minecraft)/\1/')
if [ -n "$fabric" ]; then
    printf 'Fabric:       %s\n' "$fabric"
else
    printf 'Fabric:       not found in the log. Is this a Fabric profile?\n'
    WARNS=$((WARNS + 1))
fi

voxy_line=$(LC_ALL=C grep -a -m1 -E '^[[:space:]]+- voxy ' "$L" | sed -E 's/^[[:space:]]+- //')
if [ -n "$voxy_line" ]; then
    printf 'Voxy:         %s\n' "$voxy_line"
else
    printf 'Voxy:         not in the mod list. Voxy did not load.\n'
    WARNS=$((WARNS + 1))
fi
jars=$(cd "$G/mods" 2>/dev/null && ls -1 2>/dev/null | grep -i '^voxy.*\.jar$')
if [ -n "$jars" ]; then
    printf '%s\n' "$jars" | while IFS= read -r j; do printf 'Voxy jar:     mods/%s\n' "$j"; done
else
    printf 'Voxy jar:     none in mods/ (normal for the dev client)\n'
fi

metal=$(LC_ALL=C grep -a -m1 -F 'Metal device:' "$L" | sed -E 's/.*(Metal device:)/\1/')
if [ -n "$metal" ]; then
    printf '%s\n' "$metal"
else
    printf 'Metal device: not found. Voxy did not start its Metal backend.\n'
fi

if [ "$(count '[VOXY_FORCE_METAL] Voxy enabled')" -gt 0 ]; then
    printf 'Voxy opt-in:  enabled (Metal render path is on)\n'
elif [ "$(count 'Voxy disabled on')" -gt 0 ]; then
    printf 'Voxy opt-in:  WARN: Voxy disabled itself. Add -Dvoxy.forceMetal=true to the JVM arguments.\n'
    WARNS=$((WARNS + 1))
else
    printf 'Voxy opt-in:  no opt-in line found\n'
fi

stopped=$(LC_ALL=C grep -a -c -E 'Stopping!$' "$L" 2>/dev/null)
stopped=${stopped:-0}
if [ "$stopped" -gt 0 ]; then
    printf 'Shutdown:     the game quit normally (log has "Stopping!")\n'
else
    printf 'Shutdown:     no "Stopping!" yet (game still running, crashed, or force quit)\n'
fi

# -------------------------------------------------------- failure signatures
echo
echo "== Failure messages (each count must be 0)"
SIGNATURES=(
    'disabling shaders'
    'was not freed'
    'Cannot have multiple renderers'
    'composite disabled'
    'inject failed'
    'FBO incomplete'
    'bindToGlTexture failed'
    'Metal command buffer ended with status'
    'Not all worlds shutdown'
    'World still in use after'
    'Error shutting down renderer'
    'Error releasing'
    'thread died'
    'Geometry OOM'
    'Section freed while marked as dirty'
    'had an exception'
    'was not alive'
    'Executing had an error'
    'Tried executing on a dead service'
    'Critical error occurred in async processor'
    'OutOfMemoryError'
)
i=0
while [ $i -lt ${#SIGNATURES[@]} ]; do
    sig=${SIGNATURES[$i]}
    n=$(count "$sig")
    if [ "$n" -gt 0 ]; then
        printf '  FAIL %5s  %s\n' "$n" "$sig"
        printf '              first: %s\n' "$(first_match "$sig")"
        FAILS=$((FAILS + 1))
    else
        printf '  ok   %5s  %s\n' "$n" "$sig"
    fi
    i=$((i + 1))
done

# --------------------------------------------------------------- model atlas
echo
echo "== Model atlas"
created=$(count 'model atlas CREATED')
freed=$(count 'model atlas FREED')
diff=$((created - freed))
if [ "$diff" -eq 0 ]; then
    printf '  ok   CREATED=%s FREED=%s\n' "$created" "$freed"
elif [ "$diff" -eq 1 ] && [ "$stopped" -eq 0 ]; then
    printf '  ok   CREATED=%s FREED=%s (one atlas open: fine while you are in a world)\n' "$created" "$freed"
    printf '       Run this again after you quit. Then CREATED must equal FREED.\n'
else
    printf '  FAIL CREATED=%s FREED=%s (after quitting they must be equal)\n' "$created" "$freed"
    FAILS=$((FAILS + 1))
fi

# ------------------------------------------------------------------ info only
echo
echo "== Info only (not failures)"
printf '  %5s  [Metal-CULL] ... back faces of water WILL rasterise\n' "$(count 'back faces of water WILL rasterise')"
printf '         Normal without a shader pack (README: "Log messages checked during testing").\n'
printf '  %5s  Viewport width or height was zero (one per minimize)\n' "$(count 'Viewport width or height was zero')"
printf '  %5s  Voxy section saving still in progress (saves flushed at quit)\n' "$(count 'Voxy section saving still in progress')"
printf '  %5s  Voxy render system construction failed (expected only in a forced-failure test)\n' "$(count 'Voxy render system construction failed')"

# --------------------------------------------------------------- crash files
echo
echo "== Crash files from the last $WINDOW_MIN minutes"
crash_list=$(
    {
        [ -d "$G/crash-reports" ] && find "$G/crash-reports" -maxdepth 1 -type f -name 'crash-*' -mmin -"$WINDOW_MIN" 2>/dev/null
        find "$G" -maxdepth 1 -type f -name 'hs_err_pid*' -mmin -"$WINDOW_MIN" 2>/dev/null
        tmp=${TMPDIR:-/tmp}
        [ -d "$tmp" ] && find "$tmp" -maxdepth 1 -type f -name 'hs_err_pid*' -mmin -"$WINDOW_MIN" 2>/dev/null
    } | sort -u
)
if [ -n "$crash_list" ]; then
    printf '%s\n' "$crash_list" | while IFS= read -r f; do printf '  FAIL %s\n' "$f"; done
    FAILS=$((FAILS + 1))
else
    echo "  ok   none"
fi
ips_list=$(find "$HOME/Library/Logs/DiagnosticReports" -maxdepth 1 -type f -name 'java*' -mmin -"$WINDOW_MIN" 2>/dev/null)
if [ -n "$ips_list" ]; then
    echo "  info macOS crash reports for a Java process (may be another Java app):"
    printf '%s\n' "$ips_list" | while IFS= read -r f; do printf '       %s\n' "$f"; done
fi

# ------------------------------------------------------------------- result
echo
if [ "$FAILS" -gt 0 ]; then
    echo "== Result: FAIL ($FAILS problem(s) above)"
    echo "Attach the saved log, this output and your screenshots to the report."
    exit 1
fi
if [ "$WARNS" -gt 0 ]; then
    echo "== Result: PASS, with $WARNS warning(s) above"
else
    echo "== Result: PASS"
fi
exit 0
