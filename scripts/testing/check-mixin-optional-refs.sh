#!/bin/bash
# check-mixin-optional-refs.sh: check that Voxy's mixins reference an optional
# mod only from that mod's own mixin package.
# Compatible with bash 3.2 (macOS /bin/bash).
if [ -z "${BASH_VERSION:-}" ]; then echo "Run this script with bash, for example: bash $0" >&2; exit 2; fi
set -u

usage() {
    cat <<'EOF'
Usage: bash check-mixin-optional-refs.sh [JAR]

Checks the mixin classes in a Voxy jar for references to optional mods.

When Mixin applies a mixin, it looks up the class of every method and field
the mixin's code uses. If that class belongs to a mod that is not installed,
the target class fails to load and the game crashes, even if the code never
runs. This happened with Iris (finding H5): MixinDefaultChunkRenderer called
Iris directly, so every world join crashed for players without Iris.

A mixin may use an optional mod only from that mod's own mixin package. Those
mixins target the mod's classes, so Mixin skips them when the mod is missing:
  me/cortex/voxy/client/mixin/iris/        Iris
  me/cortex/voxy/client/mixin/flashback/   Flashback
  me/cortex/voxy/client/mixin/nvidium/     Nvidium
  me/cortex/voxy/commonImpl/mixin/chunky/  Chunky

Optional mods checked: Iris (net/irisshaders, net/coderbot), Nvidium
(me/cortex/nvidium), Flashback (com/moulberry/flashback), Chunky
(org/popcraft/chunky), Vivecraft (org/vivecraft) and Mod Menu
(com/terraformersmc).

JAR defaults to the only build/libs/voxy-*.jar of this repository, so run it
after ./gradlew build. It needs javap from a JDK: javap on the PATH, the JDK
that /usr/libexec/java_home finds, or JAVAP=/path/to/javap.

Exit status: 0 = pass, 1 = at least one mixin references an optional mod,
2 = setup error.
EOF
}

case ${1:-} in
    -h|--help) usage; exit 0 ;;
    -*) printf 'Unknown option: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
esac
[ $# -le 1 ] || { usage >&2; exit 2; }

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/lib.sh" || exit 2

OPTIONAL_RE='(net/irisshaders|net/coderbot|me/cortex/nvidium|com/moulberry/flashback|org/popcraft/chunky|org/vivecraft|com/terraformersmc)[A-Za-z0-9_/$]*'

echo "== Mixin check: references to optional mods"

# ------------------------------------------------------------------- the jar
JAR=${1:-}
if [ -z "$JAR" ]; then
    root=$(voxy_find_repo_root "$VOXY_SCRIPT_DIR") \
        || voxy_die "This script is not inside the Voxy repository. Pass the jar: bash $(basename "$0") path/to/voxy.jar"
    n=0
    for j in "$root"/build/libs/voxy-*.jar; do
        [ -f "$j" ] || continue
        case $j in *-sources.jar|*-dev.jar) continue ;; esac
        n=$((n + 1))
        JAR=$j
    done
    [ "$n" -eq 1 ] || voxy_die "Expected one build/libs/voxy-*.jar, found $n. Run ./gradlew build first, or pass the jar."
fi
case $JAR in
    "~/"*) JAR="$HOME/${JAR#"~/"}" ;;
esac
[ -f "$JAR" ] || voxy_die "Jar not found: $JAR"
printf 'Jar:          %s\n' "$JAR"

# --------------------------------------------------------------------- javap
JAVAP_BIN=${JAVAP:-}
if [ -z "$JAVAP_BIN" ]; then
    # macOS has a /usr/bin/javap stub even without a JDK; -version fails on it.
    if command -v javap >/dev/null 2>&1 && javap -version >/dev/null 2>&1; then
        JAVAP_BIN=javap
    elif jh=$(/usr/libexec/java_home 2>/dev/null) && [ -x "$jh/bin/javap" ]; then
        JAVAP_BIN="$jh/bin/javap"
    fi
fi
[ -n "$JAVAP_BIN" ] || voxy_die "javap not found. Install a JDK, or set JAVAP=/path/to/javap."
"$JAVAP_BIN" -version >/dev/null 2>&1 || voxy_die "javap does not run: $JAVAP_BIN"
printf 'javap:        %s (%s)\n' "$JAVAP_BIN" "$("$JAVAP_BIN" -version 2>&1 | head -1)"

# ------------------------------------------------------------ mixin classes
TMP=$(mktemp -d "${TMPDIR:-/tmp}/voxy-mixin-check.XXXXXX") || voxy_die "mktemp failed"
trap 'rm -rf "$TMP"' EXIT

# Exit code 11 only means one of the two patterns matched nothing.
unzip -q -o "$JAR" 'me/cortex/voxy/client/mixin/*' 'me/cortex/voxy/commonImpl/mixin/*' -d "$TMP" >/dev/null 2>&1
find "$TMP" -type f -name '*.class' | LC_ALL=C sort > "$TMP/all.txt"

scanned=0
skipped=0
files=()
while IFS= read -r f; do
    rel=${f#"$TMP"/}
    case $rel in
        me/cortex/voxy/client/mixin/iris/*|me/cortex/voxy/client/mixin/flashback/*|\
        me/cortex/voxy/client/mixin/nvidium/*|me/cortex/voxy/commonImpl/mixin/chunky/*)
            skipped=$((skipped + 1)) ;;
        *)
            files[${#files[@]}]=$f
            scanned=$((scanned + 1)) ;;
    esac
done < "$TMP/all.txt"

printf 'Mixin classes: %s scanned, %s skipped (in an optional mod'"'"'s own package)\n' "$scanned" "$skipped"
[ "$scanned" -gt 0 ] || voxy_die "No mixin classes under me/cortex/voxy/{client,commonImpl}/mixin in this jar. Is it a Voxy jar?"

# One javap run for all classes. Every class starts with "Classfile <path>".
if ! "$JAVAP_BIN" -v -p "${files[@]}" > "$TMP/javap.txt" 2> "$TMP/javap.err"; then
    head -5 "$TMP/javap.err" >&2
    voxy_die "javap failed on the extracted classes."
fi

LC_ALL=C awk -v re="$OPTIONAL_RE" '
    /^Classfile / {
        cur = $0
        i = index(cur, "me/cortex/voxy/")
        cur = (i > 0) ? substr(cur, i) : substr(cur, 11)
        next
    }
    {
        line = $0
        while (match(line, re)) {
            print cur "\t" substr(line, RSTART, RLENGTH)
            line = substr(line, RSTART + RLENGTH)
        }
    }
' "$TMP/javap.txt" | LC_ALL=C sort -u > "$TMP/hits.txt"

# ------------------------------------------------------------------- result
echo
if [ ! -s "$TMP/hits.txt" ]; then
    echo "  ok   no mixin outside an optional mod's own package references an optional mod"
    echo
    echo "== Result: PASS"
    exit 0
fi

culprits=$(cut -f1 "$TMP/hits.txt" | LC_ALL=C sort -u)
count=$(printf '%s\n' "$culprits" | grep -c .)
printf '%s\n' "$culprits" | while IFS= read -r c; do
    printf '  FAIL %s\n' "$c"
    awk -F'\t' -v c="$c" '$1 == c { print "         uses " $2 }' "$TMP/hits.txt"
done
echo
echo "Without that mod installed, the mixin's target class fails to load and the game crashes."
echo "Move the code that uses the mod into a plain class and call that from the mixin"
echo "(example: me.cortex.voxy.client.core.util.MetalVxResolveHooks)."
echo
echo "== Result: FAIL ($count mixin class(es) reference an optional mod)"
exit 1
