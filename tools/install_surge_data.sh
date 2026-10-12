#!/usr/bin/env bash
# Install Surge XT's factory content onto the tablet.
#
# Surge reads wavetables and patches from a real directory -- it takes fs::path,
# so it cannot read out of the APK. The content lives beside the instrument
# library, under the app's external files directory, for the same reasons: it is
# content rather than code, it is far too big to commit, and keeping it outside
# the APK means it can be replaced without a rebuild.
#
#   tools/install_surge_data.sh            # the 33 MB core: wavetables + patches
#   tools/install_surge_data.sh --full     # everything, ~483 MB
#
# Run the app once first, so that IT creates the files directory.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/third_party/surge/resources/data"
DEST="/sdcard/Android/data/be.mdw.rigalong/files/surge-xt"
ADB="${ADB:-adb}"

# Windows hosts: adb.exe is a native binary, so it needs a Windows source path,
# while MSYS would otherwise "helpfully" rewrite the /sdcard destination into
# one too. Convert the source by hand and switch the automatic rewriting off.
if command -v cygpath >/dev/null 2>&1; then
    SRC_NATIVE="$(cygpath -m "$SRC")"
    export MSYS2_ARG_CONV_EXCL="*"
else
    SRC_NATIVE="$SRC"
fi

if [ ! -d "$SRC" ]; then
    echo "No Surge checkout at $SRC -- run tools/init_deps.sh first." >&2
    exit 1
fi

# The core set. wavetables and patches_factory are what make Surge Surge; the
# preset folders are small and make the FX and modulator menus work. Everything
# else is either GUI (skins, fonts), a feature we do not use (tuning_library,
# impulses for the convolution reverb) or optional extra content that is 400 MB.
CORE=(wavetables patches_factory fx_presets modulator_presets)
FULL=("${CORE[@]}" impulses_factory impulses_3rdparty tuning_library
      wavetables_3rdparty patches_3rdparty)

SET=("${CORE[@]}")
if [ "${1:-}" = "--full" ]; then SET=("${FULL[@]}"); fi

echo "installing to $DEST"
$ADB shell "mkdir -p '$DEST'"

for d in "${SET[@]}"; do
    if [ ! -d "$SRC/$d" ]; then
        echo "  skip $d (not in this checkout)"
        continue
    fi
    echo "  $d"
    $ADB push "$SRC_NATIVE/$d" "$DEST/" >/dev/null
done

# adb push creates directories as the SHELL user, mode 770, which the app cannot
# traverse -- the same trap the instrument library set. Fix it, every time.
$ADB shell "chmod -R 777 '$DEST'"

echo
echo "installed:"
$ADB shell "du -sh '$DEST' 2>/dev/null; ls '$DEST'"
echo
echo "Restart the app (or press RESCAN) to pick it up."
