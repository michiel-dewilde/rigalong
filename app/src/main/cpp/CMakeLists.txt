cmake_minimum_required(VERSION 3.22.1)
# C as well as C++: setBfree's tonewheel engine is C99.
project(bagosynths LANGUAGES C CXX)

find_package(oboe REQUIRED CONFIG)

# ---------------------------------------------------------------- sfizz
# SFZ sampler core (BSD-2). Built as a static library into our one process --
# no plugin, no IPC. Everything that is not the library itself is switched off:
# the JACK client, the SMF renderer, tests, benchmarks, demos and dev tools all
# pull in dependencies we have no use for on Android.
set(SFIZZ_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/sfizz)

# NDK 30 ships Clang 19+, which turns -Wmissing-template-arg-list-after-template-kw
# into an error by default. sfizz's vendored atomic_queue uses the older
# `x.template foo(...)` spelling that this diagnostic objects to. The code is
# correct; the compiler is simply newer than the dependency. Suppress just that
# one diagnostic rather than lowering the bar generally.
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-missing-template-arg-list-after-template-kw")

set(SFIZZ_JACK        OFF CACHE BOOL "" FORCE)
set(SFIZZ_RENDER      OFF CACHE BOOL "" FORCE)
set(SFIZZ_SHARED      OFF CACHE BOOL "" FORCE)
set(SFIZZ_TESTS       OFF CACHE BOOL "" FORCE)
set(SFIZZ_BENCHMARKS  OFF CACHE BOOL "" FORCE)
set(SFIZZ_DEMOS       OFF CACHE BOOL "" FORCE)
set(SFIZZ_DEVTOOLS    OFF CACHE BOOL "" FORCE)
set(SFIZZ_USE_SNDFILE OFF CACHE BOOL "" FORCE)   # use the bundled dr_libs instead
# LTO across a library this size is slow to build and buys little here.
set(ENABLE_LTO        OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING     OFF CACHE BOOL "" FORCE)

add_subdirectory(${SFIZZ_DIR} ${CMAKE_BINARY_DIR}/sfizz EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------- beatrix
# setBfree's tonewheel organ engine (GPL-2-or-later), compiled straight into
# this process as a static library. Not the LV2 plugin and not the JACK program
# -- just the DSP sources, which are plain C99 and portable.
#
# The file list is upstream's own LV2OBJ (common.mak), minus:
#   memstream.c  -- an open_memstream() fallback; bionic has it, so the whole
#                   file compiles to nothing once HAVE_MEMSTREAM is defined.
# and it is compiled WITHOUT HAVE_ASEQ (ALSA sequencer) and WITHOUT
# HAVE_ZITACONVOLVE (the convolution reverb), exactly as upstream's note in
# common.mak instructs for a non-JACK build.
#
# src/main.c is deliberately absent: it is the JACK program's entry point. The
# three symbols it would have defined live in beatrix/beatrix_glue.c.
set(SETBFREE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/setbfree)

add_library(beatrix STATIC
        ${SETBFREE_DIR}/src/midi.c
        ${SETBFREE_DIR}/src/cfgParser.c
        ${SETBFREE_DIR}/src/program.c
        ${SETBFREE_DIR}/src/vibrato.c
        ${SETBFREE_DIR}/src/state.c
        ${SETBFREE_DIR}/src/tonegen.c
        ${SETBFREE_DIR}/src/pgmParser.c
        ${SETBFREE_DIR}/src/midnam.c
        ${SETBFREE_DIR}/b_whirl/eqcomp.c
        ${SETBFREE_DIR}/b_whirl/whirl.c
        ${SETBFREE_DIR}/b_overdrive/overdrive.c
        ${SETBFREE_DIR}/b_reverb/reverb.c
        beatrix/beatrix_glue.c)

target_include_directories(beatrix PUBLIC
        ${SETBFREE_DIR}/src
        ${SETBFREE_DIR}/b_whirl
        ${SETBFREE_DIR}/b_overdrive
        ${SETBFREE_DIR}/b_reverb)

target_compile_definitions(beatrix PRIVATE HAVE_MEMSTREAM)
set_target_properties(beatrix PROPERTIES C_STANDARD 99 POSITION_INDEPENDENT_CODE ON)

# Third-party C from 2003 warns a great deal and none of it is actionable here.
# Our own targets keep -Wall -Wextra; this one does not.
target_compile_options(beatrix PRIVATE -w $<$<CONFIG:Release>:-O3>)

# ---------------------------------------------------------------- surge xt
# Surge XT's synthesis engine (GPL-3-or-later): three oscillators a voice across
# a dozen oscillator types, two filter units, dual FX chains, twelve modulation
# sources -- and 641 factory patches.
#
# The DSP comes apart from the plugin because UPSTREAM made it come apart:
# SURGE_SKIP_JUCE_FOR_RACK exists so the VCV Rack port can consume this engine
# headless, and every JUCE target hides behind it. We are walking a maintained
# path, not forcing one. `surge-common` is the library; `surge-juce` that it
# links is an INTERFACE target carrying only JUCE_* defines, no JUCE code.
#
# Three small fixes ride on the forks; see LICENSING.md for each
# and LICENSING.md. None of them is in DSP.
set(SURGE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/surge)

# Surge links `pthread` because it is UNIX. Bionic has no libpthread -- the
# pthread symbols are in libc -- so ld.lld fails with `unable to find library
# -lpthread`. An INTERFACE target of that name satisfies the link line with
# nothing, which is exactly right, and beats patching upstream's CMake.
if(NOT TARGET pthread)
    add_library(pthread INTERFACE)
endif()

set(SURGE_SKIP_JUCE_FOR_RACK TRUE CACHE BOOL "" FORCE)   # no JUCE, no plugin targets
set(SURGE_SKIP_LUA           TRUE CACHE BOOL "" FORCE)   # LuaJIT needs host tools to cross-compile
set(SURGE_SKIP_ODDSOUND_MTS  TRUE CACHE BOOL "" FORCE)   # microtuning IPC; meaningless here
set(SURGE_SKIP_STANDALONE    TRUE CACHE BOOL "" FORCE)
set(SURGE_SKIP_WERROR        TRUE CACHE BOOL "" FORCE)   # a newer compiler is not a build failure
set(SURGE_BUILD_TESTRUNNER   OFF  CACHE BOOL "" FORCE)
set(SURGE_BUILD_XT           OFF  CACHE BOOL "" FORCE)
set(SURGE_BUILD_FX           OFF  CACHE BOOL "" FORCE)
set(SURGE_BUILD_CLAP         OFF  CACHE BOOL "" FORCE)

add_subdirectory(${SURGE_DIR} ${CMAKE_BINARY_DIR}/surge EXCLUDE_FROM_ALL)

# ----------------------------------------------------------------- dexed
# The DX7 engine: `msfa` (music-synthesizer-for-android, Apache-2.0), as it
# lives inside Dexed. Not the Dexed plugin, which is GPL-3 and JUCE-bound --
# just the six-operator FM synthesis core, which was written for Android in the
# first place and has no dependencies at all.
#
# `tuning.cc` is deliberately absent: it is the one file in msfa that pulls in
# JUCE, and the only thing that needed the tuning library. `createStandardTuning`
# is supplied by dexed/dexed_glue.cpp instead. See LICENSING.md.
#
# EngineMkI and EngineOpl live outside msfa but are equally free of JUCE, and
# they are the difference between "a DX7" and "a DX7 that sounds right" -- so
# all three cores are built and selectable.
set(DEXED_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/dexed)

add_library(msfa STATIC
        ${DEXED_DIR}/Source/msfa/dx7note.cc
        ${DEXED_DIR}/Source/msfa/env.cc
        ${DEXED_DIR}/Source/msfa/exp2.cc
        ${DEXED_DIR}/Source/msfa/fm_core.cc
        ${DEXED_DIR}/Source/msfa/fm_op_kernel.cc
        ${DEXED_DIR}/Source/msfa/freqlut.cc
        ${DEXED_DIR}/Source/msfa/lfo.cc
        ${DEXED_DIR}/Source/msfa/pitchenv.cc
        ${DEXED_DIR}/Source/msfa/porta.cpp
        ${DEXED_DIR}/Source/msfa/sin.cc
        ${DEXED_DIR}/Source/EngineMkI.cpp
        ${DEXED_DIR}/Source/EngineOpl.cpp
        dexed/dexed_glue.cpp)

# Our directory comes FIRST so that dexed/libMTSClient.h is found: msfa includes
# it with quotes, it is not beside dx7note.h, so the compiler falls through to
# the include path and finds our stub. Nothing upstream is patched for it.
target_include_directories(msfa PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/dexed
        ${DEXED_DIR}/Source
        ${DEXED_DIR}/Source/msfa)

set_target_properties(msfa PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_definitions(msfa PRIVATE DEXED_ID="bagosynths")
target_compile_options(msfa PRIVATE -w $<$<CONFIG:Release>:-O3>)


# ------------------------------------------------------------- fluidsynth
# SF2 and SF3 SoundFonts (LGPL-2.1). Asked for by name, and the format that
# makes "any General MIDI sound" a two-minute job rather than a project.
#
# It needed NO patch to build for Android, which none of the others managed.
# Two upstream decisions are why: as of 2.6 FluidSynth has no glib dependency
# at all (it vendors GLib's hash table as src/utils/fluid_hash.c, under the
# LGPL), and its OS abstraction is C++11 threads rather than pthreads -- so the
# bionic `-lpthread` trap that Surge fell into never arises.
#
# Every driver is off. FluidSynth can open ALSA, JACK, PulseAudio, Oboe,
# OpenSLES and more by itself, and it must do none of that here: this is a
# synthesis engine inside our graph, and the one thing allowed to touch the
# audio device is the engine in AudioEngine.cpp. `fluid_synth_write_float` is
# the entire interface we want.
#
# enable-network=off matters for more than size. FluidSynth's shell server
# listens on a TCP socket; a stage instrument has no business opening a port.
set(FLUIDSYNTH_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/fluidsynth)
set(STB_DIR        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/stb)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

foreach(_fs_off
        alsa jack oss dbus ladspa libsndfile midishare opensles oboe
        pulseaudio pipewire readline sdl3 dsound wasapi waveout winmidi
        network aufile openmp signalsmith native-dls)
    set(enable-${_fs_off} OFF CACHE BOOL "" FORCE)
endforeach()

add_subdirectory(${FLUIDSYNTH_DIR} ${CMAKE_BINARY_DIR}/fluidsynth EXCLUDE_FROM_ALL)

# The Ogg Vorbis decoder FluidSynth would otherwise have taken from libsndfile.
# See cpp/fluid/fluid_glue.c and the fluidsynth fork's SF3 commit:
# one public-domain file instead of libsndfile + libogg + libvorbis.
add_library(fluidglue STATIC fluid/fluid_glue.c)
target_include_directories(fluidglue PRIVATE ${STB_DIR})
set_target_properties(fluidglue PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_options(fluidglue PRIVATE -w $<$<CONFIG:Release>:-O3>)

# BAGOSYNTHS_STB_VORBIS is what the patch keys off. It goes on the FluidSynth target
# rather than globally, and `libfluidsynth` must link `fluidglue` so that the
# linker sees the definition after the reference -- static archive order is not
# something to leave to luck.
target_compile_definitions(libfluidsynth-OBJ PRIVATE BAGOSYNTHS_STB_VORBIS=1)

# NOTE the absence of `target_link_libraries(libfluidsynth ... fluidglue)`.
#
# That is the obvious way to say "libfluidsynth needs our decoder", and it does
# not work here: a static library records even its PRIVATE dependencies in its
# link interface, and FluidSynth's own export() and install(EXPORT) then refuse
# to export a target that depends on one of ours. $<BUILD_INTERFACE:> fixes the
# install but not the build-tree export, which is exactly what it excludes.
#
# So the dependency is resolved where it actually matters -- at the final link
# of libbagosynths.so, by listing fluidglue AFTER libfluidsynth below. An archive
# resolves its undefined symbols against what follows it on the link line, which
# is the oldest rule there is, and it leaves FluidSynth's CMake untouched.

# The portable half of this project. Nothing in engine/ may include an Android,
# Oboe, JNI or third-party-engine header, which is what makes it testable on a
# laptop -- see engine/CMakeLists.txt. Everything in THIS file is the other
# half: the parts that can only run on the device.
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine engine)

add_library(bagosynths SHARED
        native_bridge.cpp
        AudioEngine.cpp
        MidiInput.cpp
        Synth.cpp
        graph/Nodes.cpp
        graph/HammondNode.cpp
        graph/SurgeNode.cpp
        graph/DexedNode.cpp
        graph/PlaitsNode.cpp
        graph/FluidNode.cpp)

# Plaits is compiled into `surge::eurorack` already -- Surge's Twist oscillator
# IS a plaits::Voice -- and that target is PUBLIC on surge-common, so its
# headers reach us with no new dependency and nothing to fetch or patch.
#
# TEST=1 is not optional and not a test flag. stmlib picks between portable C
# and hand-written 32-bit ARM assembly (`ssat`, `vsqrt.f32`) on it; without it
# this will not even assemble on arm64. It is scoped to the one file rather
# than the target because TEST is far too common a macro to define globally,
# and it MUST match what eurorack itself was built with: it selects between two
# bodies for the same inline functions, and disagreeing about that across
# translation units is exactly the class of bug that cost a day in section 5c.
set_source_files_properties(graph/PlaitsNode.cpp PROPERTIES
        COMPILE_DEFINITIONS TEST=1)

target_include_directories(bagosynths PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${SFIZZ_DIR}/src)

target_compile_features(bagosynths PRIVATE cxx_std_20)

target_compile_options(bagosynths PRIVATE
        -Wall -Wextra -Wno-unused-parameter
        $<$<CONFIG:Release>:-O3>
        # Deliberately NOT -ffast-math: it disables NaN/Inf handling, which hides
        # bugs in filter/envelope code rather than preventing them. Denormals are
        # dealt with explicitly via FPCR (see Denormals.h).
        )

target_link_libraries(bagosynths
        bagosynths::engine
        beatrix
        msfa
        libfluidsynth
        fluidglue      # AFTER libfluidsynth -- see above
        surge::surge-common
        sfizz::sfizz
        oboe::oboe
        amidi      # AMidi: native MIDI, API 29+
        android
        log)
