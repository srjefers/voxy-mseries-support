#!/bin/bash
# collect-report.sh: gather everything a Voxy test report needs into one zip.
# Read only for the game folder. Compatible with bash 3.2 (macOS /bin/bash).
if [ -z "${BASH_VERSION:-}" ]; then echo "Run this script with bash, for example: bash $0" >&2; exit 2; fi
set -u

usage() {
    cat <<'EOF'
Usage: bash collect-report.sh [--redact] [--out DIR] [GAME_DIR]

Run it after you quit the game. It creates
  ~/voxy-test-results/report-<date>_<time>/
and a .zip of that folder next to it. Attach the .zip to your GitHub report.

The folder holds:
  system.txt       macOS version, Mac model, chip, memory, GPU cores, power
  versions.txt     Minecraft, Fabric, Voxy, Sodium, Iris, Java and Metal device
                   lines from the log, plus the mod list and the jars in mods/
  log-check.txt    output of log-check.sh
  settings.txt     output of settings-dump.sh
  latest.log       copy of logs/latest.log
  crash files      the newest crash report and hs_err_pid*.log, only if they
                   are less than 60 minutes old
  sampler files    sampler.sh output changed in the last 2 hours
  screenshots.txt  names of screenshots from the last 2 hours (not copies;
                   attach the ones that show the problem yourself)

Privacy: the log contains your Minecraft name and file paths that include
your Mac user name. --redact replaces them in the report copies before
zipping. Your game files are never changed.

Options:
  --redact        replace your home folder path, Mac user name and Minecraft
                  name in the report files
  -o, --out DIR   results folder (default ~/voxy-test-results, or
                  $VOXY_RESULTS_DIR if set)
  -h, --help      show this help

GAME_DIR is chosen in this order:
  1. the GAME_DIR argument
  2. the VOXY_GAME_DIR environment variable
  3. the run/ folder, when the script sits inside the Voxy repository
  4. ~/Library/Application Support/minecraft (official launcher)

Exit status: 0 = report created, 2 = setup error.
EOF
}

REDACT=0
OUTDIR=""
GAME_ARG=""
while [ $# -gt 0 ]; do
    case $1 in
        -h|--help) usage; exit 0 ;;
        --redact) REDACT=1; shift ;;
        -o|--out)
            [ $# -ge 2 ] && [ -n "$2" ] || { echo "Option $1 needs a value." >&2; exit 2; }
            OUTDIR=$2; shift 2 ;;
        -*) printf 'Unknown option: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
        *)
            [ -z "$GAME_ARG" ] || { echo "Only one GAME_DIR, please." >&2; exit 2; }
            GAME_ARG=$1; shift ;;
    esac
done

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/lib.sh" || exit 2
OUT_ARG=$OUTDIR
OUTDIR=${OUTDIR:-$VOXY_RESULTS_DIR}

echo "== Voxy test report"
voxy_resolve_game_dir "$GAME_ARG"
G=$VOXY_GAME
L="$G/logs/latest.log"
WINDOW_MIN=60
SHOT_MIN=120

if pid=$(voxy_newest_game_pid); then
    echo "WARN: the game is still running (PID $pid). Quit it first for a complete log."
    echo "      Continuing anyway."
fi

STAMP=$(date +%Y-%m-%d_%H-%M-%S)
mkdir -p "$OUTDIR" || voxy_die "Cannot create results folder: $OUTDIR"
OUTDIR=$(cd "$OUTDIR" && pwd -P)
R="$OUTDIR/report-$STAMP"
mkdir "$R" || voxy_die "Cannot create report folder: $R"
echo "Report folder: $R"

# Newest file among find results ("" if none). Handles spaces in paths.
newest() {
    "$@" -exec stat -f '%m %N' {} + 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-
}

# ------------------------------------------------------------- system.txt
{
    echo "== macOS"
    sw_vers
    echo
    echo "== Hardware"
    echo "Model:       $(sysctl -n hw.model 2>/dev/null)"
    echo "Chip:        $(sysctl -n machdep.cpu.brand_string 2>/dev/null)"
    echo "Arch:        $(uname -m)"
    p=$(sysctl -n hw.perflevel0.physicalcpu 2>/dev/null)
    e=$(sysctl -n hw.perflevel1.physicalcpu 2>/dev/null)
    echo "CPU cores:   $(sysctl -n hw.ncpu 2>/dev/null) (performance ${p:-?}, efficiency ${e:-?})"
    mem=$(sysctl -n hw.memsize 2>/dev/null)
    case $mem in
        ''|*[!0-9]*) echo "Memory:      unknown" ;;
        *) echo "Memory:      $((mem / 1073741824)) GB" ;;
    esac
    echo
    echo "== GPU and displays"
    system_profiler SPDisplaysDataType 2>/dev/null |
        grep -E 'Chipset Model|Total Number of Cores|Metal Support|Metal Family|Display Type|Resolution|UI Looks like|Main Display' |
        sed -E 's/^[[:space:]]+/  /'
    echo
    echo "== Power"
    pmset -g batt 2>/dev/null | head -1
    pmset -g 2>/dev/null | grep -E '^[[:space:]]*(lowpowermode|powermode)[[:space:]]' | sed -E 's/^[[:space:]]+/  /'
    echo "  (lowpowermode 1 or powermode 1 = Low Power Mode on)"
    echo
    echo "== Uptime"
    uptime
} > "$R/system.txt" 2>&1

# ----------------------------------------------------------- versions.txt
{
    echo "== From logs/latest.log"
    LC_ALL=C grep -a -m1 -E 'Loading Minecraft .* with Fabric Loader' "$L" | sed -E 's/.*(Loading Minecraft)/\1/'
    LC_ALL=C grep -a -m1 -E 'Loading [0-9]+ mods:' "$L" | sed -E 's/.*(Loading [0-9]+ mods:)/\1/'
    LC_ALL=C grep -a -E '^[[:space:]]+- (minecraft|fabricloader|fabric-api|java|voxy|sodium|iris|voxyworldgenv2) ' "$L" | sed -E 's/^[[:space:]]+/  /'
    LC_ALL=C grep -a -m1 -F 'Metal device:' "$L" | sed -E 's/.*(Metal device:)/\1/'
    LC_ALL=C grep -a -m1 -E 'Render backend: ' "$L" | sed -E 's/.*(Render backend: )/\1/'
    LC_ALL=C grep -a -m1 -E '\[VOXY_FORCE_METAL\] Voxy enabled|Voxy disabled on' "$L" | sed -E 's/.*(\[VOXY_FORCE_METAL\] Voxy enabled on [A-Z]+ backend|\[M9 TRANSITIONAL\] Voxy disabled on [A-Z]+ backend).*/\1/'
    LC_ALL=C grep -a -m1 -F 'OpenGL Renderer:' "$L" | sed -E 's/.*(OpenGL Renderer:)/\1/'
    echo
    echo "== All mods in the log (top level)"
    LC_ALL=C grep -a -E '^[[:space:]]+- [^ ]+ ' "$L" | sed -E 's/^[[:space:]]+/  /'
    echo
    echo "== Jars in mods/"
    if [ -d "$G/mods" ]; then
        jars=$(cd "$G/mods" && ls -1 | grep -i '\.jar$')
        if [ -n "$jars" ]; then
            printf '%s\n' "$jars" | sed 's/^/  /'
        else
            echo "  (none: normal for the dev client, which loads mods from the build)"
        fi
    else
        echo "  (no mods folder)"
    fi
} > "$R/versions.txt" 2>&1

# --------------------------------------------------- log check + settings
bash "$VOXY_SCRIPT_DIR/log-check.sh" "$G" > "$R/log-check.txt" 2>&1
LC_STATUS=$?
bash "$VOXY_SCRIPT_DIR/settings-dump.sh" "$G" > "$R/settings.txt" 2>&1

# -------------------------------------------------------------- copies
cp "$L" "$R/latest.log" || echo "WARN: could not copy $L"
COPIED="latest.log"

crash=$(newest find "$G/crash-reports" -maxdepth 1 -type f -name 'crash-*' -mmin -"$WINDOW_MIN")
if [ -n "$crash" ]; then
    cp "$crash" "$R/" && COPIED="$COPIED, $(basename "$crash")"
fi
hserr=$(newest find "$G" "${TMPDIR:-/tmp}" -maxdepth 1 -type f -name 'hs_err_pid*' -mmin -"$WINDOW_MIN")
if [ -n "$hserr" ]; then
    cp "$hserr" "$R/" && COPIED="$COPIED, $(basename "$hserr")"
fi
if [ -d "$OUTDIR" ]; then
    samplers=$(find "$OUTDIR" -maxdepth 1 -type f -name 'sampler-*' -mmin -"$SHOT_MIN" 2>/dev/null)
    if [ -n "$samplers" ]; then
        while IFS= read -r f; do
            cp "$f" "$R/" && COPIED="$COPIED, $(basename "$f")"
        done <<EOF
$samplers
EOF
    fi
fi

# ------------------------------------------------------ screenshots list
{
    echo "Screenshots from the last 2 hours in $G/screenshots"
    echo "(not copied: attach the ones that show the problem to your report)"
    echo
    if [ -d "$G/screenshots" ]; then
        shots=$(cd "$G/screenshots" && find . -maxdepth 1 -type f -mmin -"$SHOT_MIN" \
            -exec stat -f '%Sm  %N' -t '%Y-%m-%d %H:%M:%S' {} + 2>/dev/null | sed 's|  \./|  |' | sort)
        if [ -n "$shots" ]; then
            printf '%s\n' "$shots"
        else
            echo "none"
        fi
    else
        echo "no screenshots folder"
    fi
} > "$R/screenshots.txt"
SHOT_COUNT=$(grep -c '^[0-9]' "$R/screenshots.txt")

# ------------------------------------------------------------------ redact
MC_NAME=$(LC_ALL=C grep -a -m1 -E 'Setting user: ' "$L" | sed -E 's/.*Setting user: //' | tr -d '\r')
MAC_USER=$(id -un 2>/dev/null)
# Escape a fixed string for a sed -E regex that uses | as the delimiter.
sed_escape() {
    printf '%s' "$1" | sed -e 's/[][\.*^$|+?(){}]/\\&/g'
}
if [ "$REDACT" -eq 1 ]; then
    set --
    set -- -e "s|$(sed_escape "$HOME")|~|g"
    if [ ${#MAC_USER} -ge 3 ]; then
        set -- "$@" -e "s|[[:<:]]$(sed_escape "$MAC_USER")[[:>:]]|<user>|g"
    fi
    if [ ${#MC_NAME} -ge 3 ]; then
        set -- "$@" -e "s|[[:<:]]$(sed_escape "$MC_NAME")[[:>:]]|<player>|g"
    fi
    for f in "$R"/*; do
        [ -f "$f" ] || continue
        LC_ALL=C sed -E -i '' "$@" "$f"
    done
fi

# --------------------------------------------------------------------- zip
ZIP="$R.zip"
if command -v zip >/dev/null 2>&1; then
    (cd "$OUTDIR" && zip -q -r -X "$(basename "$ZIP")" "$(basename "$R")")
else
    ditto -c -k --norsrc --noextattr --keepParent "$R" "$ZIP"
fi
[ -f "$ZIP" ] || voxy_die "Could not create the zip. The folder is still there: $R"

# ----------------------------------------------------------------- summary
echo
echo "Files: system.txt, versions.txt, log-check.txt, settings.txt, screenshots.txt"
echo "Copied: $COPIED"
if [ "$LC_STATUS" -eq 0 ]; then
    echo "Log check: PASS (see log-check.txt)"
elif [ "$LC_STATUS" -eq 1 ]; then
    echo "Log check: FAIL (see log-check.txt)"
else
    echo "Log check: could not run (see log-check.txt)"
fi
echo "Screenshots from the last 2 hours: $SHOT_COUNT (listed in screenshots.txt)"
echo
echo "Zip: $ZIP"
echo "Attach the zip and your screenshots to the GitHub report."
echo
if [ "$REDACT" -eq 1 ]; then
    echo "Privacy: replaced your home folder path, Mac user name and Minecraft name."
    echo "Server addresses you joined can still be in latest.log. Check before you share."
else
    echo "Privacy: latest.log and the other files contain your Minecraft name${MC_NAME:+ ($MC_NAME)},"
    echo "file paths with your Mac user name, and server addresses you joined."
    echo "To replace the name and paths, run this script again with --redact:"
    echo "  bash \"$VOXY_SCRIPT_DIR/collect-report.sh\" --redact${OUT_ARG:+ --out \"$OUT_ARG\"} \"$G\""
    echo "or edit this report in place, then zip it again:"
    printf "  LC_ALL=C sed -E -i '' -e 's|%s|~|g'" "$(sed_escape "$HOME")"
    [ -n "$MC_NAME" ] && printf " -e 's|%s|<player>|g'" "$(sed_escape "$MC_NAME")"
    printf ' "%s"/*\n' "$R"
    echo "  cd \"$OUTDIR\" && rm -f \"$(basename "$ZIP")\" && zip -q -r -X \"$(basename "$ZIP")\" \"$(basename "$R")\""
fi
exit 0
