#!/usr/bin/env bash
# Put a SoundFont on the tablet.
#
# SoundFonts are CONTENT, not code. They have their own licences, a General MIDI
# font is 30 MB or more, and none of that belongs in the APK -- exactly the rule
# the SFZ instrument library and the Surge factory data already follow. So they
# live in the app's external files directory and this script puts them there.
#
#   tools/install_soundfont.sh                     # the test font from FluidSynth
#   tools/install_soundfont.sh path/to/GeneralUser.sf2 ...
#
# Where to get a real one. The app plays anything in SF2 or SF3, and the sensible
# default for "any General MIDI sound" is one of:
#
#   GeneralUser GS  ~30 MB, SF2   https://www.schristiancollins.com/generaluser
#                   The usual recommendation: complete GM/GS, well balanced,
#                   free to distribute under its own licence.
#   FluidR3 GM      ~140 MB, SF2  packaged as fluid-soundfont-gm on Debian
#   GeneralUser GS  as SF3        much smaller, and SF3 works here -- the Ogg
#                                 Vorbis samples decode through stb_vorbis.
#
# Read the licence that comes with whichever you choose. Several popular fonts
# are free to use but NOT free to redistribute, which is one more reason they are
# not in this repository.
set -u
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
PKG=be.mdw.bagosynths
DEST="/sdcard/Android/data/$PKG/files/soundfonts"

if [ ! -x "$ADB" ] && ! command -v "$ADB" >/dev/null 2>&1; then
    echo "adb not found at $ADB -- set ADB=/path/to/adb" >&2
    exit 1
fi

files=("$@")
if [ ${#files[@]} -eq 0 ]; then
    # FluidSynth's own test font: freeware, redistributable with its copyright
    # notice, and it ships as BOTH .sf2 and .sf3 of identical content -- which
    # makes it the right thing to verify SF3 decoding against, since the two
    # should sound the same.
    default_dir="$ROOT/third_party/fluidsynth/sf2"
    if [ ! -d "$default_dir" ]; then
        echo "no files given, and $default_dir is missing." >&2
        echo "Run tools/init_deps.sh first, or pass a .sf2/.sf3 path." >&2
        exit 1
    fi
    files=(
        "$default_dir/VintageDreamsWaves-v2.sf2"
        "$default_dir/VintageDreamsWaves-v2.sf3"
        "$default_dir/COPYRIGHT.txt"
    )
    echo "no files given -- installing FluidSynth's test font (Vintage Dreams"
    echo "Waves by Ian Wilson, freeware; see COPYRIGHT.txt beside it)."
fi

"$ADB" shell mkdir -p "$DEST" || exit 1

for f in "${files[@]}"; do
    if [ ! -f "$f" ]; then
        echo "  SKIP $f -- not a file"
        continue
    fi
    name="$(basename "$f")"
    size="$(du -h "$f" 2>/dev/null | cut -f1)"
    echo "  $name ($size)"
    # cygpath so that a Windows adb understands a path this shell produced.
    if command -v cygpath >/dev/null 2>&1; then
        "$ADB" push "$(cygpath -m "$f")" "$DEST/$name" >/dev/null || exit 1
    else
        "$ADB" push "$f" "$DEST/$name" >/dev/null || exit 1
    fi
done

# adb push creates the directory and its files as the SHELL user, mode 770,
# which the app cannot traverse -- it reads SoundFonts and finds none, silently.
# The same trap the instrument library and the Surge data both set. Fix it every
# time rather than remembering to.
"$ADB" shell "chmod -R 777 '$DEST'"

echo
echo "installed to $DEST"
"$ADB" shell ls -la "$DEST" 2>/dev/null
echo
echo "Restart the app, or tap FONT in the SOUNDFONT panel to pick one."
