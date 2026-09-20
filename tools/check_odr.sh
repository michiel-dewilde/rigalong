#!/usr/bin/env bash
# Look for the same symbol defined by more than one dependency.
#
# Why this script exists: sfizz and Surge XT both vendored the Surge Synth Team
# tuning library, at different versions, in the same namespace, with
# incompatible struct layouts. Linked into one shared object the linker keeps
# ONE copy of each inline constructor and destructor -- they share a mangled
# name and a comdat group -- and whichever library loses then operates on the
# other's memory layout. Neither the compiler nor the linker says a word. It
# crashed every launch, deterministically, and took an hour to read.
#
# Run it after adding any large dependency:
#     tools/check_odr.sh
#
# It groups archives by which dependency tree they were built from, because that
# is the actual question. Two copies of one library merging with itself is what
# comdat is FOR and is harmless; the same symbol arriving from two different
# trees is the bug above.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${ANDROID_NDK:-$HOME/AppData/Local/Android/Sdk/ndk/30.0.16248370}"
NM="${NM:-$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe}"

# Prefer a build directory that actually has archives in it: the debug one is
# often empty, and finding zero would report a clean bill of health that means
# nothing at all.
BUILD="${1:-}"
if [ -z "$BUILD" ]; then
    for d in $(find "$ROOT/app/.cxx" -maxdepth 3 -type d -name "arm64-v8a" 2>/dev/null); do
        if [ "$(find "$d" -name '*.a' | wc -l)" -gt 0 ]; then BUILD="$d"; break; fi
    done
fi
if [ -z "$BUILD" ] || [ ! -d "$BUILD" ]; then
    echo "No build directory with archives. Build once first, or pass one as \$1." >&2
    exit 1
fi

echo "scanning $BUILD"
count=$(find "$BUILD" -name "*.a" | wc -l)
echo "$count archives"
if [ "$count" -eq 0 ]; then
    echo "No archives found -- build first, or this says nothing." >&2
    exit 1
fi

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

while IFS= read -r a; do
    # The archive's path under the build directory says which dependency it came
    # from: sfizz builds under library/, Surge under surge/ and libs/, ours at
    # the top. That grouping is the whole point.
    rel="${a#"$BUILD"/}"
    "$NM" --defined-only --extern-only "$a" 2>/dev/null |
        awk -v A="$rel" '$2=="T"||$2=="D"||$2=="B"{print "STRONG\t"$3"\t"A}
                         $2=="W"||$2=="V"{print "WEAK\t"$3"\t"A}'
done < <(find "$BUILD" -name "*.a") | sort -u > "$tmp"

python "$ROOT/tools/check_odr.py" "$tmp"
