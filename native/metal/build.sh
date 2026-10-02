#!/usr/bin/env bash
# Convenience script for building libvoxy_metal.dylib on macOS (Apple Silicon).
# Usage: ./build.sh [Debug|Release]
#
# Requires: Xcode command-line tools (clang++, metal), CMake >= 3.20, JDK 21.
# Output:  src/main/resources/natives/macos-arm64/libvoxy_metal.dylib

set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "error: libvoxy_metal only builds on macOS (got $(uname -s))" >&2
  exit 1
fi
if [[ "$(uname -m)" != "arm64" ]]; then
  echo "warning: host arch is $(uname -m); building arm64 slice regardless" >&2
fi

BUILD_TYPE="${1:-Release}"
HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${HERE}/build"

: "${JAVA_HOME:=$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home)}"
export JAVA_HOME
echo "Using JAVA_HOME=${JAVA_HOME}"

cmake -S "${HERE}" -B "${BUILD_DIR}" \
  -DCMAKE_BUILD_TYPE="${BUILD_TYPE}" \
  -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DCMAKE_OSX_DEPLOYMENT_TARGET="${VOXY_MACOS_MIN:-13.0}"

cmake --build "${BUILD_DIR}" --config "${BUILD_TYPE}" --parallel

# Guard the deployment target: a stale CMake cache or an IDE profile can silently build for the
# host OS (the 2026-09 minos 26.0 dylib that disabled Voxy on macOS 13-15).
OUT="${HERE}/../../src/main/resources/natives/macos-arm64/libvoxy_metal.dylib"
MINOS="$(otool -l "${OUT}" | awk '/LC_BUILD_VERSION/{f=1} f&&/minos/{print $2; exit}')"
WANT="${VOXY_MACOS_MIN:-13.0}"
if [[ -z "${MINOS}" ]]; then
  echo "error: could not read minos from ${OUT}" >&2
  exit 1
fi
if [[ "$(printf '%s\n%s\n' "${MINOS}" "${WANT}" | sort -V | tail -1)" != "${WANT}" ]]; then
  echo "error: ${OUT} has minos ${MINOS}, newer than the deployment target ${WANT}" >&2
  echo "       (stale native/metal/build cache? delete native/metal/build and rebuild)" >&2
  exit 1
fi
echo "minos ${MINOS} (deployment target ${WANT})"

echo
echo "Built libvoxy_metal.dylib for ${BUILD_TYPE} (macOS deployment target ${VOXY_MACOS_MIN:-13.0})."
echo "Installed to: src/main/resources/natives/macos-arm64/libvoxy_metal.dylib"
