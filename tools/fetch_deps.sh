#!/usr/bin/env bash
# Clone the upstream engines this project links, at the exact revisions it was
# built against, and apply the few patches Android needs.
#
# third_party/ is over a gigabyte of other people's code and is NOT committed:
# it is recoverable exactly from these commits, which is what version control is
# for. The same commits are recorded in LICENSING.md, which is the copy that
# matters if the GPL source offer is ever exercised.
#
# Run from anywhere:  tools/fetch_deps.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party"
PATCHES="$ROOT/tools/patches"
mkdir -p "$DEST"

# name|url|commit
DEPS=(
    "sfizz|https://github.com/sfztools/sfizz.git|f5c6e29f23b8057867c08e88f5f6ac6738baa30b"
    "setbfree|https://github.com/pantherb/setBfree.git|2f41983b5b535bda9b1b1db38a06da29d50ff557"
    "surge|https://github.com/surge-synthesizer/surge.git|58914e59c608ed4384ba6002e44c3465c58b2e71"
    "dexed|https://github.com/asb2m10/dexed.git|2e182b3db85c09083ab13c8b9b00565ce7d9ff85"
    "fluidsynth|https://github.com/FluidSynth/fluidsynth.git|71e85b2ca6bf48641ba7e2261d8f2640ae473773"
    "stb|https://github.com/nothings/stb.git|2c980bb59875b0d32144a71867fbdebb2f77cd20"
)

# Surge carries 23 submodules and we need 15 of them. JUCE alone is most of the
# weight and we build with SURGE_SKIP_JUCE_FOR_RACK, so it is never needed;
# LuaJIT would have to be cross-compiled via host tools and we build with
# SURGE_SKIP_LUA; pybind11, melatonin_inspector, clap-juce-extensions,
# MTS-ESP and surge-rs belong to targets we do not build.
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

# repo-relative-path|patch file. Each patch is applied inside that repository,
# so a patch to a submodule names the submodule.
PATCH_LIST=(
    "surge|surge-add-subdirectory-safe.patch"
    "surge/libs/sst/sst-plugininfra|sst-plugininfra-android-backtrace.patch"
    "sfizz|sfizz-tunings-namespace.patch"
    "dexed|dexed-msfa-drop-tunings.patch"
    "fluidsynth|fluidsynth-sf3-without-libsndfile.patch"
    "fluidsynth|fluidsynth-add-subdirectory-safe.patch"
)

fetch_at() {   # dir url commit
    local dir="$1" url="$2" commit="$3"
    if [ -d "$dir/.git" ]; then
        local have
        have="$(git -C "$dir" rev-parse HEAD)"
        if [ "$have" = "$commit" ]; then
            echo "  already at $commit"
            return 1   # nothing fetched: caller may skip re-patching
        fi
        echo "  at $have, wanted $commit -- fetching"
    else
        echo "  cloning"
        git init -q "$dir"
        git -C "$dir" remote add origin "$url"
    fi

    # LF on disk, always. The checkout is patched with `git apply`, and a CRLF
    # working tree on Windows makes every patch fail to apply. This is also why
    # the setting is pushed into each submodule below.
    git -C "$dir" config core.autocrlf false
    git -C "$dir" config core.eol lf

    # Shallow: nothing here needs the history. Enough to build and to identify
    # the revision -- but NOT enough to hand to someone exercising the GPL
    # source offer, so re-clone with full history if that ever happens.
    git -C "$dir" fetch --depth 1 origin "$commit"
    git -C "$dir" checkout --detach -f FETCH_HEAD
    return 0
}

for dep in "${DEPS[@]}"; do
    IFS='|' read -r name url commit <<< "$dep"
    dir="$DEST/$name"
    echo "$name:"

    fresh=0
    fetch_at "$dir" "$url" "$commit" && fresh=1 || true

    if [ "$name" = "stb" ]; then
        # A collection of single-file public-domain libraries. We use exactly
        # one of them -- stb_vorbis.c -- and it has no submodules.
        :
    elif [ "$name" = "fluidsynth" ]; then
        # No submodules. Note what is NOT needed here: as of 2.6 FluidSynth has
        # no glib dependency at all (it vendors GLib's hash table as
        # src/utils/fluid_hash.c under the LGPL), and its OS abstraction is
        # C++11 threads rather than pthreads -- so none of the bionic trouble
        # the other engines caused applies. It builds for arm64 unpatched.
        :
    elif [ "$name" = "dexed" ]; then
        # Dexed carries JUCE, MTS-ESP, the tuning library, the VST3 SDK and the
        # CLAP extensions -- every one of them for the plugin, none of them for
        # the msfa engine, which is the only part compiled here. So: none.
        :
    elif [ "$name" = "surge" ]; then
        # Selective, because the full recursive set is JUCE and friends.
        # LF matters here as much as it does above, and it has to be set BEFORE
        # the checkout, not after. `git submodule update` clones AND checks out
        # in one subprocess, inheriting core.autocrlf=true from the system
        # gitconfig that Git for Windows installs -- so by the time a `foreach`
        # sets the flag, the files on disk are already CRLF, and every `git
        # apply` below fails on every hunk. Passing -c is the only place early
        # enough: it propagates to the clone through GIT_CONFIG_PARAMETERS.
        #
        # This was latent for as long as third_party was only ever built up by
        # hand; the first run of this script into an EMPTY tree is what found
        # it. That is the whole argument for the clean-clone test.
        git -C "$dir" -c core.autocrlf=false -c core.eol=lf submodule update --init --depth 1 "${SURGE_SUBMODULES[@]}"
        git -C "$dir" -c core.autocrlf=false -c core.eol=lf submodule update --init --recursive --depth 1 libs/sst/sst-plugininfra
        git -C "$dir" submodule foreach --recursive \
            'git config core.autocrlf false; git config core.eol lf; git reset --hard -q' >/dev/null
    else
        # sfizz carries its own submodules (abseil, simde, dr_libs, wavpack and
        # more); setBfree carries none, but running this for both is harmless
        # and survives either changing. A shallow submodule fetch fails when the
        # pinned commit is not a branch tip, which happens often enough to need
        # the fallback -- abseil's full history is the slow case.
        git -C "$dir" -c core.autocrlf=false -c core.eol=lf submodule update --init --recursive --depth 1 ||
            git -C "$dir" -c core.autocrlf=false -c core.eol=lf submodule update --init --recursive
        git -C "$dir" submodule foreach --recursive 'git config core.autocrlf false; git config core.eol lf; git reset --hard -q' >/dev/null
    fi
done

# ------------------------------------------------------------------- patches
#
# Six of them, none in DSP. THREE are latent upstream bugs that only show when
# a project is consumed via add_subdirectory -- two in Surge, one in FluidSynth,
# whose `uninstall` convenience target collides with the identically named one
# in Surge's vendored zstd, because CMake target names are global; one is bionic having no execinfo.h;
# two rename a vendored library out of the way of another copy of itself. The
# FluidSynth one routes SF3 sample decoding through stb_vorbis instead of
# libsndfile, which is the difference between one public-domain source file and
# a dependency tree of libsndfile + libogg + libvorbis.
#
# They are kept as patch files rather than as a fork so that the pinned commits
# above stay the honest answer to "what did you build".
echo
echo "patches:"
for entry in "${PATCH_LIST[@]}"; do
    IFS='|' read -r rel patch <<< "$entry"
    repo="$DEST/$rel"
    file="$PATCHES/$patch"

    # A submodule's .git is a FILE pointing at the real git dir, not a
    # directory, so test with rev-parse rather than with -d.
    if ! git -C "$repo" rev-parse --git-dir >/dev/null 2>&1; then
        echo "  SKIP $patch -- $rel is not checked out"
        continue
    fi
    if git -C "$repo" apply --reverse --check "$file" 2>/dev/null; then
        echo "  $patch already applied"
        continue
    fi
    git -C "$repo" apply "$file"
    echo "  applied $patch"
done

echo
echo "third_party is ready. Build with:"
echo "  export JAVA_HOME=\"/c/Users/michi/.jdks/jbr-21.0.11\"   # JDK 21, not 25"
echo "  ./gradlew installRelease                               # never judge audio from a debug build"
