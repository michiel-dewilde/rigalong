#!/usr/bin/env bash
# Build and run the engine tests. No device, no Android SDK, no CMake.
#
#   engine/tests/run_tests.sh [name-filter]
#
# One compiler invocation, about a second. That is deliberate: engine/ exists to
# hold the part of this project that depends on nothing, and a test runner that
# needs a toolchain file or a package manager would undo that on the first line.
# CXX can be overridden; g++ and clang++ both work.
#
# On Windows there is no host compiler in this toolchain -- the NDK's clang
# targets Android, and nothing here targets the laptop. Run these under WSL:
#
#   wsl bash engine/tests/run_tests.sh
#
# CI runs them on Linux directly, which is the copy that has to keep passing.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENGINE="$(cd "$HERE/.." && pwd)"
OUT="${TMPDIR:-/tmp}/bagosynths-engine-tests"

: "${CXX:=g++}"

"$CXX" -std=c++20 -O1 -g -Wall -Wextra -Wno-unused-parameter \
    -fno-omit-frame-pointer \
    -I"$ENGINE/include" \
    -I"$HERE" \
    -o "$OUT" \
    "$ENGINE/src/Graph.cpp" \
    "$ENGINE/src/UsbDescriptors.cpp" \
    "$HERE/harness.cpp" \
    "$HERE/test_graph.cpp" \
    "$HERE/test_paramqueue.cpp" \
    "$HERE/test_sampleformat.cpp" \
    "$HERE/test_usbdescriptors.cpp" \
    "$HERE/test_usbmidi.cpp" \
    "$HERE/test_gain.cpp"

echo "engine tests ($("$CXX" --dumpversion 2>/dev/null || echo "$CXX"))"
exec "$OUT" "$@"
