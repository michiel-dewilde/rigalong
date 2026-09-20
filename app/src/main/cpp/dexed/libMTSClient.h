#pragma once

#include <cmath>

// A stand-in for ODDSound's MTS-ESP client, which Dexed's msfa engine includes
// but which this project has no use for.
//
// MTS-ESP is an inter-process protocol: a separate "master" application
// broadcasts a tuning and every participating plugin follows it. That is a
// studio arrangement. This is one process driving one stage piano, and there is
// no master to talk to -- the same reason the Surge build sets
// SURGE_SKIP_ODDSOUND_MTS.
//
// msfa touches exactly two functions, both on the note-start path, and both are
// already guarded by `MTS_HasMaster()`:
//
//     dx7note.cc:51   if (tuning_state_->is_standard_tuning() && MTS_HasMaster(mtsClient))
//     dx7note.cc:348  double f = MTS_NoteToFrequency(mtsClient, note, channel - 1);
//
// With no master the first is false, so the second is never reached and standard
// tuning is used throughout.
//
// This header is found because `dx7note.h` includes it with quotes and it is not
// beside that file, so the compiler falls through to the include path -- the
// same trick that lets `beatrix_glue.c` supply what setBfree's `main.c` would
// have. No upstream file is patched for it.

struct MTSClient;

inline bool MTS_HasMaster(MTSClient*) { return false; }

inline double MTS_NoteToFrequency(MTSClient*, char midinote, char /*channel*/) {
    // Unreachable while MTS_HasMaster() is false, but defined rather than left
    // to the linker so that a future caller fails loudly at the right place
    // instead of silently retuning to nothing. Equal temperament, A4 = 440.
    return 440.0 * pow(2.0, (static_cast<double>(midinote) - 69.0) / 12.0);
}
