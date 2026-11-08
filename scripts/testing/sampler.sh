#!/bin/bash
# sampler.sh: record the game's memory, CPU and thread count over time.
# Compatible with bash 3.2 (macOS /bin/bash).
if [ -z "${BASH_VERSION:-}" ]; then echo "Run this script with bash, for example: bash $0" >&2; exit 2; fi
set -u
# C locale: ps and footprint must print "12.5", not "12,5", inside a CSV.
export LC_ALL=C

usage() {
    cat <<'EOF'
Usage: bash sampler.sh [options]

Finds the running Minecraft Java process and samples it until the game
exits or you press Ctrl+C. Start the game first. A world does not need to
be open yet.

Every INTERVAL seconds it appends one line to
  ~/voxy-test-results/sampler-<date>.csv
with these columns:
  time          local time of the sample
  rss_mb        resident memory (ps)
  cpu_pct       CPU use from ps; 100 means one full core
  os_threads    threads the OS sees in the process
  footprint_mb  memory footprint from /usr/bin/footprint (empty if missing)
  pid           process ID, so several sessions in one file stay apart

On the first sample and every Nth sample after it, it also appends Java
thread names, grouped and counted, to
  ~/voxy-test-results/sampler-<date>-threads.txt
This needs jcmd. It uses the jcmd next to the game's java first, then the
one on your PATH. Without jcmd the CSV still works.

Options:
  -i, --interval SECONDS   time between samples (default 30)
  -t, --threads-every N    thread names every N samples (default 5, 0 = off)
  -p, --pid PID            sample this process instead of searching
  -o, --out DIR            output folder (default ~/voxy-test-results,
                           or $VOXY_RESULTS_DIR if set)
  -h, --help               show this help

Exit status: 0 = sampled until the game exited or Ctrl+C,
             1 = no game process found, 2 = bad option.
EOF
}

INTERVAL=30
THREADS_EVERY=5
PID=""
OUTDIR=""

need_value() {
    [ $# -ge 2 ] && [ -n "$2" ] || { printf 'Option %s needs a value.\n' "$1" >&2; exit 2; }
}
while [ $# -gt 0 ]; do
    case $1 in
        -h|--help) usage; exit 0 ;;
        -i|--interval) need_value "$@"; INTERVAL=$2; shift 2 ;;
        -t|--threads-every) need_value "$@"; THREADS_EVERY=$2; shift 2 ;;
        -p|--pid) need_value "$@"; PID=$2; shift 2 ;;
        -o|--out) need_value "$@"; OUTDIR=$2; shift 2 ;;
        *) printf 'Unknown argument: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done

is_uint() {
    case $1 in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac
}
is_uint "$INTERVAL" && [ "$INTERVAL" -ge 1 ] || { echo "--interval must be a whole number of seconds, 1 or more." >&2; exit 2; }
is_uint "$THREADS_EVERY" || { echo "--threads-every must be a whole number (0 turns it off)." >&2; exit 2; }
[ -z "$PID" ] || is_uint "$PID" || { echo "--pid must be a number." >&2; exit 2; }

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/lib.sh" || exit 2
OUTDIR=${OUTDIR:-$VOXY_RESULTS_DIR}

# ------------------------------------------------------------ find the game
if [ -n "$PID" ]; then
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "No running process with PID $PID that you can access."
        exit 1
    fi
else
    if ! list=$(voxy_game_pids); then
        echo "No running Minecraft Java process found."
        echo "Start the game first, then run this script again in another Terminal tab."
        echo "If your launcher is not detected, pass the PID: bash sampler.sh --pid <PID>"
        echo "  (find it in Activity Monitor: the 'java' process, PID column)"
        exit 1
    fi
    PID=$(voxy_newest_game_pid)
    count=$(printf '%s\n' "$list" | wc -l | tr -d ' ')
    if [ "$count" -gt 1 ]; then
        echo "Found $count game processes. Using the newest one, PID $PID."
        echo "Use --pid to choose another: $(printf '%s\n' "$list" | awk '{print $1}' | tr '\n' ' ')"
    fi
fi

COMM=$(ps -o comm= -p "$PID" 2>/dev/null)
FLAGS=$(ps -ww -o args= -p "$PID" 2>/dev/null | tr ' ' '\n' | grep -E '^-(Xmx|Xms|Dvoxy\.)' | tr '\n' ' ' | sed 's/ $//')

# ------------------------------------------------------------- pick tools
find_jcmd() {
    local dir cand
    case $COMM in
        /*)
            dir=$(dirname "$COMM")
            if [ -x "$dir/jcmd" ]; then
                printf '%s\n' "$dir/jcmd"
                return 0
            fi
            ;;
    esac
    cand=$(command -v jcmd 2>/dev/null) || return 1
    # /usr/bin/jcmd is a macOS stub. Without a JDK it opens an install dialog.
    if [ "$cand" = "/usr/bin/jcmd" ]; then
        /usr/libexec/java_home >/dev/null 2>&1 || return 1
    fi
    printf '%s\n' "$cand"
}
JCMD=""
if [ "$THREADS_EVERY" -gt 0 ]; then
    JCMD=$(find_jcmd) || JCMD=""
fi
FOOTPRINT=""
[ -x /usr/bin/footprint ] && FOOTPRINT=/usr/bin/footprint

mkdir -p "$OUTDIR" || { echo "Cannot create output folder: $OUTDIR" >&2; exit 2; }
DAY=$(date +%Y-%m-%d)
CSV="$OUTDIR/sampler-$DAY.csv"
TXT="$OUTDIR/sampler-$DAY-threads.txt"
[ -s "$CSV" ] || echo "time,rss_mb,cpu_pct,os_threads,footprint_mb,pid" > "$CSV"

echo "Sampling PID $PID every $INTERVAL s. Press Ctrl+C to stop."
echo "  java:   $COMM"
[ -n "$FLAGS" ] && echo "  flags:  $FLAGS"
echo "  csv:    $CSV"
if [ -n "$JCMD" ]; then
    echo "  threads: $TXT (every $THREADS_EVERY samples, jcmd: $JCMD)"
    printf '##### session start %s  pid %s  java %s  flags: %s\n\n' \
        "$(date '+%Y-%m-%d %H:%M:%S')" "$PID" "$COMM" "${FLAGS:-none}" >> "$TXT"
elif [ "$THREADS_EVERY" -gt 0 ]; then
    echo "  threads: skipped, jcmd not found (install a JDK to get thread names)"
fi
[ -n "$FOOTPRINT" ] || echo "  footprint: /usr/bin/footprint not found, column left empty"

# ------------------------------------------------------------ signals
STOP=0
JCMD_PID=""
JCMD_TMP=""
on_signal() { STOP=1; }
cleanup() {
    [ -n "$JCMD_PID" ] && kill "$JCMD_PID" 2>/dev/null
    [ -n "$JCMD_TMP" ] && rm -f "$JCMD_TMP"
}
trap on_signal INT TERM HUP
trap cleanup EXIT

# ------------------------------------------------------------ thread names
thread_dump() {
    local stamp=$1 sample=$2 threads=$3 waited=0
    JCMD_TMP=$(mktemp -t voxy-jcmd) || return 0
    "$JCMD" "$PID" Thread.print > "$JCMD_TMP" 2>&1 &
    JCMD_PID=$!
    while kill -0 "$JCMD_PID" 2>/dev/null; do
        if [ "$waited" -ge 30 ] || [ "$STOP" -ne 0 ]; then
            kill "$JCMD_PID" 2>/dev/null
            break
        fi
        sleep 1
        waited=$((waited + 1))
    done
    wait "$JCMD_PID" 2>/dev/null
    JCMD_PID=""
    {
        printf '=== %s  pid %s  sample %s  os_threads %s\n' "$stamp" "$PID" "$sample" "$threads"
        if grep -q '^"' "$JCMD_TMP"; then
            grep -oE '^"[^"]+' "$JCMD_TMP" | sed -E 's/^"//; s/[0-9#]+/#/g' | sort | uniq -c | sort -rn
            printf '  (java threads in dump: %s)\n' "$(grep -c '^"' "$JCMD_TMP")"
        else
            printf '  jcmd gave no thread list: %s\n' "$(head -3 "$JCMD_TMP" | tr '\n' ' ')"
        fi
        echo
    } >> "$TXT"
    rm -f "$JCMD_TMP"
    JCMD_TMP=""
}

# ------------------------------------------------------------ sample loop
N=0
PEAK_RSS=0
PEAK_THR=0
REASON="stopped by Ctrl+C"
while [ "$STOP" -eq 0 ]; do
    if ! kill -0 "$PID" 2>/dev/null; then
        REASON="game process $PID exited"
        break
    fi
    rss_kb=$(ps -o rss= -p "$PID" 2>/dev/null | tr -d ' ')
    cpu=$(ps -o %cpu= -p "$PID" 2>/dev/null | tr -d ' ')
    thr=$(ps -M -p "$PID" 2>/dev/null | wc -l | tr -d ' ')
    if [ -z "$rss_kb" ] || [ -z "$thr" ] || [ "$thr" -le 1 ]; then
        REASON="game process $PID exited"
        break
    fi
    thr=$((thr - 1))
    rss_mb=$((rss_kb / 1024))
    fp=""
    if [ -n "$FOOTPRINT" ]; then
        fp=$("$FOOTPRINT" -p "$PID" 2>/dev/null | grep -m1 -oE 'Footprint: [0-9.]+ [KMG]?B' |
            awk '{ v = $2; u = $3
                   if (u == "KB") v = v / 1024; else if (u == "GB") v = v * 1024; else if (u == "B") v = v / 1048576
                   printf "%.0f", v }')
    fi
    [ "$STOP" -eq 0 ] || break
    stamp=$(date '+%Y-%m-%d %H:%M:%S')
    N=$((N + 1))
    printf '%s,%s,%s,%s,%s,%s\n' "$stamp" "$rss_mb" "$cpu" "$thr" "$fp" "$PID" >> "$CSV"
    printf '%s  rss %6s MB  cpu %6s%%  threads %4s  footprint %6s MB\n' \
        "$stamp" "$rss_mb" "$cpu" "$thr" "${fp:--}"
    [ "$rss_mb" -gt "$PEAK_RSS" ] && PEAK_RSS=$rss_mb
    [ "$thr" -gt "$PEAK_THR" ] && PEAK_THR=$thr

    if [ -n "$JCMD" ] && [ $(( (N - 1) % THREADS_EVERY )) -eq 0 ]; then
        thread_dump "$stamp" "$N" "$thr"
    fi

    i=0
    while [ "$i" -lt "$INTERVAL" ] && [ "$STOP" -eq 0 ]; do
        sleep 1
        i=$((i + 1))
        kill -0 "$PID" 2>/dev/null || break
    done
done

echo
echo "Stopped: $REASON."
echo "Samples: $N. Peak RSS: $PEAK_RSS MB. Peak OS threads: $PEAK_THR."
echo "CSV: $CSV"
[ -n "$JCMD" ] && [ -f "$TXT" ] && echo "Thread names: $TXT"
exit 0
