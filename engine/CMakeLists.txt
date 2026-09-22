# The portable engine.
#
# The rule this directory exists to enforce: NOTHING HERE MAY INCLUDE AN ANDROID
# HEADER, an Oboe header, a JNI header, or any third-party engine. What is left
# is the part of this project that is pure logic -- the node graph, its compiler,
# and the small numeric helpers around it -- and the point of separating it is
# not tidiness. It is that pure logic can be TESTED, on a laptop, in a second,
# and the graph compiler has never been tested at all.
#
# That gap is not hypothetical. "A second edge into any input pin produced
# silence, with no error" survived in this code until an organ needed fan-in,
# because the only way to exercise the compiler was to play a tablet.
#
# What is deliberately NOT here yet: the six instrument nodes. Every one of them
# includes a third-party engine header (sfizz, setBfree, Surge, msfa, Plaits,
# FluidSynth), and none of those is built for the host in this project. Moving
# them would mean cross-compiling all six for a laptop to gain nothing testable.
# They keep their Android-side home until there is a reason better than symmetry.
cmake_minimum_required(VERSION 3.22)
project(bagosynths-engine LANGUAGES CXX)

add_library(bagosynths-engine STATIC
        src/Graph.cpp
        src/UsbDescriptors.cpp)

target_include_directories(bagosynths-engine PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include)

target_compile_features(bagosynths-engine PUBLIC cxx_std_20)

target_compile_options(bagosynths-engine PRIVATE
        -Wall -Wextra -Wno-unused-parameter
        $<$<CONFIG:Release>:-O3>
        # Deliberately NOT -ffast-math, for the same reason as the app target:
        # it disables NaN/Inf handling, which hides bugs in DSP rather than
        # preventing them. Denormals are dealt with explicitly, in Denormals.h.
        )

add_library(bagosynths::engine ALIAS bagosynths-engine)

# The tests are built by engine/tests/run_tests.sh with a plain compiler, not
# through CMake. See the comment at the top of that script: the whole argument
# for this directory is that it needs nothing, and a test runner that needs a
# CMake toolchain file is already more than nothing.
