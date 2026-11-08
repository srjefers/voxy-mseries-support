#!/bin/bash
# settings-dump.sh: print the saved settings that matter for Voxy tests.
# Read only. Compatible with bash 3.2 (macOS /bin/bash).
if [ -z "${BASH_VERSION:-}" ]; then echo "Run this script with bash, for example: bash $0" >&2; exit 2; fi
set -u

usage() {
    cat <<'EOF'
Usage: bash settings-dump.sh [GAME_DIR]

Prints the saved settings that change test results. It changes nothing.
  - Minecraft (options.txt): enableVsync, maxFps, inactivityFpsLimit,
    pauseOnLostFocus, renderDistance, simulationDistance, guiScale, fullscreen
  - Voxy (config/voxy-config.json)
  - Iris (config/iris.properties): enableShaders, shaderPack
  - Voxy's shader cache: ~/.voxy/shader-cache (file count)

Run it with the game closed. The game writes these files when you press
Apply or quit, so a running game can show different values.

GAME_DIR is chosen in this order:
  1. the GAME_DIR argument
  2. the VOXY_GAME_DIR environment variable
  3. the run/ folder, when the script sits inside the Voxy repository
  4. ~/Library/Application Support/minecraft (official launcher)

Exit status: 0 = printed, 2 = setup error.
EOF
}

case ${1:-} in
    -h|--help) usage; exit 0 ;;
    -*) printf 'Unknown option: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
esac
[ $# -le 1 ] || { usage >&2; exit 2; }

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/lib.sh" || exit 2

echo "== Voxy settings dump"
voxy_resolve_game_dir "${1:-}"
G=$VOXY_GAME

if pid=$(voxy_newest_game_pid); then
    echo "Note: the game is running (PID $pid). Files show the last saved values."
fi

# ------------------------------------------------------------------ Minecraft
echo
echo "== Minecraft (options.txt)"
OPT="$G/options.txt"
if [ ! -f "$OPT" ]; then
    echo "  options.txt not found"
else
    for key in enableVsync maxFps inactivityFpsLimit pauseOnLostFocus renderDistance simulationDistance guiScale fullscreen; do
        line=$(grep -m1 -E "^$key:" "$OPT")
        val=${line#*:}
        note=""
        case "$key=$val" in
            enableVsync=true) note="VSync ON: fps snap to 60, 40, 30. Turn it off to measure." ;;
            enableVsync=false) note="VSync OFF" ;;
            maxFps=260) note="Unlimited" ;;
            maxFps=*) note="fps cap (260 = Unlimited)" ;;
            'inactivityFpsLimit="afk"') note="WARN: 30 fps after 60 s idle. Set 'Reduce FPS when' to Minimized to measure." ;;
            'inactivityFpsLimit="minimized"') note="ok for measuring" ;;
            pauseOnLostFocus=true) note="game pauses when you click another window (toggle: F3+P)" ;;
            pauseOnLostFocus=false) note="no pause on lost focus" ;;
            renderDistance=*|simulationDistance=*) note="chunks" ;;
            guiScale=0) note="Auto" ;;
        esac
        if [ -z "$line" ]; then
            printf '  %-32s %s\n' "$key:" "(not set)"
        elif [ -n "$note" ]; then
            printf '  %-32s %s\n' "$line" "$note"
        else
            printf '  %s\n' "$line"
        fi
    done
fi

# ----------------------------------------------------------------------- Voxy
echo
echo "== Voxy (config/voxy-config.json)"
VCFG="$G/config/voxy-config.json"
if [ ! -f "$VCFG" ]; then
    echo "  voxy-config.json not found (Voxy has not run in this folder yet)"
else
    sed 's/^/  /' "$VCFG"
    echo
    json_val() {
        grep -m1 -E "\"$1\"[[:space:]]*:" "$VCFG" | sed -E 's/.*:[[:space:]]*//; s/[[:space:]]*,?[[:space:]]*$//'
    }
    echo "  How these appear in the Voxy menu:"
    rd=$(json_val section_render_distance)
    case $rd in
        ''|*[!0-9]*) ;;
        *) printf '    section_render_distance %s = "Render distance" %s\n' "$rd" "$((rd * 32))" ;;
    esac
    sd=$(json_val sub_division_size)
    [ -n "$sd" ] && printf '    sub_division_size %s: the menu can show about 1 less (rounding)\n' "$sd"
    ef=$(json_val use_environmental_fog)
    [ -n "$ef" ] && printf '    use_environmental_fog %s = "Enable environmental fog" (default true)\n' "$ef"
    ds=$(json_val dont_use_sodium_builder_threads)
    case $ds in
        false) echo '    dont_use_sodium_builder_threads false = "Use sodium threads" ON' ;;
        true) echo '    dont_use_sodium_builder_threads true = "Use sodium threads" OFF' ;;
    esac
    st=$(json_val service_threads)
    [ -n "$st" ] && printf '    service_threads %s = "Service threads"\n' "$st"
    ie=$(json_val ingest_enabled)
    [ -n "$ie" ] && printf '    ingest_enabled %s = "Chunk Ingest"\n' "$ie"
fi

# ----------------------------------------------------------------------- Iris
echo
echo "== Iris (config/iris.properties)"
IRIS="$G/config/iris.properties"
if [ ! -f "$IRIS" ]; then
    echo "  iris.properties not found (Iris is not installed, or has not run yet)"
else
    found=$(grep -E '^(enableShaders|shaderPack)=' "$IRIS")
    if [ -n "$found" ]; then
        printf '%s\n' "$found" | sed 's/^/  /'
    else
        echo "  enableShaders and shaderPack are not set"
    fi
fi

# ------------------------------------------------------------- shader cache
echo
CACHE="$HOME/.voxy/shader-cache"
echo "== Voxy shader cache (~/.voxy/shader-cache)"
if [ -d "$CACHE" ]; then
    total=$(find "$CACHE" -type f | wc -l | tr -d ' ')
    spv=$(find "$CACHE" -type f -name '*.spv' | wc -l | tr -d ' ')
    msl=$(find "$CACHE" -type f -name '*.metal' | wc -l | tr -d ' ')
    printf '  %s files (%s .spv, %s .metal)\n' "$total" "$spv" "$msl"
    echo "  0 right after you delete the folder. The game fills it on first use."
else
    echo "  not found (0 files). Voxy creates it the first time it compiles shaders."
fi
exit 0
