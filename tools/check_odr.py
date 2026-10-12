"""Report symbols defined by more than one dependency. Driven by check_odr.sh.

Reads lines of "KIND<tab>SYMBOL<tab>ARCHIVE-PATH" and groups the archives by
which upstream tree they were built from. Two archives from the SAME tree
defining one symbol is ordinary comdat merging; two archives from DIFFERENT
trees defining it is the bug that cost an hour -- see FINDINGS section 8.
"""

import collections
import os
import sys


def tree(rel):
    """Which dependency an archive belongs to, from its path in the build dir."""
    parts = rel.replace("\\", "/").split("/")
    if parts[0] in ("surge", "libs"):
        return "surge"          # Surge and everything it vendors
    if parts[0] in ("library", "external", "src"):
        return "sfizz"          # sfizz and everything it vendors
    base = os.path.basename(rel)
    if base.startswith(("libsfizz", "libabsl", "libst_audiofile", "libcephes",
                        "libaiff", "libwavpack", "libkiss")):
        return "sfizz"
    if parts[0] == "fluidsynth":
        return "fluidsynth"
    if base == "libmsfa.a":
        return "dexed"
    if base == "libbeatrix.a":
        return "setbfree"
    if base == "libfluidglue.a":
        # Ours, and deliberately so: it carries a SECOND copy of stb_vorbis,
        # because sfizz vendors one too. Every public name in ours is prefixed
        # rigalong_, so the two cannot collide -- see cpp/fluid/fluid_glue.c.
        return "ours:" + base
    return "ours:" + base


# The standard library, the compiler's own helpers and the unwinder are meant to
# merge: there is one libc++ and one compiler here, so every archive that uses
# std::vector emits the same std::vector. Filtering them out is what leaves the
# signal visible -- without it the real answer is buried under a few hundred
# template instantiations.
_SHARED_PREFIXES = (
    "_ZNSt", "_ZSt", "_ZNKSt", "_ZNSa", "_ZNS", "_ZTVSt", "_ZTISt", "_ZTSSt",
    "_ZTv0_n24_NSt", "_ZThn", "_ZTh", "_ZTc",
    "__clang_call_terminate", "__cxa", "__gxx", "DW.ref.",
)


def is_shared_runtime(sym):
    return sym.startswith(_SHARED_PREFIXES)


def main(path):
    strong = collections.defaultdict(set)
    weak = collections.defaultdict(set)

    with open(path) as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 3:
                continue
            kind, sym, rel = parts
            (strong if kind == "STRONG" else weak)[sym].add((tree(rel), rel))

    def report(title, table, cross_only):
        rows = []
        for sym, places in table.items():
            if len(places) < 2:
                continue
            trees = sorted({t for t, _ in places})
            if cross_only and len(trees) < 2:
                continue
            if cross_only and is_shared_runtime(sym):
                continue
            rows.append((sym, trees, sorted(r for _, r in places)))
        rows.sort()
        print("\n%s: %d" % (title, len(rows)))
        for sym, trees, files in rows[:60]:
            print("  %s" % sym)
            print("      trees: %s" % ", ".join(trees))
            print("      in:    %s" % ", ".join(files))
        if len(rows) > 60:
            print("  ... and %d more" % (len(rows) - 60))
        return len(rows)

    n1 = report("STRONG duplicates (always an error)", strong, cross_only=False)
    n2 = report("Symbols defined by MORE THAN ONE dependency tree", weak, cross_only=True)

    if n1 == 0 and n2 == 0:
        print("\nClean: nothing is defined by two different dependency trees.")
        return 0

    print("\nLook at each one. Two copies of the same library at the SAME version")
    print("are harmless; at different versions they are the tuning-library bug.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
