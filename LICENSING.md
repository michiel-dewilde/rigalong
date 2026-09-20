# Licensing

**Decision (2026-09-19): Bag-O-Synths is free software, GPL-3.0-or-later.**

The goal is the best instrument reachable for the stage. The highest-quality free
engines — the tonewheel organ especially — are GPL, and working around that would
mean either worse sound or a lot of reimplementation for no musical gain. So the
project takes the GPL rather than designing around it.

This is revisitable. If selling ever becomes interesting, the question is not
"can this be undone" but "which components can be replaced" — see
[If this ever needs to be proprietary](#if-this-ever-needs-to-be-proprietary).

---

## What GPL-3.0-or-later actually obliges

For **personal use — playing it yourself, on your own gig** — nothing at all.
The GPL governs *distribution*. Until an APK goes to someone else, there are no
obligations whatsoever.

Once distributed (Play Store, F-Droid, or handing a friend an APK):

1. **Publish the source** of the whole combined work, under GPL-3.0-or-later.
   In practice: a public git repo, and a link from the app's about screen.
2. **Include the licence text** (`COPYING`) and preserve upstream copyright
   notices and licence headers.
3. **State what was changed** in any modified upstream source files.
4. **No additional restrictions** on recipients' rights.

That is the whole burden, and a public GitHub repo discharges most of it.

> **TODO:** drop the official GPLv3 text into `COPYING` (from
> <https://www.gnu.org/licenses/gpl-3.0.txt>) before the first GPL component
> lands. Not needed yet — nothing in the tree is GPL today.

---

## The one real trap: GPL-2-only cannot combine with GPL-3

This is the constraint that actually bites, and it is easy to trip over because
both are "the GPL".

* A project licensed **"GPL-2 or later"** can be combined with GPL-3 code — the
  "or later" clause lets it be used under GPL-3. Fine.
* A project licensed **"GPL-2 only"** is **incompatible with GPL-3 code**. The two
  cannot legally be linked into one binary, in either direction.

So before adding any GPL dependency, check whether its headers say *"version 2 of
the License, or (at your option) any later version"* or just *"version 2"*.

Known so far:

* **setBfree / Beatrix — GPL-2-or-later.** Combines with GPL-3. Safe.
* **ZynAddSubFX — GPL-2, needs verifying** whether it carries the "or later"
  clause. If it is GPL-2-only it cannot ship in the same binary as Surge XT.
  Not a problem today: Zyn is an optional M6 nice-to-have, Surge is not.

**Avoid AGPL regardless.** OpenB3 is AGPL-3, and its network clause buys nothing
for a local instrument while adding obligations. Since it is only a JUCE wrapper
around Beatrix, link **Beatrix directly** and use OpenB3 purely as a reference
for how to drive it. **Done, 2026-09-19** — and OpenB3 turned out not to be needed
as a reference either: setBfree's own `b_synth/lv2.c` shows the whole
instantiate/init/render sequence, which is what `HammondNode` follows.

---

## Component licences

### Currently in the tree

| Component | Licence | Notes |
|---|---|---|
| Bag-O-Synths' own code | GPL-3.0-or-later | this project |
| Oboe | Apache-2.0 | permissive, no obligations beyond attribution |
| **sfizz** | BSD-2 | SFZ sampler, `third_party/sfizz`, linked |
| **setBfree / Beatrix** | **GPL-2-or-later** | the tonewheel organ and Leslie, `third_party/setbfree`, linked. **This is the dependency the licence decision was made for.** |
| **Surge XT** | **GPL-3.0-or-later** | the synthesiser, `third_party/surge`, linked as `surge-common` without JUCE |
| **msfa** (inside Dexed) | **Apache-2.0** | the DX7 engine, `third_party/dexed/Source/msfa`, linked. Permissive, so it imposes nothing |
| **Plaits** (Mutable Instruments) | MIT | sixteen synthesis models, linked as part of `surge::eurorack` -- no separate clone, covered by the Surge pin |
| Plaits per-model parameter labels | **GPL-3-or-later** | a table of names copied from Surge XT's `TwistOscillator` into `cpp/graph/PlaitsNode.cpp`. Attributed in the source. Combines because this project is GPL-3-or-later too |
| **FluidSynth** | **LGPL-2.1-or-later** | SF2/SF3, `third_party/fluidsynth`, linked statically. See the note below -- this is the first LGPL component |
| **stb_vorbis** | public domain / MIT | `third_party/stb`, one file, the Ogg Vorbis decoder for SF3 samples |

**The Dexed PLUGIN is not linked.** `Source/msfa` and the two alternative FM
cores (`EngineMkI.cpp`, `EngineOpl.cpp`) are Apache-2.0 and JUCE-free; the
plugin around them is GPL-3 and JUCE-bound, and none of it is compiled here.
Voice allocation, cartridge parsing and MIDI handling are this project's own
code. `unpackVoice()` was written from the DX7 SysEx specification, with Dexed's
`PluginData.cpp` as the reference for the "don't care" bit masks the
specification leaves implicit — noted here because that is a GPL-3 file and the
distinction between reading it and copying it is worth being straight about.

**The 33 DX7 cartridges bundled in the APK are Dexed's, and are never copied
here.** They are unpacked at build time straight out of
`third_party/dexed/assets/builtin_pgm.zip` — `Dexed_01.syx` and 32
`SynprezFM_*.syx`, 1056 voices — at the dexed revision pinned below.

That is a deliberate change from carrying our own copy, and it simplifies this
paragraph rather than complicating it: **we ship what Dexed ships, from Dexed's
own tree, at a recorded revision.** There is no second copy to drift, and the
provenance of every byte is a commit hash. (The copy this repository used to
carry was verified byte-identical to that zip, all 33 files, before it was
deleted — so nothing changed in the APK.)

This is content rather than code, so it carries its own terms and not ours.
Dexed distributes these files with its own binaries; DX7 patch data of this
vintage is widely treated as freely redistributable, and the SynprezFM
collection was published for exactly this use — recorded here so the question
is not silently skipped. If any rights-holder objects, the answer is to drop the
unpack step: the DX7 works without them, on its init voice, and user cartridges
load from the external folder.


**FluidSynth is LGPL-2.1, and it is linked statically.** That is the first
component here whose licence is neither permissive nor GPL, so it is worth being
precise rather than waving at it.

The LGPL permits combining with a GPL-3 work — section 3 of LGPL-2.1 explicitly
allows relicensing a copy under the GPL, and in any case the GPL-3 result is the
stricter licence, so nothing is lost to the user. What the LGPL adds in the
static case is a **relinking obligation**: a recipient must be able to replace
their copy of FluidSynth with a modified one and rebuild the result.

For this project that obligation is already satisfied by how it is built. The
whole thing is GPL-3-or-later with complete corresponding source, FluidSynth is
consumed at a pinned upstream commit recorded below, and `tools/fetch_deps.sh`
plus `tools/patches/` reproduce the exact tree that was compiled. Anyone can
change the pin, or edit `third_party/fluidsynth`, and rebuild. No separate
provision is needed — but the reasoning is recorded here so that a future change
(shipping a prebuilt `.so` without sources, say) is recognised as the thing that
would break it.

**The two FluidSynth patches are ours and are GPL-3-or-later**, like the rest of
this project's code; they are distributed as patch files, not as a fork, so the
LGPL work itself stays identifiable. `cpp/fluid/fluid_glue.c` is likewise ours.

**SoundFonts are content and are NOT distributed with this project.** An SF2 or
SF3 file carries its own licence, and several popular General MIDI fonts are free
to use but not free to redistribute. None is in the APK or the repository;
`tools/install_soundfont.sh` puts one on the device, and its header names the
usual free choices. The font it installs by default is FluidSynth's own test
font, *Vintage Dreams Waves* by Ian Wilson — freeware, redistributable with its
copyright notice, which is pushed alongside it.

**Surge XT's own dependencies come with it**, and the ones actually linked are
permissive or compatible: sst-basic-blocks / sst-filters / sst-waveshapers /
sst-effects / sst-cpputils / sst-plugininfra (MIT), simde (MIT), fmt (MIT),
PEGTL (MIT/Boost), zstd (BSD/GPL-2 dual), tuning-library (MIT), pffft (BSD-like),
r8brain-free-src (MIT), sqlite (public domain), eurorack / Mutable Instruments
(MIT), airwindows (MIT), binn (Apache-2.0). LuaJIT (MIT) and MTS-ESP are **not**
linked — the build sets `SURGE_SKIP_LUA` and `SURGE_SKIP_ODDSOUND_MTS`. JUCE
(GPL-3 or commercial) is **not** linked: `SURGE_SKIP_JUCE_FOR_RACK` keeps it out
of the tree entirely, which is worth stating because JUCE's licence is the one
people expect to be a problem here. Since the whole binary is GPL-3-or-later,
all of this combines.

**Surge XT's factory content** (`resources/data`: wavetables, patches, presets)
ships to the device but **not** inside the APK — see
`tools/install_surge_data.sh`. It is distributed by the Surge Synth Team under
the same terms as the project, and is covered by the same source offer.

**We patch four upstream files**, all recorded in `tools/patches/` and applied by
`tools/fetch_deps.sh`. Under GPL these are modifications we distribute, so they
must go out with the corresponding source — keeping them as patch files against
pinned commits is what makes that honest, and is why the dependency is not
forked.

**setBfree, verified rather than assumed.** Every file actually compiled into the
binary was checked for the "or later" clause, not just the top-level `COPYING`
(which is the bare GPL-2 text and would have been the wrong thing to read):
`tonegen.c`, `vibrato.c`, `midi.c`, `program.c`, `state.c`, `cfgParser.c`,
`pgmParser.c`, `midnam.c`, `global_inst.h`, `b_whirl/whirl.c`, `b_whirl/eqcomp.c`,
`b_reverb/reverb.c`. All carry *"either version 2, or (at your option) any later
version"*. `b_overdrive/overdrive.c` is machine-generated by `overmaker.c` and
carries no header of its own; it inherits the project's terms.

Since the whole binary is GPL-3-or-later, this combines. It also means
**distributing the APK obliges us to offer the complete corresponding source**,
including the exact `third_party` revisions — so those must stay pinned and
recorded, not floating. As built:

| dependency | upstream | commit | dated |
|---|---|---|---|
| sfizz | github.com/sfztools/sfizz | `f5c6e29f23b8057867c08e88f5f6ac6738baa30b` | 2025-03-17 |
| setBfree | github.com/pantherb/setBfree | `2f41983b5b535bda9b1b1db38a06da29d50ff557` | 2026-09-08 |
| Surge XT | github.com/surge-synthesizer/surge | `58914e59c608ed4384ba6002e44c3465c58b2e71` | 2026-09-18 |
| Dexed (msfa) | github.com/asb2m10/dexed | `2e182b3db85c09083ab13c8b9b00565ce7d9ff85` | 2026-03-15 |
| FluidSynth | github.com/FluidSynth/fluidsynth | `71e85b2ca6bf48641ba7e2261d8f2640ae473773` | 2026-09-19 (tagged 2.6.1) |
| stb (stb_vorbis) | github.com/nothings/stb | `2c980bb59875b0d32144a71867fbdebb2f77cd20` | 2026-08-01 |

Surge's own submodules are pinned by that commit; `tools/fetch_deps.sh` fetches
the 15 of 23 that are actually needed.

Both were cloned shallow (`--depth 1`). A shallow clone is enough to build and
enough to identify the revision, but **not** enough to hand someone if the source
offer is ever exercised — re-clone at that commit with full history.

### Planned — permissive (no copyleft obligation)

| Component | Licence | Role |
|---|---|---|
| ~~**msfa**~~ | Apache-2.0 | **shipped 2026-09-20** — see above |
| ~~**Plaits**~~ (Mutable Instruments) | MIT | **shipped 2026-09-20** — sixteen synthesis models. No separate clone: it is `surge/libs/eurorack`, already vendored by Surge XT and covered by that pin |
| **Mutable Instruments** (Clouds, Rings, Elements) | MIT | granular texture, modal and physical resonators. Clouds is in Surge's copy too; Rings and Elements are **not**, and cloning `pichenettes/eurorack` for them brings a second `stmlib` |
| **DaisySP** | MIT | DSP building blocks |
| **Airwindows** | MIT | effects |
| **FFTConvolver** | MIT | convolution reverb |
| **FAUST**-generated C++ | permissive | custom DSP |

### Planned — GPL (this is what the decision buys)

| Component | Licence | Role |
|---|---|---|
| ~~**Surge XT**~~ | GPL-3 | **shipped 2026-09-20** — see above |
| ~~**Dexed** (the plugin)~~ | GPL-3 | **not needed** — `msfa` alone was enough |
| **Dragonfly Reverb** (Freeverb3) | GPL-3 | best free algorithmic reverbs |
| ~~**FluidSynth**~~ | LGPL-2.1 | **shipped 2026-09-20** — see above |
| **OB-Xf**, **Odin 2**, **Vital/Vitalium** | GPL-3 | optional further synths |

### Sample content — separate question

Instrument libraries carry their own terms, independent of the code:

* **Salamander Grand Piano** — CC-BY
* **VSCO-2 Community Edition** — CC0
* **Pianobook** libraries — per-library, usually free for any use
* **Impulse responses** (OpenAIR etc.) — usually CC

Do not bundle sample content into the APK without checking each licence. Shipping
an empty app that *loads* user-supplied libraries sidesteps the question entirely,
and is the better design anyway given library sizes.

---

## If this ever needs to be proprietary

The permissive layer is deliberately the load-bearing one, so this is not a dead
end. What would have to be replaced:

* **The organ (Beatrix)** — the hard one. There is no permissive tonewheel model
  of comparable quality. Options: write one (additive synthesis with tonewheel
  leakage, key click and a Leslie is very achievable), or keep the organ in a
  personal-only build.
* **Surge XT** — replaceable by building on DaisySP / Mutable / FAUST, at the cost
  of the patch library.
* **Dragonfly Reverb** — easily replaced by an own FDN or convolution via
  FFTConvolver (MIT).

Everything else — the sampler, FM, physical modelling, effects — is already
permissive and would carry over untouched.

---

## Distribution notes

* **F-Droid** is the natural home for a GPL Android app and has no target-API
  deadline pressure.
* **Google Play** is generally considered compatible with the GPL (unlike some
  other stores, its terms do not impose extra restrictions on recipients), but it
  does impose the API 36 target requirement — see [README.md](README.md).
