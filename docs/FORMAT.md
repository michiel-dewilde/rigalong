# The library format, v0 (draft)

The contract between the app and everything that reads or writes its library:
the app itself, exports, the sync registry, and any tool. Requirements:
[REQUIREMENTS.md](../REQUIREMENTS.md) section 8. Plan: [PLAN.md](../PLAN.md)
D1-D4. **Draft for Michiel's review; nothing here is frozen until M1 ships.**

---

## 1. Objects

An **object** is an immutable sequence of bytes. Its identity is

```
sha256:<64 lowercase hex digits of SHA-256 over those bytes>
```

There are two kinds:

* **JSON objects**: everything the app defines (patches, songs, live sets,
  ...). Their bytes are their **canonical form** (section 2).
* **Binary objects**: foreign formats kept exactly as they are: sample files,
  SoundFonts, PDFs, Surge patches, DX7 banks, CP-88 Live Set Sounds. Their
  bytes are the file, except where section 5 normalises them.

## 2. Canonical JSON

* **RFC 8785 (JSON Canonicalization Scheme)**: UTF-8, no whitespace, object
  members sorted by UTF-16 code units, numbers in ECMAScript shortest form.
* **Every JSON object has a top-level `"@type"`**: `"bagosynths.<kind>/<schema
  version>"`, e.g. `"bagosynths.patch/1"`. The type is therefore part of the
  hash, and two kinds can never share one.
* **Parameter values are float32.** They are written as the shortest decimal
  that reads back as the same float32 (so `0.3`, never
  `0.30000001192092896`), then serialised by JCS. A value read back from an
  engine therefore hashes the same as the value that was stored (R8.1).
* **No nulls, no empty optional members.** An absent value is an absent member,
  so the same content cannot be written two ways.
* Integers stay within ±2^53.

## 3. References

A reference to another object is **always** a JSON object of this form:

```json
{"@ref": "sha256:9f86d0...", "@type": "bagosynths.patch/1"}
```

* `@ref` is required. `@type` is a hint for readers (for binary objects, a
  media type such as `"application/pdf"`) and is part of the hash like any
  member.
* No other construct may contain a key named `@ref`. Generic code can
  therefore find every reference in any object, of any type, including types
  added later: export closure, garbage collection and the reverse index
  (I8.22) need no knowledge of the types.
* User text can never be mistaken for a reference, since a string is never
  one.

## 4. Library entries (the mutable layer)

An **entry** is a name pointing at an object, with history (I8.9, I8.23).
Each version of an entry is itself a JSON object:

```json
{
  "@type": "bagosynths.entry/1",
  "entry": {"@ref": "sha256:<first version of this entry>"},
  "name": "Summer opener",
  "target": {"@ref": "sha256:...", "@type": "bagosynths.song/1"},
  "previous": {"@ref": "sha256:<the version this one replaces>"},
  "author": "Michiel De Wilde",
  "time": "2026-10-11T01:30:00Z",
  "tags": ["gig", "2026"],
  "description": "..."
}
```

* The **first version** has no `entry` and no `previous`. Its own hash is the
  entry's identity from then on.
* The only mutable state in a library is, per entry, which version is the
  latest.
* Two versions with the same `previous` are a **divergence** (I8.21, I8.31):
  both are kept, and the user chooses.
* A deleted entry is a version with no `target`.

## 5. Normalised binary objects

* **CP-88 Live Set Sound** (`application/vnd.bagosynths.cp88sound.v1`): the
  19 SysEx Bulk Dump messages concatenated, in address order (header, the 17
  data blocks in cp-liveset's `DATA_BLOCKS` order, footer), with **device
  number 0** and the header/footer address set to the **Current Sound Buffer
  (`7F 00`)**. The same sound read from slot 1:1 or 40:8 is then one object;
  where it is written is decided when it is written (S4.10).
* **DX7 voice**: the 128-byte packed voice (VMEM) of one voice, not the whole
  cartridge, so a patch refers to exactly the voice it plays.
* Everything else: the file's bytes, unchanged.

## 6. Object types (schema version 1)

Field lists are the minimum. Every type can gain optional members in a later
schema version without changing what existing objects mean.

| `@type` | holds |
|---|---|
| `bagosynths.patch/1` | `engine` (factory type name), `engineVersion`, `params` (parameter id -> value), and `state`: a reference to engine-specific bytes where the engine has its own patch format (a Surge patch, a DX7 voice, an SFZ instrument pack, a SoundFont plus preset number). Until packs are in the library (M9), `preset` names installed content instead: an SFZ instrument id, a Surge "Category/Name", a DX7 voice, a SoundFont file, an organ registration, a Plaits model |
| `bagosynths.song/1` | `instances` (name -> {patch ref, island preference by port}), `graph` (nodes, typed pins, wires), `values` (song value per parameter), `recall` (mode per parameter), `liveSets` (ordered refs), `cp88` (optional), `tempo` (optional), `leadSheet` (optional ref), `stageLayout`, `switching` (incoming selection -> live set) |
| `bagosynths.liveset/1` | `name`, `enabled` (node -> bool), `wires` (wire -> on/gain), `overrides` (parameter -> value), `recall` (per-parameter override of the song's mode), `transitions`, `onSelect` (MIDI to send), `cp88` (leave / select page:set / own ref), `visibleControls`, `leadSheetPage` |
| `bagosynths.setlist/1` | `songs` (ordered refs) |
| `bagosynths.environment/1` | `ports` (name, kind, width), `bindings` (port -> device match and settings), `externalSynths`, `switchingSources`, `pageTurner`, `click`, `stageScreen`, `retimers` (per device pair) |
| `bagosynths.usbprofile/1` | `match` (vendor, product, optional bcdDevice), `fbx` (the members of `struct fbx_profile`), `measuredOn`, `notes` |
| `bagosynths.pack/1` | `files` (relative path -> ref), `entry` (the instrument's main file), `licence`, `source` (where to fetch it again) |
| `bagosynths.catalog/1` | `packs` (refs, with name, size and licence summary) |
| `bagosynths.midnam/1` | wrapper: `device` (match), `document` (ref to the MIDNAM XML bytes) |

Parameter ids are the stable string ids from the native node API (D6), never
indices.

## 7. Storage: OCI Image Layout (D3)

The local library, an export file, and a registry repository hold the same
objects in the same way.

* **Blobs** at `blobs/sha256/<hex>`: every object, JSON and binary alike.
* **One manifest per entry version** (OCI image manifest, schema 2):
  * `artifactType`: the media type of the target, e.g.
    `application/vnd.bagosynths.song.v1+json`;
  * `config`: the entry version object, with media type
    `application/vnd.bagosynths.entry.v1+json`;
  * `layers`: the **whole closure** of the target, every object its references
    reach, each with its media type, digest and size (I8.29). Content that may
    not be redistributed is left out and stays reachable only by its `source`
    (I8.30);
  * annotation `org.opencontainers.image.title`: the entry's name.
* **Tags**: the entry's identity as 64 hex digits, pointing at its latest
  version's manifest. Tag syntax allows no free text, so names live in the
  entry version, never in tags (I8.28).
* `index.json` lists the tagged manifests; `oci-layout` is
  `{"imageLayoutVersion":"1.0.0"}`.
* An **export** is a tar of such a layout holding the chosen entries'
  manifests and their closures. **Import** verifies every blob against its
  digest before anything is added.

## 8. Media types

| object | media type |
|---|---|
| app JSON objects | `application/vnd.bagosynths.<kind>.v<schema>+json` |
| CP-88 Live Set Sound | `application/vnd.bagosynths.cp88sound.v1` |
| DX7 voice | `application/vnd.bagosynths.dx7voice.v1` |
| Surge patch | `application/vnd.bagosynths.surgepatch.v1` |
| lead sheet | `application/pdf` |
| samples | `audio/wav`, `audio/flac`, `audio/ogg` as the file is |
| SFZ text | `text/plain` |
| SoundFont | `application/vnd.bagosynths.soundfont` |
| MIDNAM | `application/xml` |
| anything else | `application/octet-stream` |

## 9. Open points for review

1. `@ref` objects instead of bare `"sha256:..."` strings: chosen so user text
   can never be read as a reference. Acceptable?
2. Normalising CP-88 sounds to device 0 and the current buffer (section 5).
3. One DX7 voice per object rather than whole cartridges.
4. Entry history objects carry author and time in their hash: two people
   saving the same content make two versions (but one target object).
