# lib.sh: shared helpers for the Voxy testing scripts.
# Source this file from a script in the same folder. Do not run it directly.
# Compatible with bash 3.2 (the /bin/bash that ships with macOS).

# Folder that holds these scripts.
VOXY_SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)

# Where sampler.sh and collect-report.sh write their files.
VOXY_RESULTS_DIR=${VOXY_RESULTS_DIR:-"$HOME/voxy-test-results"}

# One pgrep -f pattern for the game's Java process:
#   dev client (./gradlew runClient): net.fabricmc.devlaunchinjector.Main ... KnotClient
#   Fabric in a launcher:             net.fabricmc.loader.impl.launch.knot.KnotClient
#   vanilla Minecraft:                net.minecraft.client.main.Main
#   Prism Launcher / MultiMC:         org.prismlauncher.EntryPoint / org.multimc.EntryPoint
VOXY_GAME_PATTERN='devlaunchinjector|KnotClient|net\.minecraft\.client\.main\.Main|org\.(prismlauncher|multimc)\.EntryPoint'

voxy_die() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 2
}

# Find this repository's root by walking up from the scripts folder.
# A folder counts as the repo root when it has gradlew and Voxy's sources.
# Plain files only, so no git install is needed.
voxy_find_repo_root() {
    local d=$1
    while [ -n "$d" ] && [ "$d" != "/" ]; do
        if [ -f "$d/gradlew" ] && [ -d "$d/src/main/java/me/cortex/voxy" ]; then
            printf '%s\n' "$d"
            return 0
        fi
        d=$(dirname "$d")
    done
    return 1
}

# Pick the game folder. Sets VOXY_GAME and VOXY_GAME_SOURCE.
# Order: argument, then $VOXY_GAME_DIR, then <repo>/run, then the official launcher folder.
# Exits with status 2 if the folder or its logs/latest.log is missing.
voxy_resolve_game_dir() {
    local arg=${1:-} dir src root sub
    if [ -n "$arg" ]; then
        dir=$arg
        src="command-line argument"
    elif [ -n "${VOXY_GAME_DIR:-}" ]; then
        dir=$VOXY_GAME_DIR
        src="VOXY_GAME_DIR environment variable"
    elif root=$(voxy_find_repo_root "$VOXY_SCRIPT_DIR") && [ -d "$root/run" ]; then
        dir="$root/run"
        src="dev client: run/ folder of this repository"
    else
        dir="$HOME/Library/Application Support/minecraft"
        src="default folder of the official Minecraft Launcher"
    fi

    # Expand a leading ~ that arrived quoted.
    case $dir in
        "~") dir=$HOME ;;
        "~/"*) dir="$HOME/${dir#"~/"}" ;;
    esac

    if [ ! -d "$dir" ]; then
        voxy_die "Game folder not found: $dir (chosen from: $src).
Pass the folder as the first argument, for example:
  bash $(basename "$0") \"\$HOME/Library/Application Support/minecraft\""
    fi
    dir=$(cd "$dir" && pwd -P)

    # Prism Launcher and MultiMC keep the game in a subfolder of the instance.
    if [ ! -f "$dir/logs/latest.log" ]; then
        for sub in minecraft .minecraft; do
            if [ -f "$dir/$sub/logs/latest.log" ]; then
                dir="$dir/$sub"
                break
            fi
        done
    fi

    VOXY_GAME=$dir
    VOXY_GAME_SOURCE=$src
    printf 'Game folder: %s\n' "$VOXY_GAME"
    printf '  (chosen from: %s)\n' "$VOXY_GAME_SOURCE"

    if [ ! -f "$VOXY_GAME/logs/latest.log" ]; then
        cat >&2 <<EOF
ERROR: No logs/latest.log in this folder.
Start the game once with this folder, or pass the right folder:
  bash $(basename "$0") "/path/to/game folder"
  or: export VOXY_GAME_DIR="/path/to/game folder"
Where the game folder is:
  Official launcher: ~/Library/Application Support/minecraft
    (or the "Game directory" set in your launcher profile)
  Prism Launcher, Modrinth App, CurseForge: use the instance's
    "Open folder" button and pass that folder.
  Dev client (./gradlew runClient): the run/ folder of the repository.
EOF
        exit 2
    fi
}

# Elapsed time from "ps -o etime=" ([[dd-]hh:]mm:ss) to seconds.
voxy_etime_seconds() {
    printf '%s\n' "$1" | tr -d ' ' | awk -F'[-:]' '{
        n = NF; s = $n; m = (n >= 2) ? $(n-1) : 0
        h = (n >= 3) ? $(n-2) : 0; d = (n >= 4) ? $(n-3) : 0
        print ((d * 24 + h) * 60 + m) * 60 + s }'
}

# Print "pid age_seconds" for each running game Java process, one per line.
# Returns 1 if there is none.
voxy_game_pids() {
    local pids pid comm et found=1
    pids=$(pgrep -f "$VOXY_GAME_PATTERN" 2>/dev/null) || return 1
    for pid in $pids; do
        comm=$(ps -o comm= -p "$pid" 2>/dev/null) || continue
        case ${comm##*/} in
            java) ;;
            *) continue ;;
        esac
        et=$(ps -o etime= -p "$pid" 2>/dev/null) || continue
        printf '%s %s\n' "$pid" "$(voxy_etime_seconds "$et")"
        found=0
    done
    return $found
}

# Print the PID of the newest running game Java process. Returns 1 if none.
voxy_newest_game_pid() {
    local list
    list=$(voxy_game_pids) || return 1
    printf '%s\n' "$list" | sort -k2,2n | head -1 | awk '{print $1}'
}

# File modification time as "YYYY-MM-DD HH:MM".
voxy_mtime() {
    stat -f '%Sm' -t '%Y-%m-%d %H:%M' "$1" 2>/dev/null
}
