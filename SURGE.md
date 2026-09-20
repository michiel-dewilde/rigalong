# The Surge XT spike — result

**Done, 2026-09-20. It worked, by a wide margin, and it shipped.**

This file was the working brief; it is now the record of what the four questions
turned out to answer. The durable rules are in [FINDINGS.md](FINDINGS.md)
section 5c, and the numbers in [MEASUREMENTS.md](MEASUREMENTS.md) section 5c.

---

## What shipped

Surge XT's synthesis engine runs as a graph node beside the sampler and the
organ: three oscillators a voice across a dozen oscillator types including
wavetable and FM, two filter units, dual FX chains, twelve modulation sources —
and **641 factory patches**, browsable by name on the tablet.

```
MIDI In --+--> sfizz    --+
          |               |
          +--> Hammond  --+--> Reverb --> Limiter --> Audio Out
          |               |
          +--> Surge XT --+
```

The panel exposes the patch, the eight macros and a level. Deliberately **not** a
Surge editor: Surge has around 2000 parameters, a UI for a synth that deep is its
own project, and a half-built one would be worse than none — the whole point of
641 factory patches is that the sound design is already done. The macros are what
each patch's author chose to put under your hand, so they are the right
performance controls by construction rather than by our guess.

## The four questions, answered

**1. Does the engine build for arm64 under NDK 30 without JUCE? Yes.** The
escape hatch is upstream's own: `SURGE_SKIP_JUCE_FOR_RACK` exists so the VCV Rack
port can consume this engine headless, and every JUCE target hides behind it. The
single most important fact of the whole spike — it moved Surge from "risky" to
"ordinary", because the path is maintained rather than forced.

**2. Can it be driven headless? Yes, and the API is small.** `SurgeSynthesizer`
plus a `PluginLayer` with two methods a host may legitimately ignore. `BLOCK_SIZE`
is a compile-time 32, so our 128-frame render block is exactly four `process()`
calls and MIDI lands with 0.73 ms resolution.

**3. What does the content weigh? 33 MB for the useful part**, out of 483 MB —
the rest is 3rd-party extra content, GUI skins and impulse responses we do not
use. It installs beside the instrument library with
`tools/install_surge_data.sh`. Absent, Surge still runs on its init patch.

**4. What does it cost? About what the organ costs.** Median factory patch at
eight voices: **0.064 ms per 128-frame block, 2.2%** of the 2.902 ms budget.
Worst of all 641 at eight voices: 0.577 ms. The abort threshold was roughly 1 ms
for a *single* voice; the worst patch in the library costs **0.157 ms** for one.

## What it actually cost to get there

Four upstream patches, none in DSP, all in [tools/patches/](tools/patches/):

* bionic has no `execinfo.h`, so `sst-plugininfra`'s `backtrace()` call is
  stubbed on Android;
* two latent upstream CMake bugs that only appear via `add_subdirectory`
  (`CMAKE_SOURCE_DIR` and `CMAKE_PROJECT_VERSION_MAJOR` both mean *the top-level
  project*, which is us);
* **sfizz's vendored tuning library is renamed.**

That last one was the real work. sfizz and Surge both vendor the Surge Synth Team
tuning library, at different versions, in the same namespace, with incompatible
struct layouts — a textbook ODR violation that neither compiler nor linker
mentions. It crashed every launch, deterministically, and the identical code ran
perfectly as a standalone probe. Two wrong turns (thread stack size, compile
flags) before the fault address `0x4088ffffffffffe8` was read for what it was:
the double 800.0 sitting where a `std::string*` belonged. The durable lesson is
in FINDINGS section 8, and it is not about tuning libraries — it is about any two
dependencies that vendor the same code.

## What is not done

* **The macros are unlabelled.** Surge knows each macro's name per patch; the
  panel shows "M1".."M8". Reading the names across is small and worth doing.
* **No patch search or favourites.** 641 patches in one alphabetical list is a
  lot of scrolling; category filtering is the obvious next step.
* **Surge's own effects are unused.** Each patch's dual FX chains run, but the
  node output still goes through our Reverb and Limiter as well.
* **The 3rd-party content (406 MB) is not installed.** `--full` does it; nobody
  has needed it yet.
* **Michiel has not played it.** The engine is verified audible and measured, but
  the verdict on how it sounds is his to give.

## The fallback, unused

**Mutable Instruments Plaits** — MIT, sixteen synthesis models, no dependencies.
It was the plan if Surge turned out to be a dead end. It did not, so Plaits
remains a nice-to-have rather than a necessity.
