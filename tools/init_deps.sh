#!/usr/bin/env bash
# Check out the dependency submodules this project builds against.
#
#   tools/init_deps.sh
#
# Everything under third_party/ is a submodule of a FORK, on the branch
# `bagosynths/main`, with a RELATIVE url in .gitmodules -- `../sfizz.git` and so
# on. Relative resolves against whatever origin this repository was cloned from,
# so ssh and https both work and neither is written down anywhere.
#
# So why is there a script at all, when `git submodule update --init
# --recursive` exists? One reason, and it is a big one:
#
#   SURGE CARRIES 23 SUBMODULES AND THIS BUILD USES 15.
#
# The other eight are JUCE and friends -- JUCE alone is most of the weight, and
# we build with SURGE_SKIP_JUCE_FOR_RACK so it is never compiled; LuaJIT would
# have to be cross-compiled through host tools and we build with SURGE_SKIP_LUA;
# pybind11, melatonin_inspector, clap-juce-extensions, MTS-ESP and surge-rs
# belong to targets we do not build. Naming the 15 is the difference between a
# checkout of a few hundred megabytes and one of several gigabytes.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# LF on disk, always, and set BEFORE anything is checked out.
#
# Git for Windows ships a SYSTEM gitconfig with core.autocrlf=true, and
# `git submodule update` clones and checks out in one subprocess -- so setting
# the flag afterwards is too late: the files are already CRLF, every one of them
# reads as modified, and the superproject reports every submodule dirty.
# Passing -c propagates through GIT_CONFIG_PARAMETERS to that subprocess, which
# is the only place early enough.
#
# core.longpaths belongs in the same breath. Submodules of submodules nest their
# git directories -- .git/modules/third_party/surge/modules/libs/eurorack/... --
# and a pack file at the bottom of that runs past the Windows 260-character
# limit, which surfaces as "cannot write keep file: Filename too long" followed
# by a clone that fails and retries forever. It costs nothing anywhere else.
LF=(-c core.autocrlf=false -c core.eol=lf -c core.longpaths=true)

# Shallow where it can be. A shallow submodule fetch fails when the recorded
# commit is not the tip of its branch, which happens as soon as a fork moves on,
# so every call falls back to a full one rather than stopping.
update() {   # update <repo-dir> <args...>
    local dir="$1"; shift
    git "${LF[@]}" -C "$dir" submodule update --init --depth 1 "$@" ||
        git "${LF[@]}" -C "$dir" submodule update --init "$@"
}

SURGE_SUBMODULES=(
    libs/eurorack/eurorack
    libs/fmt
    libs/simde
    libs/tuning-library
    libs/PEGTL
    libs/pffft
    libs/r8brain-free-src
    libs/zstd
    libs/sst/sst-cmake
    libs/sst/sst-basic-blocks
    libs/sst/sst-cpputils
    libs/sst/sst-effects
    libs/sst/sst-filters
    libs/sst/sst-waveshapers
)

echo "third_party:"
update "$ROOT"

# Surge: the 15, plus sst-plugininfra recursively. sst-plugininfra is the one
# Surge submodule that is patched, so on our surge fork its .gitmodules points
# at ../sst-plugininfra.git -- relative from surge, which resolves to our fork
# of it. The other fourteen stay pointed upstream, because nothing about them
# is modified and a fork that changes nothing can only go stale.
echo "surge (15 of 23 submodules):"
update "$ROOT/third_party/surge" "${SURGE_SUBMODULES[@]}"
update "$ROOT/third_party/surge" --recursive libs/sst/sst-plugininfra

# sfizz carries abseil, simde, dr_libs, wavpack and more, and builds them all.
# setBfree, dexed, fluidsynth and stb need none: dexed carries JUCE, MTS-ESP,
# the VST3 SDK and the CLAP extensions, every one of them for the plugin and
# none for the msfa engine, which is the only part compiled here.
echo "sfizz:"
update "$ROOT/third_party/sfizz" --recursive

# Persist the line-ending settings so later work in these repositories agrees,
# and re-normalise anything an earlier checkout left as CRLF.
git -C "$ROOT" submodule foreach --recursive --quiet \
    'git config core.autocrlf false; git config core.eol lf; git config core.longpaths true; git reset --hard -q' >/dev/null

echo
echo "third_party is ready. Build with:"
echo "  export JAVA_HOME=\"/c/Users/michi/.jdks/jbr-21.0.11\"   # JDK 21, not 25"
echo "  ./gradlew installRelease                               # never judge audio from a debug build"
